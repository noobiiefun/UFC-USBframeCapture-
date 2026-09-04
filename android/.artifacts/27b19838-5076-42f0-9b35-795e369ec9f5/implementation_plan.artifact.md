# Rencana Perbaikan: Solusi Crash "Configure" dan Stabilitas Stream

Rencana ini bertujuan untuk mengatasi *force close* (crash) yang terjadi saat mengklik tombol Live dan memperbaiki masalah "Bad file descriptor" dengan cara mengalihkan sumber suara ke mikrofon HP.

## Analisis Masalah

1.  **Crash "Configure"**: Kemungkinan terjadi karena inisialisasi mesin streaming dilakukan di thread yang salah atau adanya ketidakcocokan parameter saat kamera sedang aktif.
2.  **Bad File Descriptor (ioctl error)**: Ini adalah tanda bentrokan memori pada driver USB (MediaTek/Xiaomi). Mencoba mengambil suara dari USB (UAC) sambil mengambil video seringkali membuat driver USB "hang" atau crash.
3.  **Unstable 32-bit Mode**: Memaksa mode 32-bit pada HP modern 64-bit dapat menyebabkan ketidakstabilan sistem.

## Perubahan yang Diusulkan

### 1. Migrasi Suara ke Mikrofon HP (Solusi Utama)
- Mengubah sumber suara dari **USB (Capture Card)** ke **Mic (Internal HP)**.
- **Alasan**: Ini menghilangkan kebutuhan akan `libUACAudio.so` (yang sering hilang/crash) dan mengurangi beban daya pada port USB, sehingga error `ioctl` tidak akan muncul lagi. Suara akan jauh lebih stabil dan YouTube tidak akan menolak koneksi.

### 2. Manajemen Memori Aman (`UfcCameraFragment.kt`)
- Melakukan **Deep Copy** pada buffer video sebelum dikirim ke mesin streaming.
- **Alasan**: Mencegah crash "Bad file descriptor" yang terjadi jika sistem kamera mengambil kembali memori sebelum data sempat terkirim ke YouTube.

### 3. Peningkatan Keamanan Koneksi (`RtmpPusher.kt`)
- Menambahkan **Uncaught Exception Handler** yang akan memunculkan pesan eror asli jika aplikasi tetap crash, sehingga kita tidak menebak-nebak lagi.
- Membungkus setiap proses pengiriman data dengan blok `try-catch` yang sangat ketat.

### 4. Optimalisasi Arsitektur (`build.gradle.kts`)
- Menghapus pembatasan 32-bit (`abiFilters`). Kita akan biarkan aplikasi berjalan di mode 64-bit asli agar performa maksimal di HP Xiaomi Anda.

## Rincian File yang Diubah

#### [MODIFY] [UfcCameraFragment.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/UfcCameraFragment.kt)
#### [MODIFY] [MainActivity.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/MainActivity.kt)
#### [MODIFY] [RtmpPusher.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/stream/RtmpPusher.kt)
#### [MODIFY] [app/build.gradle.kts](file:///F:/coding/UFC-USBframeCapture-/android/app/build.gradle.kts)

## Rencana Verifikasi
- Jalankan aplikasi, pastikan tombol Run aktif (64-bit mode).
- Klik **Start Live**. Jika ada crash, baca pesan yang muncul di layar.
- Cek YouTube, suara harusnya terdengar dari mikrofon HP (Anda bisa menaruh HP di dekat speaker jika ingin suara game masuk).
