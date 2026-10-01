package com.lapcast;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Renders the streamed laptop screen and turns touch/keyboard input into protocol messages. */
public class RemoteView extends View {
    public interface Listener { void onState(String msg, boolean fatal); }
    /** Called when zoom level changes so the activity can show/hide scroll controls. */
    public interface ZoomListener { void onZoomChanged(int percent); }
    /** Called after a non-modifier action is sent, so the activity can release sticky modifiers. */
    public interface ActionListener { void onActionSent(); }

    // Modifier key names that should NOT trigger auto-release of other modifiers
    private static final Set<String> MODIFIER_NAMES = new HashSet<>();
    static {
        MODIFIER_NAMES.add("Control"); MODIFIER_NAMES.add("Alt");
        MODIFIER_NAMES.add("Shift"); MODIFIER_NAMES.add("Meta");
    }

    public boolean padMode = false;                 // false = direct touch, true = trackpad
    private static final float GAIN = 1.6f;         // trackpad sensitivity (fraction of screen per fraction of view)
    private final float density = getResources().getDisplayMetrics().density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint curFill = new Paint(Paint.ANTI_ALIAS_FLAG), curStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint msgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile Bitmap frame;
    private volatile float curX = -1, curY = -1;
    private OkHttpClient http;
    private WebSocket ws;
    private Listener listener;
    private ZoomListener zoomListener;
    private ActionListener actionListener;
    private volatile boolean closed;
    private boolean kbVisible;

    // ---- Zoom & scroll state --------------------------------------------------
    private float zoomLevel = 1.0f;
    private float scrollX = 0f, scrollY = 0f;  // 0..1 normalized scroll offset
    private boolean keyboardShowing = false;

    // ---- High-quality bitmap decode options -----------------------------------
    private final BitmapFactory.Options decodeOpts = new BitmapFactory.Options();

    public RemoteView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        curFill.setColor(0xCCFFFFFF);
        curStroke.setColor(0xFF000000); curStroke.setStyle(Paint.Style.STROKE); curStroke.setStrokeWidth(2 * density);
        msgPaint.setColor(0xFF888888); msgPaint.setTextSize(20 * density); msgPaint.setTextAlign(Paint.Align.CENTER);

        // High-quality decode: never down-sample, prefer ARGB_8888
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decodeOpts.inSampleSize = 1;
        decodeOpts.inScaled = false;
    }

    public void setZoomListener(ZoomListener l) { zoomListener = l; }
    public void setActionListener(ActionListener l) { actionListener = l; }

    // ---- Zoom controls --------------------------------------------------------
    public float getZoomLevel() { return zoomLevel; }
    public int getZoomPercent() { return Math.round(zoomLevel * 100); }

    public void zoomIn() {
        float oldZoom = zoomLevel;
        zoomLevel = Math.min(zoomLevel + 0.25f, 5.0f);
        adjustScrollForZoomChange(oldZoom, zoomLevel);
        clampScroll();
        notifyZoom();
        invalidate();
    }

    public void zoomOut() {
        float oldZoom = zoomLevel;
        zoomLevel = Math.max(zoomLevel - 0.25f, 0.5f);
        adjustScrollForZoomChange(oldZoom, zoomLevel);
        clampScroll();
        notifyZoom();
        invalidate();
    }

    public void zoomReset() {
        zoomLevel = 1.0f;
        scrollX = 0f;
        scrollY = 0f;
        notifyZoom();
        invalidate();
    }

    private void adjustScrollForZoomChange(float oldZoom, float newZoom) {
        if (oldZoom == newZoom) return;
        scrollX = scrollX * oldZoom / newZoom + (1f / newZoom - 1f / oldZoom) * 0.5f;
        scrollY = scrollY * oldZoom / newZoom + (1f / newZoom - 1f / oldZoom) * 0.5f;
    }

    private void clampScroll() {
        if (zoomLevel <= 1.0f) {
            scrollX = 0f;
            scrollY = 0f;
            return;
        }
        float maxScrollX = Math.max(0, 1f - 1f / zoomLevel);
        float maxScrollY = Math.max(0, 1f - 1f / zoomLevel);
        scrollX = Math.max(0, Math.min(scrollX, maxScrollX));
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY));
    }

    public boolean needsScrollBar() {
        return zoomLevel > 1.001f || keyboardShowing;
    }

    public void setKeyboardShowing(boolean showing) {
        keyboardShowing = showing;
        notifyZoom();
        invalidate();
    }

    public void scrollBy(float dx, float dy) {
        scrollX += dx;
        scrollY += dy;
        clampScroll();
        invalidate();
    }

    private void notifyZoom() {
        if (zoomListener != null) zoomListener.onZoomChanged(getZoomPercent());
    }

    /** Notify the activity that a non-modifier action was sent (for sticky modifier release). */
    private void notifyAction() {
        if (actionListener != null) {
            ui.post(() -> { if (actionListener != null) actionListener.onActionSent(); });
        }
    }

    // ---------------------------------------------------------------- network
    public void connect(String hostPort, String pin, Listener l) {
        listener = l;
        closed = false;
        http = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(10, TimeUnit.SECONDS).build();
        Request req = new Request.Builder().url("ws://" + hostPort + "/ws").build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) { send("auth", "pin", pin); }
            @Override public void onMessage(WebSocket w, String t) {
                try {
                    JSONObject o = new JSONObject(t);
                    if ("cur".equals(o.optString("t"))) {
                        curX = (float) o.getDouble("x"); curY = (float) o.getDouble("y");
                        postInvalidateOnAnimation();
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onMessage(WebSocket w, ByteString b) {
                byte[] d = b.toByteArray();
                Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length, decodeOpts);
                if (bm != null) { frame = bm; postInvalidateOnAnimation(); }
            }
            @Override public void onClosing(WebSocket w, int code, String reason) { w.close(1000, null); }
            @Override public void onClosed(WebSocket w, int code, String reason) {
                fail(code == 4001 ? "Wrong PIN" : "Disconnected");
            }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                fail("Connection failed: " + t.getMessage());
            }
        });
        requestFocus();
    }

    private void fail(String msg) {
        if (closed) return;
        closed = true;
        ui.post(() -> { if (listener != null) listener.onState(msg, true); });
    }

    public void disconnect() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        if (ws != null) ws.close(1000, "bye");
        if (http != null) http.dispatcher().executorService().shutdown();
        frame = null;
    }

    public boolean isConnected() {
        return ws != null && !closed;
    }

    private void send(String t, Object... kv) {
        try {
            JSONObject o = new JSONObject().put("t", t);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            if (ws != null) ws.send(o.toString());
        } catch (Exception ignored) {}
    }

    // public input API used by the toolbar
    public void key(String name, boolean down) {
        send("key", "k", name, "d", down ? 1 : 0);
        // Notify action for non-modifier key presses so sticky modifiers auto-release
        if (down && !MODIFIER_NAMES.contains(name)) {
            notifyAction();
        }
    }
    public void tap(String name) { key(name, true); key(name, false); }
    public void text(String s) {
        send("txt", "s", s);
        notifyAction();
    }
    public void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        requestFocus();
        kbVisible = !kbVisible;
        if (kbVisible) imm.showSoftInput(this, 0); else imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- drawing
    private final RectF baseRect = new RectF();
    private final RectF dst = new RectF();

    private void layoutRect() {
        Bitmap b = frame;
        if (b == null) { dst.setEmpty(); baseRect.setEmpty(); return; }
        float s = Math.min(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
        float w = b.getWidth() * s, h = b.getHeight() * s;
        baseRect.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);

        // Apply zoom
        float zw = w * zoomLevel, zh = h * zoomLevel;
        float ox = scrollX * (zw - getWidth());
        float oy = scrollY * (zh - getHeight());

        float left = (getWidth() - zw) / 2f - ox;
        float top = (getHeight() - zh) / 2f - oy;
        dst.set(left, top, left + zw, top + zh);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        layoutRect();
        Bitmap b = frame;
        if (b == null) { c.drawText("Connecting…", getWidth() / 2f, getHeight() / 2f, msgPaint); return; }
        c.drawBitmap(b, null, dst, bmpPaint);
        if (curX >= 0) {
            float cx = dst.left + curX * dst.width(), cy = dst.top + curY * dst.height();
            c.drawCircle(cx, cy, 7 * density, curFill);
            c.drawCircle(cx, cy, 7 * density, curStroke);
        }
    }

    /** Convert view-space x to normalized laptop coordinate (0..1) accounting for zoom+scroll. */
    private double nx(float x) { return Math.min(1f, Math.max(0f, (x - dst.left) / dst.width())); }
    private double ny(float y) { return Math.min(1f, Math.max(0f, (y - dst.top) / dst.height())); }

    /** Returns true if (x, y) is inside the rendered image rectangle. */
    private boolean insideImage(float x, float y) {
        return dst.contains(x, y);
    }

    // ---------------------------------------------------------------- touch
    private float sx, sy, lx, ly, sax, say, accX, accY, pdx, pdy, ltx, lty;
    private long t0, ltt, lastSend;
    private boolean tap, multi, drag, longFired, scrolled;
    private int maxPtr;

    private final Runnable longPress = () -> {
        if (tap && !multi) {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (padMode) {
                btn("l", 1, false, 0, 0);
                drag = true;
            } else {
                btn("l", 1, true, sx, sy);
                drag = true;
            }
        }
    };

    private void btn(String b, int d, boolean abs, float x, float y) {
        if (abs) send("btn", "b", b, "d", d, "x", nx(x), "y", ny(y)); else send("btn", "b", b, "d", d);
    }
    private void click(String b, boolean abs, float x, float y) {
        btn(b, 1, abs, x, y);
        btn(b, 0, abs, x, y);
        // Click is an action — trigger modifier auto-release
        notifyAction();
    }

    private void move(float x, float y) {
        long n = SystemClock.uptimeMillis();
        if (n - lastSend < 8) return;
        lastSend = n;
        send("mv", "x", nx(x), "y", ny(y));
    }
    private void rel(float dx, float dy) {
        pdx += dx; pdy += dy;
        long n = SystemClock.uptimeMillis();
        if (n - lastSend < 8) return;
        lastSend = n;
        send("rm", "dx", pdx * GAIN / dst.width(), "dy", pdy * GAIN / dst.height());
        pdx = pdy = 0;
    }

    private float[] avg(MotionEvent e, int skip) {
        float x = 0, y = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) { if (i == skip) continue; x += e.getX(i); y += e.getY(i); n++; }
        return new float[]{x / n, y / n};
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (dst.isEmpty()) return true;

        // In direct touch mode, ignore touches that start outside the image
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !padMode && !insideImage(e.getX(), e.getY())) {
            return false;  // let the event pass through (e.g., to nav bar)
        }

        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        final float step = 24 * density;   // px of two-finger travel per wheel notch
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                tap = true; multi = false; drag = false; longFired = false; scrolled = false; maxPtr = 1;
                sx = lx = e.getX(); sy = ly = e.getY(); t0 = e.getEventTime(); pdx = pdy = 0;
                if (!padMode) { lastSend = 0; move(sx, sy); }
                ui.postDelayed(longPress, 450);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                ui.removeCallbacks(longPress);
                if (!multi) {
                    multi = true;
                    if (drag) {
                        btn("l", 0, !padMode, lx, ly);
                        drag = false;
                    }
                    accX = accY = 0;
                }
                maxPtr = Math.max(maxPtr, e.getPointerCount());
                float[] a = avg(e, -1); sax = a[0]; say = a[1];
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                float[] a = avg(e, e.getActionIndex()); sax = a[0]; say = a[1];
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (multi) {
                    if (e.getPointerCount() >= 2) {
                        float[] a = avg(e, -1);
                        accX += a[0] - sax; accY += a[1] - say; sax = a[0]; say = a[1];
                        int ny = (int) (accY / step), nxs = (int) (accX / step);
                        if (ny != 0 || nxs != 0) {
                            scrolled = true;
                            accY -= ny * step; accX -= nxs * step;
                            send("wh", "dy", ny * 120, "dx", -nxs * 120);
                        }
                    }
                    return true;
                }
                float x = e.getX(), y = e.getY();
                if (tap && Math.hypot(x - sx, y - sy) > slop) {
                    tap = false;
                    ui.removeCallbacks(longPress);
                    if (!longFired) {
                        if (!padMode) {
                            btn("l", 1, true, sx, sy);
                            drag = true;
                        }
                    }
                }
                if (!tap) {
                    if (padMode) {
                        rel(x - lx, y - ly);
                    } else {
                        move(x, y);
                    }
                }
                lx = x; ly = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                ui.removeCallbacks(longPress);
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (multi) {
                    if (up && !scrolled && maxPtr == 2 && e.getEventTime() - t0 < 350) click("r", !padMode, sx, sy);
                } else if (drag) {
                    if (!padMode) {
                        move(e.getX(), e.getY());
                    }
                    btn("l", 0, !padMode, e.getX(), e.getY());
                    drag = false;
                    notifyAction();
                } else if (tap && up) {
                    float cx = sx, cy = sy;
                    long now = e.getEventTime();
                    if (now - ltt < 400 && Math.hypot(cx - ltx, cy - lty) < 40 * density) { cx = ltx; cy = lty; }
                    ltx = cx; lty = cy; ltt = now;
                    if (!padMode && longFired) {
                        btn("l", 0, true, cx, cy);
                        click("r", true, cx, cy);
                    } else {
                        click("l", !padMode, cx, cy);
                    }
                }
                return true;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- keyboard
    private static String keyName(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "Enter";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "Delete";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_MOVE_HOME: return "Home";
            case KeyEvent.KEYCODE_MOVE_END: return "End";
            case KeyEvent.KEYCODE_PAGE_UP: return "PageUp";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "PageDown";
            case KeyEvent.KEYCODE_INSERT: return "Insert";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "CapsLock";
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT: return "Shift";
            case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT: return "Control";
            case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT: return "Alt";
            case KeyEvent.KEYCODE_META_LEFT: case KeyEvent.KEYCODE_META_RIGHT: return "Meta";
        }
        if (kc >= KeyEvent.KEYCODE_F1 && kc <= KeyEvent.KEYCODE_F12) return "F" + (kc - KeyEvent.KEYCODE_F1 + 1);
        if (kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z) return String.valueOf((char) ('a' + kc - KeyEvent.KEYCODE_A));
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) return String.valueOf((char) ('0' + kc - KeyEvent.KEYCODE_0));
        return null;
    }

    public boolean handleExternalKeyEvent(KeyEvent ev) {
        return handleKey(ev);
    }

    private boolean handleKey(KeyEvent ev) {
        int kc = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (ev.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        String n = keyName(kc);
        if (n != null) { key(n, down); return true; }
        int u = ev.getUnicodeChar();
        if (down && u != 0 && (u & KeyCharacterMap.COMBINING_ACCENT) == 0 && !ev.isCtrlPressed()) {
            text(new String(Character.toChars(u)));
            return true;
        }
        return false;   // Back, volume, etc. stay with Android
    }

    @Override public boolean onKeyDown(int kc, KeyEvent ev) { return handleKey(ev) || super.onKeyDown(kc, ev); }
    @Override public boolean onKeyUp(int kc, KeyEvent ev) { return handleKey(ev) || super.onKeyUp(kc, ev); }
    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo o) {
        o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        o.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private String composing = "";
            private void apply(String t) {
                int p = 0;
                while (p < composing.length() && p < t.length() && composing.charAt(p) == t.charAt(p)) p++;
                for (int i = p; i < composing.length(); i++) tap("Backspace");
                if (t.length() > p) text(t.substring(p));
                composing = t;
            }
            @Override public boolean commitText(CharSequence t, int pos) { apply(t.toString()); composing = ""; return true; }
            @Override public boolean setComposingText(CharSequence t, int pos) { apply(t.toString()); return true; }
            @Override public boolean finishComposingText() { composing = ""; return true; }
            @Override public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < before; i++) tap("Backspace");
                for (int i = 0; i < after; i++) tap("Delete");
                composing = composing.length() >= before ? composing.substring(0, composing.length() - before) : "";
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent ev) { composing = ""; handleKey(ev); return true; }
        };
    }
}
