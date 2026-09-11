"""
UFC Monitor - dashboard sederhana untuk memantau status live streaming dari
UFC App di HP, lewat jaringan lokal (LAN/WiFi). Read-only, tidak mengirim
perintah apapun ke HP.

Jalankan: python monitor.py
Build .exe: lihat docs/SETUP.md bagian 2.1
"""
import json
import threading
import time
import tkinter as tk
from tkinter import ttk

import requests

from discovery import DiscoveredDevice, DiscoveryListener

POLL_INTERVAL_SEC = 1.0
HTTP_TIMEOUT_SEC = 2.0


class MonitorApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("UFC Monitor")
        self.root.geometry("360x320")

        self.device: DiscoveredDevice | None = None
        self._polling = False

        self._build_ui()

        self.listener = DiscoveryListener(on_found=self._on_device_found)
        self.listener.start()

    def _build_ui(self):
        pad = {"padx": 10, "pady": 4}

        self.lbl_device = ttk.Label(self.root, text="Mencari HP di jaringan lokal...")
        self.lbl_device.pack(anchor="w", **pad)

        self.fields = {}
        for key, label in [
            ("connected", "Capture Card"),
            ("youtubeConnected", "Koneksi YouTube"),
            ("resolution", "Resolusi"),
            ("fps", "FPS"),
            ("bitrateKbps", "Bitrate (kbps)"),
            ("droppedFrames", "Dropped Frames"),
            ("uptimeSec", "Uptime (detik)"),
        ]:
            row = ttk.Frame(self.root)
            row.pack(fill="x", **pad)
            ttk.Label(row, text=f"{label}:", width=18).pack(side="left")
            value_lbl = ttk.Label(row, text="-")
            value_lbl.pack(side="left")
            self.fields[key] = value_lbl

        # Fallback manual, kalau auto-discovery gagal (mis. beda subnet WiFi)
        manual_frame = ttk.LabelFrame(self.root, text="Manual (kalau auto-discovery gagal)")
        manual_frame.pack(fill="x", padx=10, pady=10)
        self.ip_entry = ttk.Entry(manual_frame)
        self.ip_entry.insert(0, "192.168.1.x")
        self.ip_entry.pack(side="left", padx=6, pady=6, expand=True, fill="x")
        ttk.Button(manual_frame, text="Connect", command=self._connect_manual).pack(side="left", padx=6)

    def _on_device_found(self, device: DiscoveredDevice):
        if self.device is None:
            self.device = device
            self.root.after(0, self._start_polling_ui)

    def _connect_manual(self):
        ip = self.ip_entry.get().strip()
        if ip:
            self.device = DiscoveredDevice(ip=ip, http_port=8080, name="Manual")
            self._start_polling_ui()

    def _start_polling_ui(self):
        assert self.device is not None
        self.lbl_device.config(text=f"Terhubung ke {self.device.name} ({self.device.ip})")
        if not self._polling:
            self._polling = True
            threading.Thread(target=self._poll_loop, daemon=True).start()

    def _poll_loop(self):
        while self._polling and self.device is not None:
            try:
                url = f"http://{self.device.ip}:{self.device.http_port}/status"
                resp = requests.get(url, timeout=HTTP_TIMEOUT_SEC)
                data = resp.json()
                self.root.after(0, self._update_fields, data)
            except (requests.RequestException, json.JSONDecodeError):
                self.root.after(0, self._mark_unreachable)
            time.sleep(POLL_INTERVAL_SEC)

    def _update_fields(self, data: dict):
        for key, widget in self.fields.items():
            value = data.get(key, "-")
            if isinstance(value, bool):
                value = "Ya" if value else "Tidak"
            widget.config(text=str(value))

    def _mark_unreachable(self):
        for widget in self.fields.values():
            widget.config(text="(tidak terjangkau)")


def main():
    root = tk.Tk()
    MonitorApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
