package com.lapcast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "LapCast";
    private static final int QR_REQUEST = 200;
    private RemoteView rv;
    private SharedPreferences prefs;
    private boolean serviceRunning = false;

    // Scroll + zoom controls
    private LinearLayout scrollPanel;
    private TextView zoomLabel;

    // Sticky modifier buttons — tracked so they can be auto-released
    private final List<ModButton> modButtons = new ArrayList<>();

    // For QR auto-fill
    private EditText connectHost, connectPin;

    /** Tracks a modifier toolbar button. */
    private static class ModButton {
        final Button button;
        final String keyName;
        boolean active;
        ModButton(Button b, String k) { button = b; keyName = k; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            Log.e(TAG, "Uncaught error", ex);
            runOnUiThread(() -> showErrorFallback(ex));
        });

        try {
            prefs = getSharedPreferences("lapcast", MODE_PRIVATE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            // Request notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
                }
            }

            showConnect(null);
        } catch (Throwable t) {
            Log.e(TAG, "Error in onCreate", t);
            showErrorFallback(t);
        }
    }

    private void showErrorFallback(Throwable t) {
        try {
            ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(0xFF111111);
            TextView tv = new TextView(this);
            tv.setTextColor(0xFFFF6B6B);
            tv.setTextSize(14);
            tv.setPadding(dp(20), dp(20), dp(20), dp(20));
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            tv.setText("LapCast Error:\n\n" + sw.toString());
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) {
            // Keep navigation buttons displayed at all times
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars());
                    controller.show(WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        }
    }

    @Override public void onBackPressed() {
        if (rv != null) Toast.makeText(this, "Use ✕ in the ☰ bar to disconnect", Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (rv != null) rv.disconnect();
        stopConnectionService();
        super.onDestroy();
    }

    // Intercept hardware key events and route them to RemoteView so Android system shortcuts
    // (such as Win + R, Alt + Tab, etc.) do NOT cause the app to exit or get eaten by Android OS.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (rv != null && rv.isConnected()) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_BACK) {
                return super.dispatchKeyEvent(event);
            }
            if (rv.handleExternalKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams makeLp(int w, int h, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (topMarginDp > 0) lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value); e.setInputType(type);
        e.setTextColor(Color.WHITE); e.setHintTextColor(0xFF777777); e.setSingleLine(true);
        return e;
    }

    private void showConnect(String msg) {
        try {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
            l.setBackgroundColor(0xFF111111);
            TextView title = new TextView(this);
            title.setText("LapCast"); title.setTextSize(30); title.setTextColor(Color.WHITE);
            connectHost = field("Laptop IP:port  (e.g. 192.168.137.1:8765)", prefs.getString("host", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            connectPin = field("PIN shown by the laptop server", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

            // Buttons row: Connect + Scan QR
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER);

            Button go = new Button(this);
            go.setText("Connect");
            go.setOnClickListener(v -> {
                String h = connectHost.getText().toString().trim();
                if (h.isEmpty()) return;
                if (!h.contains(":")) h += ":8765";
                prefs.edit().putString("host", h).apply();
                showRemote(h, connectPin.getText().toString().trim());
            });

            Button scanQr = new Button(this);
            scanQr.setText("📷 Scan QR");
            scanQr.setOnClickListener(v -> startQrScan());

            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            goLp.setMargins(dp(4), 0, dp(4), 0);
            btnRow.addView(go, goLp);

            LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            scanLp.setMargins(dp(4), 0, dp(4), 0);
            btnRow.addView(scanQr, scanLp);

            TextView status = new TextView(this);
            status.setText(msg == null ? "" : msg); status.setTextColor(0xFFFF8A80);

            l.addView(title, makeLp(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT, 0));
            l.addView(connectHost, makeLp(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT, 12));
            l.addView(connectPin, makeLp(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT, 12));
            l.addView(btnRow, makeLp(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT, 12));
            l.addView(status, makeLp(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT, 12));
            setContentView(l);
        } catch (Throwable t) {
            Log.e(TAG, "Error in showConnect", t);
            showErrorFallback(t);
        }
    }

    // ---- QR scanning ----
    private void startQrScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, QR_REQUEST);
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner() {
        try {
            IntentIntegrator integrator = new IntentIntegrator(this);
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("Scan the QR code shown on your laptop terminal");
            integrator.setOrientationLocked(false);
            integrator.setBeepEnabled(false);
            integrator.initiateScan();
        } catch (Exception e) {
            Log.e(TAG, "QR scan error", e);
            Toast.makeText(this, "QR Scanner error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == QR_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner();
        } else if (requestCode == QR_REQUEST) {
            Toast.makeText(this, "Camera permission is needed to scan QR codes", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            try {
                JSONObject qr = new JSONObject(result.getContents());
                String host = qr.optString("h", "");
                String pin = qr.optString("p", "");
                if (!host.isEmpty()) {
                    if (connectHost != null) connectHost.setText(host);
                    if (connectPin != null) connectPin.setText(pin);
                    // Auto-connect
                    prefs.edit().putString("host", host).apply();
                    showRemote(host, pin);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ---- Button helpers ----
    private Button btn(String label, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinimumWidth(0); b.setMinWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        if (c != null) b.setOnClickListener(c);
        return b;
    }

    /**
     * Sticky modifier button: press to hold the key, auto-releases after next non-modifier action.
     * Pressing another modifier while one is active keeps both held (for Ctrl+Shift etc).
     */
    private Button mod(String label, String keyName) {
        Button b = btn(label, null);
        ModButton mb = new ModButton(b, keyName);
        modButtons.add(mb);
        b.setOnClickListener(v -> {
            mb.active = !mb.active;
            b.setBackgroundColor(mb.active ? 0xFF3D7BFF : Color.TRANSPARENT);
            rv.key(keyName, mb.active);
        });
        return b;
    }

    /** Release all active sticky modifiers and reset their button states. */
    private void releaseAllModifiers() {
        for (ModButton mb : modButtons) {
            if (mb.active) {
                mb.active = false;
                mb.button.setBackgroundColor(Color.TRANSPARENT);
                rv.key(mb.keyName, false);
            }
        }
    }

    private void showRemote(String hostPort, String pin) {
        modButtons.clear();
        rv = new RemoteView(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(rv, new FrameLayout.LayoutParams(-1, -1));

        // ---- Top toolbar (hamburger menu content) ----
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xDD202020);
        bar.setVisibility(View.GONE);
        Button mode = btn("Touch", null);
        mode.setOnClickListener(v -> { rv.padMode = !rv.padMode; mode.setText(rv.padMode ? "Pad" : "Touch"); });
        bar.addView(mode);
        bar.addView(btn("⌨", v -> rv.toggleKeyboard()));
        bar.addView(mod("Ctrl", "Control")); bar.addView(mod("Alt", "Alt"));
        bar.addView(mod("Shift", "Shift")); bar.addView(mod("Win", "Meta"));
        bar.addView(btn("Esc", v -> { rv.tap("Escape"); releaseAllModifiers(); }));
        bar.addView(btn("Tab", v -> { rv.tap("Tab"); releaseAllModifiers(); }));
        bar.addView(btn("✕", v -> disconnect(null)));
        root.addView(bar, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        Button menu = btn("☰", v -> bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        menu.setBackgroundColor(0x88000000);
        root.addView(menu, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        // ---- Zoom + scroll controls (bottom-right) ----
        scrollPanel = new LinearLayout(this);
        scrollPanel.setOrientation(LinearLayout.VERTICAL);
        scrollPanel.setBackgroundColor(0x88000000);
        scrollPanel.setPadding(dp(4), dp(4), dp(4), dp(4));
        scrollPanel.setGravity(Gravity.CENTER_HORIZONTAL);

        // Zoom buttons
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);

        Button zoomOutBtn = btn("−", v -> rv.zoomOut());
        zoomOutBtn.setTextSize(18);
        zoomRow.addView(zoomOutBtn);

        zoomLabel = new TextView(this);
        zoomLabel.setText("100%");
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(12);
        zoomLabel.setPadding(dp(4), 0, dp(4), 0);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomLabel.setOnClickListener(v -> rv.zoomReset());
        zoomRow.addView(zoomLabel);

        Button zoomInBtn = btn("+", v -> rv.zoomIn());
        zoomInBtn.setTextSize(18);
        zoomRow.addView(zoomInBtn);

        scrollPanel.addView(zoomRow);

        // Scroll buttons (shown only when zoomed or keyboard visible)
        LinearLayout scrollGrid = new LinearLayout(this);
        scrollGrid.setOrientation(LinearLayout.VERTICAL);
        scrollGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollGrid.setTag("scrollGrid");

        float scrollStep = 0.15f;

        // Up arrow
        LinearLayout upRow = new LinearLayout(this);
        upRow.setGravity(Gravity.CENTER);
        Button upBtn = btn("▲", v -> rv.scrollBy(0, -scrollStep));
        upBtn.setTextSize(14);
        upRow.addView(upBtn);
        scrollGrid.addView(upRow);

        // Left, reset, right
        LinearLayout midRow = new LinearLayout(this);
        midRow.setGravity(Gravity.CENTER);
        Button leftBtn = btn("◀", v -> rv.scrollBy(-scrollStep, 0));
        leftBtn.setTextSize(14);
        midRow.addView(leftBtn);
        Button centerBtn = btn("●", v -> rv.zoomReset());
        centerBtn.setTextSize(10);
        midRow.addView(centerBtn);
        Button rightBtn = btn("▶", v -> rv.scrollBy(scrollStep, 0));
        rightBtn.setTextSize(14);
        midRow.addView(rightBtn);
        scrollGrid.addView(midRow);

        // Down arrow
        LinearLayout downRow = new LinearLayout(this);
        downRow.setGravity(Gravity.CENTER);
        Button downBtn = btn("▼", v -> rv.scrollBy(0, scrollStep));
        downBtn.setTextSize(14);
        downRow.addView(downBtn);
        scrollGrid.addView(downRow);

        scrollPanel.addView(scrollGrid);

        // Initially hide scroll arrows
        scrollGrid.setVisibility(View.GONE);

        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        scrollLp.setMargins(0, 0, dp(8), dp(8));
        root.addView(scrollPanel, scrollLp);

        // Listen for zoom changes
        rv.setZoomListener(percent -> {
            runOnUiThread(() -> {
                if (zoomLabel != null) zoomLabel.setText(percent + "%");
                if (scrollPanel != null) {
                    View sg = scrollPanel.findViewWithTag("scrollGrid");
                    if (sg != null) {
                        sg.setVisibility(rv.needsScrollBar() ? View.VISIBLE : View.GONE);
                    }
                }
            });
        });

        // Listen for non-modifier actions to auto-release sticky modifiers
        rv.setActionListener(() -> releaseAllModifiers());

        // ---- Keyboard detection using layout listener ----
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (rv == null) return;
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screenHeight = root.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            boolean kbShowing = keypadHeight > screenHeight * 0.15;
            rv.setKeyboardShowing(kbShowing);
            if (scrollPanel != null) {
                View sg = scrollPanel.findViewWithTag("scrollGrid");
                if (sg != null) {
                    sg.setVisibility(rv.needsScrollBar() ? View.VISIBLE : View.GONE);
                }
            }
        });

        setContentView(root);

        // Connect, then start foreground service
        rv.connect(hostPort, pin, (msg, fatal) -> {
            runOnUiThread(() -> { if (fatal) disconnect(msg); });
        });

        startConnectionService();
    }

    // ---- Foreground service lifecycle ----
    private void startConnectionService() {
        try {
            Intent svc = new Intent(this, ConnectionService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            serviceRunning = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not start foreground service: " + e.getMessage(), e);
            Toast.makeText(this, "Background mode unavailable — stay in the app", Toast.LENGTH_LONG).show();
        }
    }

    private void stopConnectionService() {
        if (serviceRunning) {
            try {
                stopService(new Intent(this, ConnectionService.class));
            } catch (Exception e) {
                Log.w(TAG, "Could not stop service: " + e.getMessage());
            }
            serviceRunning = false;
        }
    }

    private void disconnect(String msg) {
        if (rv != null) { rv.disconnect(); rv = null; }
        modButtons.clear();
        stopConnectionService();
        showConnect(msg);
    }
}
