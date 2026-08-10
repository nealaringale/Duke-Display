import asyncio
import threading
import tkinter as tk
from tkinter import ttk
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8f8a0001-5d3e-4d0a-9c7b-000000000001"
DATA_UUID = "8f8a0002-5d3e-4d0a-9c7b-000000000002"

ORANGE = "#ff6500"
BG = "#050505"
CARD = "#111111"
TEXT = "#f7f7f7"
MUTED = "#919191"
GREEN = "#5cd274"
RED = "#ff5c5c"
AMBER = "#ffb000"


def parse_payload(raw: str):
    data = {"nav": {}, "music": {}, "msg": {}, "time": "--:--"}
    for line in raw.splitlines():
        parts = line.split("|")
        if not parts:
            continue
        if parts[0] == "TIME" and len(parts) >= 2:
            data["time"] = parts[1]
        elif parts[0] == "NAV" and len(parts) >= 5:
            try:
                distance = int(parts[2])
            except ValueError:
                distance = -1
            data["nav"] = {"direction": parts[1], "distance": distance, "instruction": parts[3], "road": parts[4]}
        elif parts[0] == "MUSIC" and len(parts) >= 4:
            data["music"] = {"title": parts[1], "artist": parts[2], "state": parts[3]}
        elif parts[0] == "MSG" and len(parts) >= 3:
            data["msg"] = {"app": parts[1], "text": parts[2]}
    return data


def direction_display(direction: str):
    arrows = {
        "RIGHT": "↗  RIGHT", "LEFT": "↖  LEFT",
        "SLIGHT_RIGHT": "↗  SLIGHT RIGHT", "SLIGHT_LEFT": "↖  SLIGHT LEFT",
        "SHARP_RIGHT": "↗  SHARP RIGHT", "SHARP_LEFT": "↖  SHARP LEFT",
        "UTURN": "↶  U-TURN", "KEEP_RIGHT": "↗  KEEP RIGHT", "KEEP_LEFT": "↖  KEEP LEFT",
        "ARRIVE": "●  ARRIVE", "ROUNDABOUT": "↻  ROUNDABOUT",
        "NORTH": "↑  HEAD NORTH", "SOUTH": "↓  HEAD SOUTH",
        "EAST": "→  HEAD EAST", "WEST": "←  HEAD WEST",
    }
    return arrows.get(direction, "↑  STRAIGHT")


class DukeDashPC:
    def __init__(self, root):
        self.root = root
        self.root.title("Duke Dash — PC Display Prototype")
        self.root.geometry("780x720")
        self.root.minsize(650, 620)
        self.root.configure(bg=BG)
        self.connected = False
        self.stop_event = threading.Event()
        self.client = None
        self.loop = None

        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TButton", background="#161616", foreground=TEXT, bordercolor="#303030", padding=10, font=("Segoe UI", 10, "bold"))
        style.map("TButton", background=[("active", "#252525")])

        self.build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.start_ble_thread()

    def build_ui(self):
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=24, pady=20)

        header = tk.Frame(outer, bg=BG)
        header.pack(fill="x")
        tk.Label(header, text="DD", bg=ORANGE, fg="white", font=("Segoe UI", 20, "bold"), width=4, height=2).pack(side="left", padx=(0, 14))
        title_box = tk.Frame(header, bg=BG)
        title_box.pack(side="left", fill="x", expand=True)
        tk.Label(title_box, text="DUKE DASH", bg=BG, fg=TEXT, font=("Segoe UI", 26, "bold")).pack(anchor="w")
        tk.Label(title_box, text="RIDE  •  CONNECT  •  KNOW", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold")).pack(anchor="w")
        tk.Label(header, text="PC TEST DISPLAY", bg="#161616", fg=MUTED, font=("Segoe UI", 9, "bold"), padx=12, pady=8).pack(side="right")

        self.status = tk.Label(outer, text="●  SCANNING FOR DUKE DASH...", bg=BG, fg=MUTED, font=("Segoe UI", 12, "bold"))
        self.status.pack(anchor="w", pady=(18, 8))

        self.nav_card, self.nav_title, self.nav_meta = self.card(outer, "NAVIGATION")
        self.music_card, self.music_title, self.music_meta = self.card(outer, "NOW PLAYING")
        self.msg_card, self.msg_title, self.msg_meta = self.card(outer, "MESSAGING")

        footer = tk.Frame(outer, bg=BG)
        footer.pack(fill="x", pady=(12, 0))
        tk.Label(footer, text="PHONE  →  BLE  →  PC TEST DISPLAY", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold")).pack(side="left")
        ttk.Button(footer, text="RESCAN", command=self.rescan).pack(side="right")

    def card(self, parent, heading):
        frame = tk.Frame(parent, bg=CARD, highlightbackground="#202020", highlightthickness=1)
        frame.pack(fill="x", pady=8)
        inner = tk.Frame(frame, bg=CARD)
        inner.pack(fill="x", padx=20, pady=18)
        tk.Label(inner, text=heading, bg=CARD, fg=ORANGE, font=("Segoe UI", 11, "bold")).pack(anchor="w")
        title = tk.Label(inner, text="—", bg=CARD, fg=TEXT, font=("Segoe UI", 25, "bold"), anchor="w", justify="left")
        title.pack(fill="x", pady=(10, 4))
        meta = tk.Label(inner, text="Waiting for phone data...", bg=CARD, fg=MUTED, font=("Segoe UI", 12), anchor="w", justify="left", wraplength=680)
        meta.pack(fill="x")
        return frame, title, meta

    def start_ble_thread(self):
        self.ble_thread = threading.Thread(target=self.ble_worker, daemon=True)
        self.ble_thread.start()

    def ble_worker(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self.scan_and_connect())
        except Exception as exc:
            self.ui_status(f"●  BLE ERROR: {exc}", RED)
        finally:
            self.loop.close()

    async def scan_and_connect(self):
        # return_adv=True is more reliable on Windows than relying only on
        # the live detection callback. It lets us inspect the service UUID
        # actually advertised by the Android phone.
        while not self.stop_event.is_set():
            self.ui_status("●  SCANNING FOR DUKE DASH...", MUTED)
            target = None
            seen = []

            try:
                devices = await BleakScanner.discover(timeout=8.0, return_adv=True)
                for device, advertisement in devices.values():
                    name = device.name or getattr(advertisement, "local_name", None) or ""
                    service_uuids = [str(u).lower() for u in (getattr(advertisement, "service_uuids", None) or [])]
                    seen.append(name or device.address)

                    # Exact service match is the authoritative match.
                    # Name match is a fallback for stacks that hide service UUIDs.
                    if SERVICE_UUID.lower() in service_uuids or "duke dash" in name.lower():
                        target = device
                        break
            except Exception as exc:
                self.ui_status(f"●  BLE SCAN ERROR: {exc}", RED)
                await asyncio.sleep(2)
                continue

            if self.stop_event.is_set():
                return

            if target is None:
                if seen:
                    sample = ", ".join(seen[:4])
                    self.ui_status(f"●  DUKE DASH NOT FOUND — SEEN: {sample}", AMBER)
                else:
                    self.ui_status("●  NO BLE DEVICES FOUND — CHECK PC BLUETOOTH", AMBER)
                await asyncio.sleep(1)
                continue

            self.ui_status(f"●  FOUND {target.name or target.address} — CONNECTING...", AMBER)
            try:
                async with BleakClient(target, disconnected_callback=self.on_disconnected) as client:
                    self.client = client
                    self.connected = True
                    self.ui_status("●  PHONE DATA LIVE", GREEN)
                    await client.start_notify(DATA_UUID, self.notification_handler)

                    # Read the current snapshot immediately. Notifications then keep it live.
                    try:
                        value = await client.read_gatt_char(DATA_UUID)
                        self.notification_handler(DATA_UUID, value)
                    except Exception as exc:
                        self.ui_status(f"●  CONNECTED — LIVE DATA WAITING ({exc})", AMBER)

                    while client.is_connected and not self.stop_event.is_set():
                        await asyncio.sleep(0.5)
            except Exception as exc:
                self.connected = False
                self.ui_status(f"●  CONNECTION ERROR: {exc}", RED)
                await asyncio.sleep(2)
            finally:
                self.client = None
                self.connected = False

    def notification_handler(self, _characteristic, value):
        try:
            raw = bytes(value).decode("utf-8", errors="replace")
            data = parse_payload(raw)
            self.root.after(0, self.update_display, data)
        except Exception as exc:
            self.ui_status(f"●  DATA ERROR: {exc}", RED)

    def on_disconnected(self, _client):
        self.connected = False
        self.ui_status("○  PHONE DISCONNECTED — RECONNECTING...", AMBER)

    def update_display(self, data):
        nav = data["nav"]
        self.nav_title.config(text=direction_display(nav.get("direction", "")))
        distance = nav.get("distance", -1)
        instruction = nav.get("instruction", "Waiting for Google Maps...")
        road = nav.get("road", "")
        meta = []
        if distance >= 0:
            meta.append(f"{distance} m")
        if instruction:
            meta.append(instruction)
        if road:
            meta.append(road)
        self.nav_meta.config(text="  •  ".join(meta) if meta else "Waiting for Google Maps...")

        music = data["music"]
        title = music.get("title", "")
        artist = music.get("artist", "")
        state = music.get("state", "")
        self.music_title.config(text=title if title else "No active music")
        self.music_meta.config(text=f"{artist or 'Unknown artist'}  •  {state}" if title else "Start music on your phone")

        msg = data["msg"]
        if msg.get("app"):
            self.msg_title.config(text=msg.get("text") or "New message")
            self.msg_meta.config(text=msg.get("app", "").upper())
        else:
            self.msg_title.config(text="No new messages")
            self.msg_meta.config(text="Supported messaging apps only")

    def ui_status(self, text, color):
        try:
            self.root.after(0, lambda: self.status.config(text=text, fg=color))
        except tk.TclError:
            pass

    def rescan(self):
        self.ui_status("●  RESCANNING FOR DUKE DASH...", MUTED)

    def close(self):
        self.stop_event.set()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    app = DukeDashPC(root)
    root.mainloop()
