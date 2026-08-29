# Rencana Implementasi Splash Screen, Orientasi, dan Fix Force Close

Tugas ini bertujuan untuk menambahkan Splash Screen, fitur pemilihan orientasi livestreaming (Horizontal/Vertikal), dan memperbaiki penyebab crash (*Force Close*).

## User Review Required

> [!IMPORTANT]
> **Penyebab Force Close:** Crash terjadi karena fungsi `AusbcPusher` di library `3.6.0` yang kita gunakan masih berupa skeleton (belum ada implementasi RTMP aslinya/`TODO`). Saya akan memperbaiki kode agar tidak crash, namun untuk benar-benar melakukan *streaming* ke YouTube, kita memerlukan pustaka RTMP tambahan.

## Perubahan yang Diusulkan

### 1. Splash Screen & Icon
Saya akan menggunakan file `UFC.png` yang ada di root folder sebagai icon aplikasi dan logo splash screen.
- **Resources**: Membuat folder `res/drawable` dan `res/mipmap-xxxhdpi`.
- **Theme**: Menambahkan `Theme.App.Starting` di `themes.xml` untuk menampilkan logo saat aplikasi dibuka.

### 2. Orientasi Livestreaming
- **Settings**: Menambahkan opsi "Orientation" (Landscape/Portrait) di menu Pengaturan.
- **Logika**: Jika "Portrait" dipilih, resolusi akan otomatis ditukar (misal: 1280x720 menjadi 720x1280).
- **Manifest**: Mengunci `MainActivity` agar tetap responsif namun menghandle perubahan konfigurasi secara manual.

### 3. Perbaikan Force Close
- **`RtmpPusher.kt`**: Menghapus inisialisasi pusher yang belum terimplementasi di library untuk mencegah `NotImplementedError`. Saya akan menambahkan mekanisme *safe-call* dan peringatan jika fitur streaming dipicu tanpa mesin pusher yang valid.

## Rincian File yang Diubah

### Resources & UI
#### [NEW] [splash_background.xml](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/res/drawable/splash_background.xml)
#### [MODIFY] [activity_settings.xml](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/res/layout/activity_settings.xml)
#### [MODIFY] [themes.xml](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/res/values/themes.xml)

### Logika & Konfigurasi
#### [MODIFY] [StreamConfig.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/model/StreamConfig.kt)
#### [MODIFY] [SettingsActivity.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/SettingsActivity.kt)
#### [MODIFY] [RtmpPusher.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/stream/RtmpPusher.kt)
#### [MODIFY] [AndroidManifest.xml](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/AndroidManifest.xml)

## Rencana Verifikasi

### Manual
1.  Buka aplikasi dan pastikan Splash Screen muncul dengan logo UFC.
2.  Masuk ke Pengaturan, ubah orientasi ke Portrait, dan simpan.
3.  Klik "Start Stream" dan pastikan aplikasi tidak lagi Force Close.
4.  Cek logcat untuk memastikan resolusi yang dikirim sudah tertukar (720x1280).
