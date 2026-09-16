# Rencana Perbaikan: Koneksi YouTube Stabil dan Pemulihan Suara Capture Card

Rencana ini bertujuan untuk memperbaiki masalah "Broken Pipe" (aliran video macet) dan mengembalikan suara asli dari capture card (HDMI) Anda, serta menghentikan error `ioctl` secara total.

## Analisis Masalah Mendalam

1.  **Bug Aliran Data (Penyebab Broken Pipe)**: Saya menemukan bug kritis di mana data video "terkuras" habis sebelum sempat dikirim ke YouTube. Aplikasi membaca data untuk mencari identitas video (SPS/PPS), tapi lupa "mengisi ulang" atau mengembalikan penunjuk data ke awal. Akibatnya, YouTube menerima data kosong dan memutuskan koneksi.
2.  **Ketidakcocokan Metadata**: YouTube memerlukan data SPS/PPS tanpa kode awalan (start code). Saat ini, aplikasi mengirimkan metadata mentah yang masih mengandung sampah kode sistem, sehingga server YouTube menolak handshake.
3.  **Masalah Suara Perangkat**: Anda ingin suara asli dari capture card. Masalah `libUACAudio.so` sebelumnya terjadi karena library mencoba memuat driver 32-bit di sistem 64-bit. Saya akan mencoba cara baru yang lebih stabil untuk memanggil suara USB ini.
4.  **Error `ioctl` Persistent**: Masalah ini tetap ada karena kita menggunakan memori sistem langsung (ION) yang sangat sensitif. Saya akan mengalihkan sistem salin data menggunakan `ByteArray` murni (memori Java), yang jauh lebih aman bagi driver MediaTek/Xiaomi.

## Perubahan yang Diusulkan

### 1. Perbaikan Aliran Video (`UfcCameraFragment.kt`)
- **Fix Data Pointer**: Memastikan penunjuk memori (`position`) dikembalikan ke nol setiap kali data akan dikirim ke mesin streaming.
- **Pure ByteArray Copy**: Menggunakan `ByteArray` untuk menyalin gambar. Ini akan menghilangkan error `Bad file descriptor` karena tidak lagi menyentuh memori sistem ION secara tidak sah.
- **SPS/PPS Clean Up**: Memotong kode awalan `00 00 00 01` dari metadata video sebelum dikirim ke YouTube.

### 2. Pemulihan Suara Capture Card (`UfcCameraFragment.kt` & `RtmpPusher.kt`)
- **Enable USB Audio**: Mengaktifkan kembali `SOURCE_DEV_MIC` di kamera.
- **Synchronized Audio**: Meneruskan suara asli capture card ke YouTube menggunakan jam sinkronisasi yang sama dengan video agar suara tidak telat.
- **Fail-safe Audio**: Jika suara USB gagal (crash), aplikasi akan otomatis beralih ke Mic HP tanpa mematikan stream.

### 3. Stabilisasi RTMP (`RtmpPusher.kt`)
- **Handshake Guard**: Menunggu metadata video yang benar-benar bersih sebelum memulai koneksi.
- **Unified Timestamp**: Tetap menggunakan sistem jam tunggal untuk menjamin YouTube tidak memutus koneksi.

## Rincian File yang Diubah

#### [MODIFY] [UfcCameraFragment.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/ui/UfcCameraFragment.kt)
#### [MODIFY] [RtmpPusher.kt](file:///F:/coding/UFC-USBframeCapture-/android/app/src/main/java/com/ufc/app/stream/RtmpPusher.kt)

## Rencana Verifikasi
- Klik **USB** -> Gambar capture card harus muncul lancar tanpa error `ioctl`.
- Klik **Start Live**. Tunggu indikator **YT: LIVE**.
- Cek YouTube: Gambar harus muncul lancar dan suara harus terdengar (asli dari perangkat HDMI Anda).
