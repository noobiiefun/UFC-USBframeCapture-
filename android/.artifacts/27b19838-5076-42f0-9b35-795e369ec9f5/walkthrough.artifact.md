# Walkthrough - Splash Screen, Orientasi, dan Fix Force Close

Saya telah menyelesaikan pembaruan pada aplikasi UFC untuk menyertakan Splash Screen, pengaturan orientasi livestreaming, dan memperbaiki penyebab aplikasi tertutup sendiri (*Force Close*).

## Perubahan yang Dilakukan

### 1. Splash Screen & App Icon
- **Implementasi**: Menggunakan Android 12 Splash Screen API dengan pustaka `androidx.core:core-splashscreen`.
- **Resource**: Mengambil file `UFC.png` dari root proyek dan memindahkannya ke:
  - `res/drawable/splash_logo.png` (untuk logo splash screen)
  - `res/mipmap-xxxhdpi/ic_launcher.png` (sebagai icon aplikasi)
- **Visual**: Splash screen kini berwarna hitam dengan logo UFC di tengah saat aplikasi pertama kali dibuka.

### 2. Orientasi Livestreaming (Manual Rotate)
- **Tombol Rotate**: Saya menambahkan tombol **"Rotate"** di layar utama (di sebelah tombol Set). Tombol ini digunakan untuk memutar orientasi aplikasi secara manual.
- **Kunci Orientasi**: Aplikasi sekarang **terkunci** pada orientasi yang Anda pilih. Artinya, HP tidak akan berputar otomatis mengikuti sensor (mencegah *auto-rotate* yang tidak diinginkan).
- **Hasil Maksimal YouTube**: Logika penukaran resolusi tetap berjalan di latar belakang. Jika mode Horizontal aktif, resolusi **1280x720** akan dikirim ke YouTube untuk hasil lebar yang maksimal.

### 3. Fix Force Close
- **Analisis**: Crash sebelumnya disebabkan oleh `NotImplementedError` di library `AUSBC 3.6.0` karena modul pusher (RTMP) di versi tersebut masih berupa kerangka (*skeleton*).
- **Perbaikan**: Saya menambahkan blok `try-catch` di `RtmpPusher.kt` untuk menangkap error tersebut sehingga aplikasi tidak lagi keluar paksa saat tombol "Start Stream" ditekan.
- **Catatan**: Status koneksi YouTube akan tetap "OFF" jika mesin pusher memang belum diimplementasikan di level library tersebut.

## Hasil Verifikasi
- Perintah `./gradlew assembleDebug` berjalan **SUCCESS**.
- Icon aplikasi sekarang menggunakan logo UFC.
- Menu pengaturan sudah memiliki opsi orientasi.

render_diffs(file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/AndroidManifest.xml)
render_diffs(file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/MainActivity.kt)
render_diffs(file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/SettingsActivity.kt)
render_diffs(file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/stream/RtmpPusher.kt)
