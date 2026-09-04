# Rencana Implementasi: Perbaikan Fitur Livestreaming (RTMP)

Tugas ini bertujuan untuk mengganti mesin streaming "kosong" dari library bawaan dengan mesin streaming RTMP yang benar-benar berfungsi agar Anda bisa melakukan live ke YouTube.

## User Review Required

> [!IMPORTANT]
> **Pustaka Baru**: Saya akan menambahkan pustaka `RootEncoder` (oleh pedroSG94) yang merupakan standar industri untuk streaming RTMP di Android. Ini diperlukan karena library `AndroidUSBCamera` versi terbaru tidak menyertakan implementasi pengiriman data ke server.

## Perubahan yang Diusulkan

### 1. Penambahan Dependency
- Menambahkan `com.github.pedroSG94.RootEncoder:rtmp:2.8.1` ke `app/build.gradle.kts`. Pustaka ini sangat stabil dan mendukung pengiriman data mentah H.264/AAC.

### 2. Implementasi RTMP Real (`RtmpPusher.kt`)
- Menghapus ketergantungan pada `AusbcPusher` yang kosong.
- Menggunakan `RtmpClient` dari library `RootEncoder`.
- Menambahkan logika penanganan koneksi (reconnect otomatis jika sinyal drop).
- Memastikan data audio dan video dikirim secara sinkron agar tidak ada jeda antara suara dan gambar.

### 3. Integrasi Data Encode (`UfcCameraFragment.kt`)
- Menghubungkan output dari *encoder* capture card (H.264 dan AAC) langsung ke mesin RTMP baru.
- Menangani data **SPS/PPS** (metadata video) secara otomatis agar YouTube bisa mengenali resolusi dan format video Anda.

### 4. Peningkatan Informasi Status (`MainActivity.kt`)
- Memperbarui indikator **YT: LIVE** atau **YT: OFF** agar benar-benar mencerminkan status koneksi ke server YouTube.

## Rincian File yang Diubah

#### [MODIFY] [app/build.gradle.kts](file:///F:/coding/UFC-USBframeCapture-/android/app/build.gradle.kts)
#### [MODIFY] [RtmpPusher.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/stream/RtmpPusher.kt)
#### [MODIFY] [UfcCameraFragment.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/UfcCameraFragment.kt)

## Rencana Verifikasi

### Manual
1.  Buka aplikasi, masukkan RTMP URL dan Stream Key YouTube Anda.
2.  Klik **Start Live**.
3.  Pastikan indikator berubah menjadi **YT: LIVE**.
4.  Buka Dashboard YouTube Studio Anda, pastikan stream sudah masuk dan gambar terlihat lancar.
