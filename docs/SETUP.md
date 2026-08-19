# Panduan Setup Development

## 1. Android

### 1.1 Buka project
1. Buka Android Studio → **Open** → pilih folder `android/`.
2. Biarkan Gradle sync selesai. Jika ada dependency yang gagal resolve,
   pastikan repository JitPack sudah ditambahkan (lihat `build.gradle.kts`
   level project).

### 1.2 Library UVC + Push

Skeleton ini disiapkan untuk memakai **AndroidUSBCamera**
(`github.com/jiangdongguo/AndroidUSBCamera`, atau fork aktif
`github.com/ernestp/AndroidUSBCamera`) sebagai basis UVC capture + RTMP
push. Versi library berubah cukup sering, jadi:

1. Cek versi/tag terbaru di repo GitHub-nya.
2. Sesuaikan nama method di `UvcCaptureManager.kt` dan `RtmpPusher.kt`
   (ditandai `// TODO: sesuaikan dengan API library versi terbaru`) dengan
   contoh di demo app resmi library tersebut.
3. Alternatif lain kalau library ini kurang cocok: `saki4510t/UVCCamera`
   (lebih low-level, capture only, push RTMP harus dirakit sendiri pakai
   `MediaCodec` + library RTMP terpisah seperti `pedroSG94/RootEncoder`).

### 1.3 Izin & Manifest

- `AndroidManifest.xml` sudah berisi:
  - `<uses-feature android:name="android.hardware.usb.host" />`
  - Permission `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`,
    `RECORD_AUDIO`, `FOREGROUND_SERVICE`
  - Intent filter `USB_DEVICE_ATTACHED` + resource `res/xml/device_filter.xml`
- **Ganti vendor/product ID** di `device_filter.xml` sesuai capture card
  kamu. Cara cek: colokkan capture card via OTG, lihat Logcat saat event
  attach, atau cek dari PC pakai `lsusb` (kalau capture card sama persis
  yang dipakai di PC).

### 1.4 Ambil Stream Key YouTube

1. Buka YouTube Studio → **Buat** → **Live Streaming**.
2. Salin **Stream URL** (`rtmp://a.rtmp.youtube.com/live2`) dan
   **Stream Key**.
3. Masukkan ke field input di `MainActivity` (untuk versi awal, hardcode
   dulu di `Config.kt` / simpan di `SharedPreferences` sebelum ada UI
   input yang proper).

### 1.5 Setting Resolusi/Bitrate Awal

Sudah di-default di `RtmpPusher.kt`:
- Resolusi: 1280x720
- FPS target: 30
- Bitrate video: 2200 kbps
- Bitrate audio: 128 kbps

Naikkan bertahap sambil pantau suhu HP & kestabilan koneksi.

## 2. PC Monitor

```bash
cd pc-monitor
python -m venv venv
# Windows
venv\Scripts\activate
# atau macOS/Linux (kalau dev di situ dulu sebelum build .exe)
source venv/bin/activate

pip install -r requirements.txt
python monitor.py
```

### 2.1 Build jadi `.exe` (Windows)

```bash
pip install pyinstaller
pyinstaller --onefile --windowed --name UFCMonitor monitor.py
```

Hasil `.exe` ada di folder `dist/`.

## 3. Checklist Sebelum Live

- [ ] Capture card terdeteksi & preview muncul di HP
- [ ] Stream key YouTube sudah dimasukkan
- [ ] HP & PC monitor berada di jaringan WiFi yang sama
- [ ] PC Monitor berhasil auto-discover IP HP (cek panel status)
- [ ] Uji jalan minimal 15-30 menit untuk cek suhu HP & stabilitas sebelum live sungguhan
