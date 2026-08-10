import asyncio
import threading
import tkinter as tk
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8f8a0001-5d3e-4d0a-9c7b-000000000001"
DATA_UUID = "8f8a0002-5d3e-4d0a-9c7b-000000000002"

BG = "#050505"
CARD = "#111111"
ORANGE = "#ff6500"
WHITE = "#f7f7f7"
MUTED = "#8f8f8f"
GREEN = "#5cd274"

class DukeDashPC:
    def __init__(self, root):
        self.root = root
        self.root.title("Duke Dash — PC Display Prototype")
        self.root.configure(bg=BG)
        self.root.geometry("720x520")
        self.root.minsize(620, 450)
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()
        self.client = None
        self.device = None
        self.connected = False
        self.status_var = tk.StringVar(value="SCANNING FOR DUKE DASH…")
        self.nav_var = tk.StringVar(value="↑  STRAIGHT")
        self.nav_meta = tk.StringVar(value="Waiting for Google Maps…")
        self.music_var = tk.StringVar(value="No active music")
        self.music_meta = tk.StringVar(value="Start music on your phone")
        self.msg_var = tk.StringVar(value="No new messages")
        self.msg_meta = tk.StringVar(value="Supported messaging apps only")
        self.build()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.scan()

    def label(self, parent, var, size, color=WHITE, bold=False):
        return tk.Label(parent, textvariable=var, bg=parent.cget("bg"), fg=color,
                        font=("Segoe UI", size, "bold" if bold else "normal"), anchor="w")

    def card(self, parent, title, main_var, meta_var):
        frame = tk.Frame(parent, bg=CARD, highlightthickness=1, highlightbackground="#242424")
        tk.Label(frame, text=title, bg=CARD, fg=ORANGE, font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=20, pady=(14, 2))
        self.label(frame, main_var, 24, WHITE, True).pack(anchor="w", padx=20, pady=(5, 2))
        self.label(frame, meta_var, 11, MUTED).pack(anchor="w", padx=20, pady=(0, 14))
        return frame

    def build(self):
        header = tk.Frame(self.root, bg=BG)
        header.pack(fill="x", padx=24, pady=(18, 8))
        logo = tk.Label(header, text="DD", bg=ORANGE, fg="white", font=("Segoe UI", 18, "bold"), width=4, height=2)
        logo.pack(side="left", padx=(0, 12))
        title = tk.Frame(header, bg=BG)
        title.pack(side="left")
        tk.Label(title, text="DUKE DASH", bg=BG, fg=WHITE, font=("Segoe UI", 23, "bold")).pack(anchor="w")
        tk.Label(title, text="RIDE  •  CONNECT  •  KNOW", bg=BG, fg=MUTED, font=("Segoe UI", 9, "bold")).pack(anchor="w")
        tk.Label(header, text="PC TEST DISPLAY", bg="#161616", fg=MUTED, font=("Segoe UI", 9, "bold"), padx=10, pady=7).pack(side="right")

        status = tk.Label(self.root, textvariable=self.status_var, bg=BG, fg=GREEN, font=("Segoe UI", 11, "bold"), anchor="w")
        status.pack(fill="x", padx=26, pady=(3, 8))

        body = tk.Frame(self.root, bg=BG)
        body.pack(fill="both", expand=True, padx=20)
        self.card(body, "NAVIGATION", self.nav_var, self.nav_meta).pack(fill="x", pady=5)
        self.card(body, "NOW PLAYING", self.music_var, self.music_meta).pack(fill="x", pady=5)
        self.card(body, "MESSAGING", self.msg_var, self.msg_meta).pack(fill="x", pady=5)

        bottom = tk.Frame(self.root, bg=BG)
        bottom.pack(fill="x", padx=24, pady=(8, 18))
        tk.Label(bottom, text="PHONE  →  BLE  →  PC TEST DISPLAY", bg=BG, fg=MUTED, font=("Segoe UI", 9, "bold")).pack(side="left")
        self.retry = tk.Button(bottom, text="RESCAN", command=self.scan, bg="#161616", fg=WHITE, activebackground="#242424", activeforeground=WHITE, relief="flat", padx=14, pady=7)
        self.retry.pack(side="right")

    def scan(self):
        self.status_var.set("●  SCANNING FOR DUKE DASH…")
        self.retry.config(state="disabled")
        asyncio.run_coroutine_threadsafe(self._scan(), self.loop)

    async def _scan(self):
        try:
            # Request advertisement metadata as well as device objects. On Windows,
            # service UUIDs are often exposed in AdvertisementData rather than
            # device.metadata, especially when the UUID is in the scan response.
            discovered = await BleakScanner.discover(timeout=8.0, return_adv=True)

            target = None
            for address, item in discovered.items():
                device, adv = item
                name = (device.name or adv.local_name or "").strip()
                uuids = {u.lower() for u in (adv.service_uuids or [])}
                if "duke dash" in name.lower() or SERVICE_UUID.lower() in uuids:
                    target = device
                    break

            if target is None:
                self.root.after(0, lambda: self.status_var.set("○  DUKE DASH NOT FOUND — TAP RESCAN"))
                self.root.after(0, lambda: self.retry.config(state="normal"))
                return

            self.device = target
            self.root.after(0, lambda d=target: self.status_var.set(f"●  FOUND {d.name or d.address} — CONNECTING…"))
            await self._connect(target)
        except Exception as exc:
            self.root.after(0, lambda e=str(exc): self.status_var.set(f"○  SCAN ERROR: {e}"))
            self.root.after(0, lambda: self.retry.config(state="normal"))

    async def _connect(self, device):
        self.client = BleakClient(device, disconnected_callback=self.disconnected)
        try:
            await self.client.connect()
            self.connected = True
            await self.client.start_notify(DATA_UUID, self.notification)
            self.root.after(0, lambda: self.status_var.set("●  DISPLAY CONNECTED • PHONE DATA LIVE"))
            self.root.after(0, lambda: self.retry.config(state="normal"))
        except Exception as exc:
            self.connected = False
            self.root.after(0, lambda e=str(exc): self.status_var.set(f"○  CONNECTION FAILED: {e}"))
            self.root.after(0, lambda: self.retry.config(state="normal"))

    def notification(self, _sender, data):
        try:
            payload = data.decode("utf-8", errors="replace")
            values = {}
            for line in payload.splitlines():
                parts = line.split("|", 1)
                if len(parts) == 2:
                    values[parts[0]] = parts[1]
            nav = values.get("NAV", "STRAIGHT|-1|Waiting for Google Maps…|").split("|", 3)
            direction = nav[0] if len(nav) > 0 else "STRAIGHT"
            distance = nav[1] if len(nav) > 1 else "-1"
            instruction = nav[2] if len(nav) > 2 else ""
            road = nav[3] if len(nav) > 3 else ""
            arrows = {
                "RIGHT": "↗  RIGHT", "LEFT": "↖  LEFT", "SLIGHT_RIGHT": "↗  SLIGHT RIGHT",
                "SLIGHT_LEFT": "↖  SLIGHT LEFT", "SHARP_RIGHT": "↗  SHARP RIGHT", "SHARP_LEFT": "↖  SHARP LEFT",
                "UTURN": "↶  U-TURN", "KEEP_RIGHT": "↗  KEEP RIGHT", "KEEP_LEFT": "↖  KEEP LEFT",
                "ARRIVE": "●  ARRIVE", "ROUNDABOUT": "↻  ROUNDABOUT", "NORTH": "↑  HEAD NORTH",
                "SOUTH": "↓  HEAD SOUTH", "EAST": "→  HEAD EAST", "WEST": "←  HEAD WEST", "STRAIGHT": "↑  STRAIGHT"
            }
            main = arrows.get(direction, "↑  " + direction.replace("_", " "))
            meta = instruction or "Waiting for Google Maps…"
            if distance not in ("", "-1"):
                meta = f"{distance} m  •  {meta}"
            if road:
                meta += f"\n{road}"

            music = values.get("MUSIC", "|||PAUSED").split("|", 2)
            title = music[0] if len(music) > 0 and music[0] else "No active music"
            artist = music[1] if len(music) > 1 and music[1] else "Start music on your phone"
            playing = music[2] if len(music) > 2 else "PAUSED"

            msg = values.get("MSG", "")
            if msg:
                mp = msg.split("|", 1)
                app = mp[0] if mp else "MESSAGE"
                text = mp[1] if len(mp) > 1 else "New message"
            else:
                app, text = "Supported messaging apps only", "No new messages"

            self.root.after(0, lambda: self.update_ui(main, meta, title, f"{artist}  •  {playing}", text, app.upper()))
        except Exception as exc:
            self.root.after(0, lambda e=str(exc): self.status_var.set(f"○  PACKET ERROR: {e}"))

    def update_ui(self, nav, nav_meta, music, music_meta, msg, msg_meta):
        self.nav_var.set(nav)
        self.nav_meta.set(nav_meta)
        self.music_var.set(music)
        self.music_meta.set(music_meta)
        self.msg_var.set(msg)
        self.msg_meta.set(msg_meta)

    def disconnected(self, _client):
        self.connected = False
        self.root.after(0, lambda: self.status_var.set("○  PHONE DISCONNECTED — RESCAN TO RECONNECT"))

    def close(self):
        async def shutdown():
            if self.client and self.client.is_connected:
                await self.client.disconnect()
        asyncio.run_coroutine_threadsafe(shutdown(), self.loop)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.root.destroy()

if __name__ == "__main__":
    root = tk.Tk()
    DukeDashPC(root)
    root.mainloop()
