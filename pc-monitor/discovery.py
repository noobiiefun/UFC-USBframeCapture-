"""
Dengarkan broadcast UDP dari UFC App di HP untuk menemukan IP + port HTTP
status secara otomatis. Lihat docs/API.md untuk format pesan.
"""
import socket
import threading
from dataclasses import dataclass
from typing import Callable, Optional

UDP_PORT = 8888
MESSAGE_PREFIX = "UFC_MONITOR"


@dataclass
class DiscoveredDevice:
    ip: str
    http_port: int
    name: str


class DiscoveryListener:
    def __init__(self, on_found: Callable[[DiscoveredDevice], None]):
        self._on_found = on_found
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._listen_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._sock:
            self._sock.close()

    def _listen_loop(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("", UDP_PORT))

        while self._running:
            try:
                data, addr = self._sock.recvfrom(1024)
            except OSError:
                break  # socket ditutup saat stop()

            try:
                text = data.decode("utf-8").strip()
                parts = text.split("|")
                if len(parts) != 4 or parts[0] != MESSAGE_PREFIX:
                    continue

                _, ip_field, port_field, name = parts
                # Kalau HP belum sempat isi IP-nya sendiri (masih "0.0.0.0"),
                # pakai source address paket UDP sebagai fallback.
                ip = ip_field if ip_field != "0.0.0.0" else addr[0]

                self._on_found(DiscoveredDevice(ip=ip, http_port=int(port_field), name=name))
            except (ValueError, UnicodeDecodeError):
                continue
