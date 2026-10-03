#!/usr/bin/env python3
"""Notifier control panel: pick an Android phone, open scrcpy, watch notifications, forward to Telegram.

MVP pipeline: poll `dumpsys notification` over adb, diff against the previous snapshot,
forward new/changed notifications to a Telegram bot. Standard library only.

    python3 panel/server.py   ->   http://localhost:8787
"""
import html
import json
import os
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
SETTINGS_FILE = DATA / "settings.json"
NOTIFS_FILE = DATA / "notifications.json"
PORT = int(os.environ.get("PANEL_PORT", "8787"))

DEFAULT_SETTINGS = {
    "device_serial": "",  # empty = first connected device; otherwise a USB serial or "192.168.1.20:5555"
    "bot_token": "",
    "chat_id": "",
    "forwarding_enabled": False,
    "poll_interval": 2,
    "ignore_packages": "android\ncom.android.systemui\ncom.google.android.gms",
    "skip_ongoing": True,
    "skip_group_summary": True,
    "show_package": True,
    "silent_messages": False,
    "scrcpy_max_size": 1280,
    "scrcpy_audio": False,
    "scrcpy_stay_awake": True,
    "scrcpy_on_top": False,
    "scrcpy_screen_off": True,
}

FLAG_ONGOING_EVENT = 0x2
FLAG_FOREGROUND_SERVICE = 0x40
FLAG_GROUP_SUMMARY = 0x200

lock = threading.Lock()
settings = dict(DEFAULT_SETTINGS)
notifications = deque(maxlen=300)
poller_state = {"last_poll": None, "error": None, "baseline_done": False}


# ---------- persistence ----------

def load():
    DATA.mkdir(exist_ok=True)
    if SETTINGS_FILE.exists():
        settings.update(json.loads(SETTINGS_FILE.read_text()))
    if NOTIFS_FILE.exists():
        notifications.extend(json.loads(NOTIFS_FILE.read_text()))


def save_settings():
    SETTINGS_FILE.write_text(json.dumps(settings, indent=2))
    os.chmod(SETTINGS_FILE, 0o600)  # holds the bot token


def save_notifications():
    NOTIFS_FILE.write_text(json.dumps(list(notifications)))


# ---------- shell helpers ----------

def run(cmd, timeout=20):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except FileNotFoundError as e:
        return 127, str(e)


def list_devices():
    code, out = run(["adb", "devices", "-l"], timeout=8)
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            model = next((p.split(":", 1)[1] for p in parts if p.startswith("model:")), "")
            devices.append({"serial": parts[0], "state": parts[1], "model": model.replace("_", " ")})
    return devices


def device_serial():
    """Selected device, or the first one adb reports as ready."""
    if settings["device_serial"].strip():
        return settings["device_serial"].strip()
    ready = [d["serial"] for d in list_devices() if d["state"] == "device"]
    return ready[0] if ready else None


def adb(*args, timeout=15):
    serial = device_serial()
    if not serial:
        return 1, "No device connected"
    return run(["adb", "-s", serial, *args], timeout=timeout)


def adb_connected():
    serial = device_serial()
    if not serial:
        return False
    code, out = run(["adb", "devices"], timeout=5)
    if f"{serial}\tdevice" in out:
        return True
    if ":" in serial:  # network device; USB serials can't be (re)connected
        run(["adb", "connect", serial], timeout=5)
    code, out = run(["adb", "devices"], timeout=5)
    return f"{serial}\tdevice" in out


def scrcpy_running():
    return run(["pgrep", "-x", "scrcpy"], timeout=5)[0] == 0


def open_scrcpy():
    if scrcpy_running():
        return "scrcpy already open"
    serial = device_serial()
    if not serial or not adb_connected():
        return "No device connected"
    cmd = ["scrcpy", "-s", serial, "--window-title", "Notifier phone"]
    if settings["scrcpy_max_size"]:
        cmd += ["--max-size", str(settings["scrcpy_max_size"])]
    if not settings["scrcpy_audio"]:
        cmd.append("--no-audio")
    if settings["scrcpy_stay_awake"]:
        cmd.append("--stay-awake")
    if settings["scrcpy_on_top"]:
        cmd.append("--always-on-top")
    if settings["scrcpy_screen_off"]:
        cmd.append("--turn-screen-off")  # phone display stays dark while mirroring
    subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return "scrcpy opened"


TWEAKS = {
    "animations_off": ["shell", "settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0"],
    "animations_on": ["shell", "settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1"],
    "dark_mode": ["shell", "cmd uimode night yes"],
    "light_mode": ["shell", "cmd uimode night no"],
    "notif_history": ["shell", "settings put secure notification_history_enabled 1"],
    "test_notification": ["shell", "cmd notification post -S bigtext -t 'Notifier test' notifier_test \"Hello from the panel at $(date +%H:%M:%S)\""],
}


# ---------- notifications ----------

EXTRA_RE = re.compile(r"android\.(title|text|bigText|subText)=\w+ \((.*)\)\s*$")


def parse_dumpsys(out):
    """Parse active notifications from `dumpsys notification --noredact`."""
    records, cur = {}, None
    for line in out.splitlines():
        if "NotificationRecord(" in line and "pkg=" in line:
            m_pkg = re.search(r"pkg=(\S+)", line)
            m_key = re.search(r"key=(\S+?): Notification", line)
            m_flags = re.search(r"flags=0x([0-9a-fA-F]+)", line)
            if not (m_pkg and m_key):
                cur = None
                continue
            cur = {"pkg": m_pkg.group(1), "key": m_key.group(1),
                   "flags": int(m_flags.group(1), 16) if m_flags else 0}
            records.setdefault(cur["key"], cur)
            cur = records[cur["key"]]
        elif cur is not None:
            m = EXTRA_RE.search(line)
            if m and m.group(1) not in cur:
                cur[m.group(1)] = m.group(2)
    return records


def ignored_packages():
    return {p.strip() for p in settings["ignore_packages"].splitlines() if p.strip()}


def skip_reason(rec):
    if rec["pkg"] in ignored_packages():
        return "ignored app"
    if settings["skip_ongoing"] and rec["flags"] & (FLAG_ONGOING_EVENT | FLAG_FOREGROUND_SERVICE):
        return "ongoing"
    if settings["skip_group_summary"] and rec["flags"] & FLAG_GROUP_SUMMARY:
        return "group summary"
    if not (rec.get("title") or rec.get("text") or rec.get("bigText")):
        return "empty"
    return None


def telegram(method, params):
    token = settings["bot_token"].strip()
    if not token:
        raise RuntimeError("bot token is empty")
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/{method}", data=data)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        body = json.loads(e.read() or b"{}")
        raise RuntimeError(body.get("description", str(e))) from None


def format_message(n):
    head = f"<b>{html.escape(n['title'] or n['pkg'])}</b>"
    if settings["show_package"]:
        head += f"\n<i>{html.escape(n['pkg'])}</i>"
    body = html.escape(n["text"] or "")
    return f"{head}\n{body}".strip()


def forward(n):
    if not settings["forwarding_enabled"]:
        return "not forwarded (forwarding off)"
    if not settings["chat_id"].strip():
        return "not forwarded (no chat id)"
    try:
        telegram("sendMessage", {
            "chat_id": settings["chat_id"].strip(),
            "text": format_message(n),
            "parse_mode": "HTML",
            "disable_notification": "true" if settings["silent_messages"] else "false",
        })
        return "sent"
    except Exception as e:  # noqa: BLE001
        return f"failed: {e}"


def poll_loop():
    seen = {}  # key -> content signature
    while True:
        time.sleep(max(1, float(settings["poll_interval"])))
        code, out = adb("shell", "dumpsys", "notification", "--noredact", timeout=20)
        poller_state["last_poll"] = time.time()
        if code != 0 or "NotificationRecord" not in out and "Notification List" not in out:
            poller_state["error"] = "Device not reachable" if code else None
            continue
        poller_state["error"] = None
        records = parse_dumpsys(out)
        first = not poller_state["baseline_done"]
        for key, rec in records.items():
            text = rec.get("bigText") or rec.get("text") or ""
            sig = (rec.get("title"), text)
            if seen.get(key) == sig:
                continue
            seen[key] = sig
            if first:
                continue  # don't flood Telegram with what was already on screen
            n = {"ts": time.time(), "pkg": rec["pkg"], "key": key,
                 "title": rec.get("title") or "", "text": text, "sub": rec.get("subText") or ""}
            reason = skip_reason(rec)
            n["status"] = f"skipped ({reason})" if reason else forward(n)
            with lock:
                notifications.appendleft(n)
                save_notifications()
        for key in list(seen):
            if key not in records:
                del seen[key]
        poller_state["baseline_done"] = True


# ---------- HTTP ----------

def select_device(serial):
    settings["device_serial"] = serial
    save_settings()
    poller_state["baseline_done"] = False  # don't forward what's already on the new device


def status():
    connected = adb_connected()
    model = adb("shell", "getprop", "ro.product.model", timeout=5)[1] if connected else ""
    return {
        "model": model,
        "adb": connected,
        "serial": device_serial(),
        "scrcpy": scrcpy_running(),
        "poller": {"error": poller_state["error"], "last_poll": poller_state["last_poll"]},
        "forwarding": settings["forwarding_enabled"] and bool(settings["bot_token"]) and bool(settings["chat_id"]),
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, obj, code=200, ctype="application/json"):
        body = obj if isinstance(obj, bytes) else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/":
            self.reply((ROOT / "index.html").read_bytes(), ctype="text/html; charset=utf-8")
        elif path == "/api/status":
            self.reply(status())
        elif path == "/api/notifications":
            self.reply(list(notifications))
        elif path == "/api/settings":
            self.reply(settings)
        elif path == "/api/devices":
            self.reply({"devices": list_devices(), "selected": settings["device_serial"]})
        else:
            self.reply({"error": "not found"}, 404)

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        data = self.body()
        if path == "/api/settings":
            for k, v in data.items():
                if k in DEFAULT_SETTINGS:
                    settings[k] = type(DEFAULT_SETTINGS[k])(v)
            save_settings()
            self.reply({"ok": True, "message": "Settings saved"})
        elif path == "/api/action":
            name = data.get("name")
            if name == "scrcpy":
                self.reply({"ok": True, "message": open_scrcpy()})
            else:
                self.reply({"ok": False, "message": "unknown action"}, 400)
        elif path == "/api/tweak":
            spec = TWEAKS.get(data.get("name"))
            if not spec:
                return self.reply({"ok": False, "message": "unknown tweak"}, 400)
            code, out = adb(*spec)
            self.reply({"ok": code == 0, "message": out or ("Done" if code == 0 else "Failed")})
        elif path == "/api/telegram/test":
            try:
                me = telegram("getMe", {})["result"]
                msg = f"Bot @{me['username']} is reachable."
                if settings["chat_id"].strip():
                    telegram("sendMessage", {"chat_id": settings["chat_id"].strip(),
                                             "text": "✅ Notifier panel test message"})
                    msg += " Test message sent."
                self.reply({"ok": True, "message": msg})
            except Exception as e:  # noqa: BLE001
                self.reply({"ok": False, "message": str(e)})
        elif path == "/api/telegram/detect_chat":
            try:
                updates = telegram("getUpdates", {})["result"]
                chats = [u[k]["chat"] for u in updates for k in ("message", "channel_post") if k in u]
                if not chats:
                    return self.reply({"ok": False, "message": "No messages yet - send /start to your bot, then retry"})
                chat = chats[-1]
                settings["chat_id"] = str(chat["id"])
                save_settings()
                name = chat.get("title") or chat.get("username") or chat.get("first_name")
                self.reply({"ok": True, "message": f"Chat detected: {name}", "chat_id": settings["chat_id"]})
            except Exception as e:  # noqa: BLE001
                self.reply({"ok": False, "message": str(e)})
        elif path == "/api/device/pair":
            # Android 11+ Wireless debugging: "Pair device with pairing code" shows ip:port + 6-digit code.
            code, out = run(["adb", "pair", data.get("address", "").strip(), data.get("code", "").strip()], timeout=20)
            self.reply({"ok": code == 0 and "Successfully paired" in out, "message": out or "No output"})
        elif path == "/api/device/connect":
            # The *connect* port is the one on the Wireless debugging main screen (differs from the pairing port).
            address = data.get("address", "").strip()
            code, out = run(["adb", "connect", address], timeout=15)
            ok = code == 0 and ("connected to" in out) and "failed" not in out
            if ok:
                select_device(address)
            self.reply({"ok": ok, "message": out or "No output"})
        elif path == "/api/device/wifi":
            # Android 10 and older: switch a USB-connected phone's adbd to TCP, then connect over Wi-Fi.
            serial = data.get("serial", "").strip()
            code, out = run(["adb", "-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"], timeout=10)
            m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
            if not m:
                return self.reply({"ok": False, "message": "Phone has no Wi-Fi IP - is Wi-Fi on?"})
            run(["adb", "-s", serial, "tcpip", "5555"], timeout=10)
            time.sleep(3)
            address = f"{m.group(1)}:5555"
            code, out = run(["adb", "connect", address], timeout=15)
            ok = "connected to" in out and "failed" not in out
            if ok:
                select_device(address)
            self.reply({"ok": ok, "message": (f"Now using {address} - you can unplug the cable" if ok else out)})
        elif path == "/api/device/select":
            select_device(data.get("serial", "").strip())
            self.reply({"ok": True, "message": "Using " + (settings["device_serial"] or "first connected device")})
        elif path == "/api/notifications/clear":
            with lock:
                notifications.clear()
                save_notifications()
            self.reply({"ok": True, "message": "Cleared"})
        else:
            self.reply({"error": "not found"}, 404)


if __name__ == "__main__":
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError:
        raise SystemExit(f"Port {PORT} is busy - the panel is probably already running at http://localhost:{PORT}\n"
                         f"Stop it with: pkill -f panel/server.py   (or use PANEL_PORT=8788)")
    load()
    threading.Thread(target=poll_loop, daemon=True).start()
    print(f"Notifier panel: http://localhost:{PORT}")
    server.serve_forever()
