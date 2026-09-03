# Rencana Perbaikan: Native Crash dan Stabilitas Lifecycle

Rencana ini bertujuan untuk menghentikan Native Crash (`SIGABRT` pada mutex) yang menyebabkan aplikasi menutup sendiri dan memastikan siklus hidup kamera berjalan aman saat pergantian orientasi.

## Analisis Masalah

1.  **Native Crash (Destroyed Mutex)**: Logcat menunjukkan `pthread_mutex_lock called on a destroyed mutex` di dalam `libuvc.so`. Ini terjadi karena library mencoba mengakses perangkat USB saat proses pembersihan (*cleanup*) sedang berjalan atau sudah selesai namun thread-nya masih aktif.
2.  **Recreation Loop**: Setting `requestedOrientation` di dalam `onCreate` memicu aktivitas dibuat ulang jika orientasi saat ini berbeda. Hal ini menyebabkan inisialisasi kamera terjadi dua kali berturut-turut dalam waktu singkat, yang memicu konflik di level Native.
3.  **Async Cleanup**: Panggilan `closeCamera()` di library bersifat asinkronus. Jika kita langsung memanggil `destroy()` setelahnya, sumber daya Native mungkin dihapus sebelum thread kamera benar-benar berhenti.

## Perubahan yang Diusulkan

### 1. Perbaikan Inisialisasi View (`UfcCameraFragment.kt`)
- Memastikan `previewView` hanya dibuat satu kali dan disimpan dengan benar.
- Menghindari pembuatan objek view baru di dalam getter `getCameraView()` yang bisa dipanggil berkali-kali oleh library.

### 2. Penanganan Orientasi yang Aman (`MainActivity.kt`)
- Menghapus paksaan orientasi di `onCreate`. Sebagai gantinya, biarkan sistem menangani orientasi atau gunakan tombol Rotate tanpa memicu `recreate()` yang agresif jika tidak perlu.
- Mengunci orientasi di `AndroidManifest.xml` agar lebih stabil secara default.

### 3. Sinkronisasi Cleanup (`UfcCameraFragment.kt`)
- Menambahkan logika pembersihan yang lebih hati-hati di `onDestroy`.
- Memberikan jeda sangat singkat atau memastikan `unRegisterMultiCamera` dipanggil di waktu yang tepat.
- Menonaktifkan **Audio Monitor** secara default untuk mengurangi risiko konflik hardware USB.

### 4. Downgrade Compile SDK (Opsional tapi Disarankan)
- Menurunkan `compileSdk` dan `targetSdk` ke **34 (Android 14)** untuk meningkatkan stabilitas dengan library NDK yang sudah agak lama, karena SDK 36 memiliki pengecekan keamanan (*Fortify*) yang sangat ketat yang sering memicu `SIGABRT` pada kode Native lama.

## Rincian File yang Diubah

#### [MODIFY] [UfcCameraFragment.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/UfcCameraFragment.kt)
#### [MODIFY] [MainActivity.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/MainActivity.kt)
#### [MODIFY] [build.gradle.kts](file:///F:/coding/UFC-USBframeCapture-/android/app/build.gradle.kts)
#### [MODIFY] [AndroidManifest.xml](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/AndroidManifest.xml)

## Rencana Verifikasi

1.  Buka aplikasi, biarkan preview berjalan.
2.  Ganti-ganti orientasi dengan tombol Rotate. Pastikan tidak terjadi crash.
3.  Cabut dan colok kembali capture card saat aplikasi menyala.
4.  Cek logcat untuk memastikan tidak ada pesan `destroyed mutex` lagi.
