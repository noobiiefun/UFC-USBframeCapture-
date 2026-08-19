# Arsitektur

## Komponen

### 1. Android App (`android/`)

| Modul | Tanggung jawab |
|---|---|
| `ui/UfcCameraFragment` | Extends `CameraFragment` dari library AndroidUSBCamera — deteksi device UVC, minta permission USB, kelola preview, dan memicu `captureStreamStart()` untuk mulai mengambil stream H.264/AAC yang sudah di-encode library. |
| `stream/RtmpPusher` | Terima frame H.264/AAC terenkode dari `UfcCameraFragment`, mux ke FLV, kirim ke RTMP endpoint YouTube lewat `AusbcPusher`/`IPusher` (modul `:libpush`). |
| `model/StreamStatus` | Data class status stream (bitrate, fps, dropped frame, koneksi, uptime) + serialisasi ke JSON. |
| `StatusRepository` | Singleton pemegang `StateFlow<StreamStatus>` — sumber kebenaran tunggal untuk status terkini, diupdate oleh `RtmpPusher`/`UvcCaptureManager`. |
| `server/StatusServer` | HTTP server ringan (NanoHTTPD) yang expose `GET /status` dari `StatusRepository`. Berjalan di thread/port terpisah — **read-only**, tidak pernah menyentuh pipeline capture/encode/push. |
| `server/DiscoveryBroadcaster` | Broadcast UDP periodik di jaringan lokal supaya PC Monitor bisa menemukan IP HP otomatis, tanpa harus input manual. |
| `ui/MainActivity` | UI kontrol: pilih device, mulai/berhenti preview, mulai/berhenti live push, tampilkan status ringkas di layar. |

### 2. PC Monitor App (`pc-monitor/`)

| File | Tanggung jawab |
|---|---|
| `discovery.py` | Dengarkan broadcast UDP dari HP, temukan IP + port HTTP status. |
| `monitor.py` | GUI (Tkinter) yang polling `GET /status` tiap 1 detik dan menampilkan dashboard: status koneksi YouTube, bitrate, fps, dropped frame, uptime. |

## Prinsip Desain Penting

1. **Non-intrusive monitoring** — PC Monitor hanya *membaca* (`GET`), tidak pernah mengirim perintah kontrol ke HP di versi awal ini. Ini memastikan proses live streaming di HP tidak pernah terganggu oleh aktivitas monitoring.
2. **Threading terpisah** — thread capture/encode/push (jalur kritis, real-time) terpisah total dari thread HTTP server dan broadcaster (jalur non-kritis). Update status ke `StatusRepository` dilakukan lewat `StateFlow` yang thread-safe, bukan lock manual.
3. **Auto-discovery, bukan IP statis** — memakai UDP broadcast (bukan mDNS/NSD dulu, demi kesederhanaan implementasi awal) supaya pengguna tidak perlu tahu/isi IP HP secara manual. Bisa di-upgrade ke Android `NsdManager` (Bonjour/mDNS) di iterasi berikutnya.
4. **Degradasi resolusi/bitrate dulu, bukan fitur** — karena target device (Helio G36, RAM 3-4GB) terbatas, prioritas adalah stream yang *stabil* di resolusi lebih rendah dibanding stream resolusi tinggi yang patah-patah.

## Alur Data Status (ringkas)

```
UvcCaptureManager / RtmpPusher
        │  update(StreamStatus)
        ▼
   StatusRepository (StateFlow)
        │
        ├──► StatusServer (HTTP :8080/status)  ◄── polling ── PC Monitor
        └──► DiscoveryBroadcaster (UDP :8888)   ──broadcast──► PC Monitor (discovery)
```
