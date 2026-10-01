"""
LapCast server (Windows laptop).  Streams the screen as JPEG over WebSocket and
injects mouse/keyboard input received from the tablet via SendInput.

    pip install mss pillow aiohttp qrcode
    python lapcast_server.py [--port 8765] [--fps 30] [--quality 95] [--monitor 1] [--pin 123456]

Run as Administrator if you need to control elevated windows / UAC prompts (UIPI).
"""
import argparse, asyncio, ctypes, io, json, secrets, socket, threading, time
from ctypes import wintypes as wt
from concurrent.futures import ThreadPoolExecutor

import mss
from PIL import Image
from aiohttp import web, WSMsgType

# ---- DPI awareness: must happen before any screen metric is read -------------
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    ctypes.windll.user32.SetProcessDPIAware()
user32 = ctypes.windll.user32

# ---- SendInput structs --------------------------------------------------------
ULONG_PTR = ctypes.c_size_t
class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]
class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]
class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]
class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]
class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wt.DWORD), ("u", _U)]

def _send(*inputs):
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))

def _mouse(flags, data=0):
    return INPUT(type=0, mi=MOUSEINPUT(0, 0, data & 0xFFFFFFFF, flags, 0, 0))

def _kbd(vk=0, scan=0, flags=0):
    return INPUT(type=1, ki=KEYBDINPUT(vk, scan, flags, 0, 0))

BTN = {"l": (0x0002, 0x0004), "r": (0x0008, 0x0010), "m": (0x0020, 0x0040)}
VK = {"Enter": 0x0D, "Backspace": 0x08, "Tab": 0x09, "Escape": 0x1B, "Space": 0x20,
      "Delete": 0x2E, "Insert": 0x2D, "Home": 0x24, "End": 0x23, "PageUp": 0x21,
      "PageDown": 0x22, "ArrowLeft": 0x25, "ArrowUp": 0x26, "ArrowRight": 0x27,
      "ArrowDown": 0x28, "Shift": 0x10, "Control": 0x11, "Alt": 0x12, "Meta": 0x5B,
      "CapsLock": 0x14, "ContextMenu": 0x5D}
VK.update({f"F{i}": 0x6F + i for i in range(1, 13)})
EXT = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5D}

held_keys, held_btns = set(), set()

def key(name, down):
    vk = VK.get(name)
    if vk is None and len(name) == 1 and name.isalnum():
        vk = ord(name.upper())
    if vk is None:
        return
    flags = (0x1 if vk in EXT else 0) | (0 if down else 0x2)
    _send(_kbd(vk, user32.MapVirtualKeyW(vk, 0), flags))
    (held_keys.add if down else held_keys.discard)(name)

def text(s):
    for ch in s:
        if ch == "\n": key("Enter", True); key("Enter", False); continue
        if ch == "\t": key("Tab", True); key("Tab", False); continue
        b = ch.encode("utf-16-le")
        for i in range(0, len(b), 2):
            u = b[i] | (b[i + 1] << 8)
            _send(_kbd(0, u, 0x4), _kbd(0, u, 0x4 | 0x2))

def button(b, down):
    if b not in BTN:
        return
    _send(_mouse(BTN[b][0 if down else 1]))
    (held_btns.add if down else held_btns.discard)(b)

def release_all():
    for k in list(held_keys): key(k, False)
    for b in list(held_btns): button(b, False)

# ---- Monitor / cursor ---------------------------------------------------------
MON = {}
def cursor_pos():
    p = wt.POINT(); user32.GetCursorPos(ctypes.byref(p)); return p.x, p.y

def move_abs(nx, ny):
    x = MON["left"] + min(max(nx, 0), 1) * (MON["width"] - 1)
    y = MON["top"] + min(max(ny, 0), 1) * (MON["height"] - 1)
    user32.SetCursorPos(int(x), int(y))

def move_rel(dx, dy):
    x, y = cursor_pos()
    x = min(max(x + dx * MON["width"], MON["left"]), MON["left"] + MON["width"] - 1)
    y = min(max(y + dy * MON["height"], MON["top"]), MON["top"] + MON["height"] - 1)
    user32.SetCursorPos(int(x), int(y))

def cursor_norm():
    x, y = cursor_pos()
    return (round((x - MON["left"]) / MON["width"], 4), round((y - MON["top"]) / MON["height"], 4))

# ---- Capture ------------------------------------------------------------------
_tl = threading.local()
def grab(mon_idx, quality, last):
    """Capture the screen and return high-quality JPEG bytes."""
    sct = getattr(_tl, "sct", None)
    if sct is None:
        sct = _tl.sct = mss.mss()
    shot = sct.grab(sct.monitors[mon_idx])
    raw = shot.bgra
    if raw == last[0]:
        return None  # screen unchanged -> send nothing
    last[0] = raw
    img = Image.frombytes("RGB", shot.size, raw, "raw", "BGRX")
    # No down-scaling: send the native resolution for maximum sharpness
    buf = io.BytesIO()
    # Use high-quality JPEG (quality 95) which is visually lossless but much faster
    # and smaller than PNG for screen content.  The key is quality=95 + no resize.
    img.save(buf, "JPEG", quality=quality, subsampling=0, optimize=False)
    return buf.getvalue()

async def stream(ws, a):
    loop, ex, last, period, lastcur = asyncio.get_running_loop(), ThreadPoolExecutor(1), [None], 1 / a.fps, None
    try:
        while not ws.closed:
            t = time.perf_counter()
            jpg = await loop.run_in_executor(ex, grab, a.monitor, a.quality, last)
            if jpg:
                await ws.send_bytes(jpg)  # awaits drain -> natural backpressure
            cur = cursor_norm()
            if cur != lastcur:
                lastcur = cur
                await ws.send_json({"t": "cur", "x": cur[0], "y": cur[1]})
            await asyncio.sleep(max(0, period - (time.perf_counter() - t)))
    except (asyncio.CancelledError, ConnectionResetError):
        pass
    finally:
        ex.shutdown(wait=False)

# ---- Input dispatch -----------------------------------------------------------
def handle(m):
    t = m.get("t")
    if t == "mv":   move_abs(m["x"], m["y"])
    elif t == "rm": move_rel(m["dx"], m["dy"])
    elif t == "btn":
        if "x" in m: move_abs(m["x"], m["y"])
        button(m["b"], bool(m["d"]))
    elif t == "wh":
        if m.get("dy"): _send(_mouse(0x0800, int(m["dy"])))
        if m.get("dx"): _send(_mouse(0x1000, int(m["dx"])))
    elif t == "key": key(m["k"], bool(m["d"]))
    elif t == "txt": text(m["s"])

# ---- QR code generation -------------------------------------------------------
_qr_png_bytes = None   # cached PNG bytes

def generate_qr(data_dict):
    """Generate a QR code PNG. Returns bytes or None if qrcode is not installed."""
    global _qr_png_bytes
    try:
        import qrcode
    except ImportError:
        return None
    payload = json.dumps(data_dict, separators=(",", ":"))
    qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M,
                        box_size=10, border=4)
    qr.add_data(payload)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    buf = io.BytesIO()
    img.save(buf, "PNG")
    _qr_png_bytes = buf.getvalue()
    return _qr_png_bytes

def print_qr_terminal(data_dict):
    """Print QR to terminal as ASCII art."""
    try:
        import qrcode
    except ImportError:
        print("   (install 'qrcode' package for QR code: pip install qrcode)")
        return
    payload = json.dumps(data_dict, separators=(",", ":"))
    qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M,
                        box_size=1, border=2)
    qr.add_data(payload)
    qr.make(fit=True)
    # Print using Unicode half-blocks for compact output
    matrix = qr.get_matrix()
    rows = len(matrix)
    for r in range(0, rows, 2):
        line = "   "
        for c in range(len(matrix[r])):
            top = matrix[r][c]
            bot = matrix[r + 1][c] if r + 1 < rows else False
            if top and bot:
                line += "█"
            elif top:
                line += "▀"
            elif bot:
                line += "▄"
            else:
                line += " "
        print(line)

async def qr_handler(req):
    """Serve QR code PNG via HTTP."""
    if _qr_png_bytes:
        return web.Response(body=_qr_png_bytes, content_type="image/png")
    return web.Response(text="QR code not available (pip install qrcode)", status=503)

# ---- Server -------------------------------------------------------------------
async def ws_handler(req):
    a = req.app["args"]
    # Allow very large messages (up to 16 MB) for high-res screenshots
    ws = web.WebSocketResponse(heartbeat=10, max_msg_size=16 << 20)
    await ws.prepare(req)
    try:
        m = await asyncio.wait_for(ws.receive(), 10)
        ok = m.type == WSMsgType.TEXT and secrets.compare_digest(str(json.loads(m.data).get("pin", "")), a.pin)
    except Exception:
        ok = False
    if not ok:
        await asyncio.sleep(1.5)  # slows PIN brute-forcing
        await ws.close(code=4001, message=b"bad pin")
        return ws
    print(f"[+] client connected: {req.remote}")
    await ws.send_json({"t": "ok", "w": MON["width"], "h": MON["height"]})
    task = asyncio.create_task(stream(ws, a))
    try:
        async for m in ws:
            if m.type == WSMsgType.TEXT:
                try: handle(json.loads(m.data))
                except Exception as e: print("input error:", e)
    finally:
        task.cancel(); release_all()
        print(f"[-] client disconnected: {req.remote}")
    return ws

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--quality", type=int, default=95, help="JPEG quality 1-100 (95 = visually lossless)")
    p.add_argument("--monitor", type=int, default=1, help="mss monitor index (1 = primary)")
    p.add_argument("--pin", default=f"{secrets.randbelow(10**6):06d}")
    a = p.parse_args()
    with mss.mss() as s:
        MON.update(s.monitors[a.monitor])
    ips = sorted(set(socket.gethostbyname_ex(socket.gethostname())[2]))
    print(f"\n LapCast server   PIN: {a.pin}   monitor {a.monitor}: {MON['width']}x{MON['height']}")
    print(f"   Quality: JPEG q={a.quality} (no downscaling, native resolution)")
    for ip in ips:
        print(f"   connect tablet to  {ip}:{a.port}")
    print("   (Windows hotspot hosted by this laptop is usually 192.168.137.1)")

    # Generate QR code for easy tablet connection
    # Pick the first hotspot-like IP, fall back to the first available
    hotspot_ip = next((ip for ip in ips if ip.startswith("192.168.137.")), ips[0] if ips else "localhost")
    qr_data = {"h": f"{hotspot_ip}:{a.port}", "p": a.pin}
    generate_qr(qr_data)
    print(f"\n   Scan this QR code from the tablet to connect:")
    print_qr_terminal(qr_data)
    print(f"   Or open: http://{hotspot_ip}:{a.port}/qr  on any browser to view QR\n")

    app = web.Application()
    app["args"] = a
    app.add_routes([
        web.get("/ws", ws_handler),
        web.get("/qr", qr_handler),
        web.get("/", lambda r: web.Response(text="LapCast OK")),
    ])
    web.run_app(app, host="0.0.0.0", port=a.port, print=None)

if __name__ == "__main__":
    main()
