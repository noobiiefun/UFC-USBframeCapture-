# UFC — USB Frame Capture

Aplikasi Android untuk menangkap gambar dari HDMI capture card (UVC) dan
langsung melakukan livestream ke YouTube, dilengkapi aplikasi monitor
terpisah di PC (via LAN) untuk memantau status stream tanpa mengganggu
proses streaming.

> Status proyek: **kerangka dasar (skeleton)**. Sebagian besar logic inti
> sudah ada strukturnya, beberapa bagian ditandai `TODO` untuk diselesaikan
> sesuai versi library yang dipakai.

## Alur Sistem

```
PC/Laptop (HDMI OUT)
      │
      ▼
HDMI Capture Card (UVC device)
      │  USB
      ▼
Android Phone (OTG) ──► UFC App
      │                     │
      │ capture+encode+push │ status (LAN)
      ▼                     ▼
  YouTube RTMP          PC Monitor App (.exe)
```

Detail lengkap ada di [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Struktur Repo

```
ufc-app/
├── android/          # Aplikasi Android (Kotlin) - capture UVC + live push + status server
├── pc-monitor/        # Aplikasi monitor PC (Python) - baca status via LAN
└── docs/              # Dokumentasi arsitektur, setup, roadmap, API
```

## Dokumentasi

- [Arsitektur & alur data](docs/ARCHITECTURE.md)
- [Panduan setup development](docs/SETUP.md)
- [Spesifikasi API status/discovery](docs/API.md)
- [Roadmap & progress](docs/ROADMAP.md)

## Prasyarat

- Android Studio (Ladybug atau lebih baru), min SDK 26+
- HP dengan dukungan USB OTG (target awal: Redmi A3 / Helio G36, Android 14 Go)
- HDMI capture card berbasis UVC (mis. chipset MS2109)
- Python 3.10+ untuk PC monitor
- Akun YouTube dengan live streaming diaktifkan (untuk ambil stream key)

## Quick Start

### Android
```bash
cd android
# buka folder ini di Android Studio, biarkan Gradle sync
# hubungkan capture card via OTG ke HP, lalu Run
```

### PC Monitor
```bash
cd pc-monitor
python -m venv venv
venv\Scripts\activate        # Windows
pip install -r requirements.txt
python monitor.py
```

## Catatan Performa (Redmi A3 / Helio G36, RAM 3-4GB)

Chipset ini kelas entry-level. Untuk stabil, mulai dari:
- Resolusi capture: **1280x720 @ 30fps**
- Bitrate video: **~2000-2500 kbps** (H.264, hardware encoder via MediaCodec)
- Audio: AAC 128kbps

Naikkan bertahap (mis. 1080p30) hanya setelah versi 720p terbukti stabil
tanpa overheat/lag berkepanjangan.

## Lisensi

Belum ditentukan — tambahkan file `LICENSE` sesuai kebutuhan sebelum publish publik.
