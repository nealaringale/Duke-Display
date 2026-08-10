import asyncio
import base64
import threading
import time
import tkinter as tk
from tkinter import ttk
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8f8a0001-5d3e-4d0a-9c7b-000000000001"
DATA_UUID = "8f8a0002-5d3e-4d0a-9c7b-000000000002"
FRAME_PREFIX = "DD1"

ORANGE = "#ff6500"
BG = "#050505"
CARD = "#111111"
CARD2 = "#161616"
TEXT = "#f7f7f7"
MUTED = "#919191"
GREEN = "#5cd274"
RED = "#ff5c5c"
AMBER = "#ffb000"
CALL_BG = "#220d0d"
MESSAGE_TIMEOUT_MS = 5000
FRAME_TIMEOUT_SECONDS = 5.0


def parse_payload(raw: str):
    data = {
        "time": "--:--", "battery": -1, "charging": False,
        "conn": "DISCONNECTED",
        "nav": {"direction": "", "distance": -1, "instruction": "", "road": "", "eta": "", "destination_distance": -1, "active": False},
        "music": {"title": "", "artist": "", "state": "PAUSED"},
        "msg": {"app": "", "text": "", "timestamp": 0},
        "call": {"active": False, "caller": ""},
    }
    for line in raw.splitlines():
        parts = line.split("|")
        if not parts:
            continue
        kind = parts[0]
        if kind == "TIME" and len(parts) >= 2:
            data["time"] = parts[1]
        elif kind == "BATTERY" and len(parts) >= 3:
            try:
                data["battery"] = int(parts[1])
            except ValueError:
                data["battery"] = -1
            data["charging"] = parts[2] == "CHARGING"
        elif kind == "CONN" and len(parts) >= 2:
            data["conn"] = parts[1]
        elif kind == "NAV" and len(parts) >= 8:
            try:
                distance = int(parts[2])
            except ValueError:
                distance = -1
            try:
                destination_distance = int(parts[6])
            except ValueError:
                destination_distance = -1
            data["nav"] = {"direction": parts[1], "distance": distance, "instruction": parts[3], "road": parts[4], "eta": parts[5], "destination_distance": destination_distance, "active": parts[7] == "ACTIVE"}
        elif kind == "MUSIC" and len(parts) >= 4:
            data["music"] = {"title": parts[1], "artist": parts[2], "state": parts[3]}
        elif kind == "MSG" and len(parts) >= 4:
            try:
                timestamp = int(parts[3])
            except ValueError:
                timestamp = 0
            data["msg"] = {"app": parts[1], "text": parts[2], "timestamp": timestamp}
        elif kind == "CALL" and len(parts) >= 3:
            data["call"] = {"active": parts[1] == "ACTIVE", "caller": parts[2]}
    return data


def direction_display(direction: str) -> str:
    return {
        "RIGHT": "↗  RIGHT", "LEFT": "↖  LEFT", "SLIGHT_RIGHT": "↗  SLIGHT RIGHT", "SLIGHT_LEFT": "↖  SLIGHT LEFT",
        "SHARP_RIGHT": "↗  SHARP RIGHT", "SHARP_LEFT": "↖  SHARP LEFT", "UTURN": "↶  U-TURN",
        "KEEP_RIGHT": "↗  KEEP RIGHT", "KEEP_LEFT": "↖  KEEP LEFT", "ARRIVE": "●  ARRIVE", "ROUNDABOUT": "↻  ROUNDABOUT",
        "NORTH": "↑  HEAD NORTH", "SOUTH": "↓  HEAD SOUTH", "EAST": "→  HEAD EAST", "WEST": "←  HEAD WEST",
    }.get(direction, "↑  STRAIGHT")


class DukeDashPC:
    """PC simulator for the real Duke Dash BLE/TFT display."""

    def __init__(self, root):
        self.root = root
        self.root.title("Duke Dash — PC Display Prototype")
        self.root.geometry("900x820")
        self.root.minsize(720, 680)
        self.root.configure(bg=BG)
        self.stop_event = threading.Event()
        self.client = None
        self.loop = None
        self.notification_after = None
        self.message_generation = 0
        self.frame_buffers = {}

        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TButton", background=CARD2, foreground=TEXT, bordercolor="#303030", padding=10, font=("Segoe UI", 10, "bold"))
        style.map("TButton", background=[("active", "#252525")])

        self.build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.start_ble_thread()
        self.root.after(1000, self.prune_frames)

    def build_ui(self):
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=24, pady=18)

        header = tk.Frame(outer, bg=BG)
        header.pack(fill="x")
        tk.Label(header, text="DD", bg=ORANGE, fg="white", font=("Segoe UI", 20, "bold"), width=4, height=2).pack(side="left", padx=(0, 14))
        title_box = tk.Frame(header, bg=BG)
        title_box.pack(side="left", fill="x", expand=True)
        tk.Label(title_box, text="DUKE DASH", bg=BG, fg=TEXT, font=("Segoe UI", 26, "bold")).pack(anchor="w")
        tk.Label(title_box, text="RIDE  •  CONNECT  •  KNOW", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold")).pack(anchor="w")
        self.top_info = tk.Label(header, text="--:--   ●   🔋 --%", bg=BG, fg=TEXT, font=("Segoe UI", 12, "bold"))
        self.top_info.pack(side="right")

        self.status = tk.Label(outer, text="●  SCANNING FOR DUKE DASH...", bg=BG, fg=MUTED, font=("Segoe UI", 12, "bold"))
        self.status.pack(anchor="w", pady=(14, 6))

        self.nav_card, self.nav_title, self.nav_meta = self.card(outer, "NAVIGATION")
        self.music_card, self.music_title, self.music_meta = self.card(outer, "NOW PLAYING")
        self.msg_card, self.msg_title, self.msg_meta = self.card(outer, "MESSAGING")

        self.call_card = tk.Frame(outer, bg=CALL_BG, highlightbackground=RED, highlightthickness=1)
        call_inner = tk.Frame(self.call_card, bg=CALL_BG)
        call_inner.pack(fill="x", padx=20, pady=16)
        tk.Label(call_inner, text="📞 INCOMING CALL", bg=CALL_BG, fg=RED, font=("Segoe UI", 12, "bold")).pack(anchor="w")
        self.call_name = tk.Label(call_inner, text="", bg=CALL_BG, fg=TEXT, font=("Segoe UI", 22, "bold"))
        self.call_name.pack(anchor="w", pady=(4, 0))

        footer = tk.Frame(outer, bg=BG)
        footer.pack(fill="x", pady=(10, 0))
        tk.Label(footer, text="PHONE  →  BLE  →  PC TEST DISPLAY", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold")).pack(side="left")
        ttk.Button(footer, text="RESCAN", command=self.rescan).pack(side="right")

    def card(self, parent, heading):
        frame = tk.Frame(parent, bg=CARD, highlightbackground="#202020", highlightthickness=1)
        frame.pack(fill="x", pady=6)
        inner = tk.Frame(frame, bg=CARD)
        inner.pack(fill="x", padx=20, pady=15)
        tk.Label(inner, text=heading, bg=CARD, fg=ORANGE, font=("Segoe UI", 11, "bold")).pack(anchor="w")
        title = tk.Label(inner, text="—", bg=CARD, fg=TEXT, font=("Segoe UI", 25, "bold"), anchor="w", justify="left")
        title.pack(fill="x", pady=(8, 3))
        meta = tk.Label(inner, text="Waiting for phone data...", bg=CARD, fg=MUTED, font=("Segoe UI", 12), anchor="w", justify="left", wraplength=780)
        meta.pack(fill="x")
        return frame, title, meta

    def start_ble_thread(self):
        threading.Thread(target=self.ble_worker, daemon=True).start()

    def ble_worker(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self.scan_and_connect())
        except Exception as exc:
            self.ui_status(f"●  BLE ERROR: {exc}", RED)
        finally:
            self.loop.close()
            self.loop = None

    async def scan_and_connect(self):
        while not self.stop_event.is_set():
            self.ui_status("●  SCANNING FOR DUKE DASH...", MUTED)
            target = None
            seen = []
            try:
                devices = await BleakScanner.discover(timeout=6.0, return_adv=True)
                for device, advertisement in devices.values():
                    name = device.name or getattr(advertisement, "local_name", None) or ""
                    uuids = [str(u).lower() for u in (getattr(advertisement, "service_uuids", None) or [])]
                    seen.append(name or getattr(device, "address", "unknown"))
                    if SERVICE_UUID.lower() in uuids or "duke dash" in name.lower():
                        target = device
                        break
            except Exception as exc:
                self.ui_status(f"●  BLE SCAN ERROR: {exc}", RED)
                await asyncio.sleep(2)
                continue

            if self.stop_event.is_set():
                return
            if target is None:
                sample = ", ".join(seen[:4]) if seen else "none"
                self.ui_status(f"●  DUKE DASH NOT FOUND — SEEN: {sample}", AMBER)
                await asyncio.sleep(1)
                continue

            self.ui_status(f"●  FOUND {target.name or target.address} — CONNECTING...", AMBER)
            try:
                async with BleakClient(target, disconnected_callback=self.on_disconnected) as client:
                    self.client = client
                    self.frame_buffers.clear()
                    self.ui_status("●  PHONE DATA LIVE", GREEN)
                    await client.start_notify(DATA_UUID, self.notification_handler)
                    try:
                        value = await client.read_gatt_char(DATA_UUID)
                        self.accept_payload(bytes(value))
                    except Exception:
                        pass
                    while client.is_connected and not self.stop_event.is_set():
                        await asyncio.sleep(0.25)
            except Exception as exc:
                if not self.stop_event.is_set():
                    self.ui_status(f"●  CONNECTION ERROR: {exc}", RED)
                    await asyncio.sleep(2)
            finally:
                self.client = None
                self.frame_buffers.clear()

    def notification_handler(self, _characteristic, value):
        try:
            self.accept_payload(bytes(value))
        except Exception as exc:
            self.ui_status(f"●  DATA ERROR: {exc}", RED)

    def accept_payload(self, packet: bytes):
        text = packet.decode("utf-8", errors="replace")
        if not text.startswith(FRAME_PREFIX + "|"):
            self.root.after(0, self.update_display, parse_payload(text))
            return

        parts = text.split("|", 4)
        if len(parts) != 5:
            return
        _, frame_id, seq_text, total_text, encoded_chunk = parts
        try:
            seq, total = int(seq_text), int(total_text)
        except ValueError:
            return
        if total <= 0 or seq < 1 or seq > total or total > 256:
            return

        now = time.monotonic()
        buffer = self.frame_buffers.get(frame_id)
        if buffer is None or buffer["total"] != total:
            buffer = {"total": total, "chunks": {}, "created": now}
            self.frame_buffers[frame_id] = buffer
        buffer["chunks"][seq] = encoded_chunk

        if len(self.frame_buffers) > 8:
            oldest = min(self.frame_buffers, key=lambda key: self.frame_buffers[key]["created"])
            if oldest != frame_id:
                self.frame_buffers.pop(oldest, None)

        if len(buffer["chunks"]) != total:
            return

        try:
            encoded = "".join(buffer["chunks"][i] for i in range(1, total + 1))
            raw = base64.b64decode(encoded, validate=True).decode("utf-8")
        except Exception as exc:
            self.frame_buffers.pop(frame_id, None)
            self.ui_status(f"●  FRAME ERROR: {exc}", RED)
            return

        self.frame_buffers.pop(frame_id, None)
        self.root.after(0, self.update_display, parse_payload(raw))

    def prune_frames(self):
        now = time.monotonic()
        for frame_id, buffer in list(self.frame_buffers.items()):
            if now - buffer["created"] > FRAME_TIMEOUT_SECONDS:
                self.frame_buffers.pop(frame_id, None)
        if not self.stop_event.is_set():
            self.root.after(1000, self.prune_frames)

    def on_disconnected(self, _client):
        if not self.stop_event.is_set():
            self.ui_status("○  PHONE DISCONNECTED — RECONNECTING...", AMBER)

    def update_display(self, data):
        battery = data.get("battery", -1)
        battery_text = "--%" if battery < 0 else f"{battery}%"
        connected = data.get("conn") == "CONNECTED"
        charging = " ⚡" if data.get("charging") else ""
        self.top_info.config(text=f"{data.get('time', '--:--')}   ●   🔋 {battery_text}{charging}", fg=GREEN if connected else AMBER)

        nav = data.get("nav", {})
        active = nav.get("active", False)
        self.nav_title.config(text=direction_display(nav.get("direction", "")) if active else "NO NAVIGATION")
        meta = []
        if nav.get("distance", -1) >= 0:
            meta.append(f"{nav['distance']} m")
        if nav.get("instruction"):
            meta.append(nav["instruction"])
        if nav.get("road"):
            meta.append(nav["road"])
        if nav.get("eta"):
            meta.append(f"ETA {nav['eta']}")
        if nav.get("destination_distance", -1) >= 0:
            meta.append(f"DEST {nav['destination_distance']} m")
        self.nav_meta.config(text="  •  ".join(meta) if meta else ("Waiting for Google Maps..." if active else "Start navigation on your phone"))

        music = data.get("music", {})
        title = music.get("title", "")
        self.music_title.config(text=title or "No active music")
        self.music_meta.config(text=f"{music.get('artist') or 'Unknown artist'}  •  {music.get('state', '')}" if title else "Start music on your phone")

        msg = data.get("msg", {})
        if msg.get("app"):
            self.message_generation += 1
            generation = self.message_generation
            self.msg_title.config(text=msg.get("text") or "New message")
            self.msg_meta.config(text=msg.get("app", "").upper())
            if self.notification_after:
                try:
                    self.root.after_cancel(self.notification_after)
                except tk.TclError:
                    pass
            self.notification_after = self.root.after(MESSAGE_TIMEOUT_MS, lambda: self.hide_message(generation))
        elif not self.call_card.winfo_ismapped():
            self.hide_message()

        call = data.get("call", {})
        if call.get("active"):
            self.call_name.config(text=call.get("caller") or "Incoming call")
            if not self.call_card.winfo_ismapped():
                self.call_card.pack(fill="x", pady=6, before=self.msg_card)
        else:
            self.hide_call()

        if battery >= 0 and battery < 10:
            self.ui_status("●  PHONE BATTERY LOW", RED)
        elif connected:
            self.ui_status("●  PHONE DATA LIVE", GREEN)

    def hide_message(self, generation=None):
        if generation is not None and generation != self.message_generation:
            return
        self.msg_title.config(text="No new messages")
        self.msg_meta.config(text="Supported messaging apps only")
        self.notification_after = None

    def hide_call(self):
        try:
            self.call_card.pack_forget()
        except tk.TclError:
            pass

    def ui_status(self, text, color):
        try:
            self.root.after(0, lambda: self.status.config(text=text, fg=color))
        except (RuntimeError, tk.TclError):
            pass

    def rescan(self):
        self.ui_status("●  RESCANNING FOR DUKE DASH...", MUTED)
        self.frame_buffers.clear()
        self.message_generation += 1
        if self.loop and self.loop.is_running():
            try:
                asyncio.run_coroutine_threadsafe(self.disconnect_current(), self.loop)
            except Exception:
                pass

    async def disconnect_current(self):
        if self.client is not None:
            try:
                await self.client.disconnect()
            except Exception:
                pass

    def close(self):
        self.stop_event.set()
        self.frame_buffers.clear()
        if self.loop and self.loop.is_running():
            try:
                asyncio.run_coroutine_threadsafe(self.disconnect_current(), self.loop)
            except Exception:
                pass
        self.root.destroy()


def main():
    root = tk.Tk()
    DukeDashPC(root)
    root.mainloop()


if __name__ == "__main__":
    main()
