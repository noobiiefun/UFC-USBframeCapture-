package com.ufc.app.model

import android.content.Context

/**
 * Pilihan sumber audio untuk stream/preview.
 */
enum class AudioMode {
    DEV_MIC,  // capture card (UAC) — suara PC dari HDMI ikut tertangkap
    SYS_MIC,  // mic internal HP
    NONE      // tanpa audio sama sekali
}

/**
 * Pengaturan persistensi untuk stream.
 */
class StreamConfig(context: Context) {
    private val prefs = context.getSharedPreferences("ufc_prefs", Context.MODE_PRIVATE)

    /**
     * Mode audio terpilih (pengganti switch lama). Nilai lama (boolean
     * use_device_mic) otomatis dimigrasikan supaya setting user tidak hilang.
     */
    var audioSourceMode: AudioMode
        get() {
            val stored = prefs.getString("audio_mode", null)
            if (stored != null) return runCatching { AudioMode.valueOf(stored) }.getOrDefault(AudioMode.DEV_MIC)
            // migrasi dari versi lama (switch ON/OFF)
            return if (prefs.getBoolean("use_device_mic", true)) AudioMode.DEV_MIC else AudioMode.SYS_MIC
        }
        set(value) = prefs.edit().putString("audio_mode", value.name).apply()

    /** Compat lama: true kalau mode = capture card. */
    var useDeviceMic: Boolean
        get() = audioSourceMode == AudioMode.DEV_MIC
        set(value) {
            audioSourceMode = if (value) AudioMode.DEV_MIC
            else if (audioSourceMode == AudioMode.DEV_MIC) AudioMode.SYS_MIC else audioSourceMode
        }

    var rtmpUrl: String
        // trim() mencegah spasi/newline nyasar (mis. dari copy-paste) yang bikin
        // RTMP server nolak koneksi instan ("End of stream")
        get() = prefs.getString("rtmp_url", "rtmp://a.rtmp.youtube.com/live2/") ?: ""
        set(value) = prefs.edit().putString("rtmp_url", value.trim()).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", "") ?: ""
        set(value) = prefs.edit().putString("stream_key", value.trim()).apply()

    // Default dikembalikan ke 720p (1280x720) sesuai target performa di README.
    var resolutionWidth: Int
        get() = prefs.getInt("res_w", 1280)
        set(value) = prefs.edit().putInt("res_w", value).apply()

    var resolutionHeight: Int
        get() = prefs.getInt("res_h", 720)
        set(value) = prefs.edit().putInt("res_h", value).apply()

    // FPS encoding/stream (minimal 25). Untuk preview saja (tanpa streaming),
    // FPS bisa dipilih bebas lewat spinner di Settings (mis. 15/20/25/30).
    var fps: Int
        get() = prefs.getInt("fps", 30).coerceAtLeast(25)
        set(value) = prefs.edit().putInt("fps", value.coerceAtLeast(25)).apply()

    // FPS yang diminta ke capture card (UVC) khusus untuk tampilan preview.
    // Bebas pilih (termasuk < 30) — ini yang menentukan kelancaran gambar
    // di layar HP; hemat CPU di HP entry-level kalau diturunkan.
    var previewFps: Int
        get() = prefs.getInt("preview_fps", 30)
        set(value) = prefs.edit().putInt("preview_fps", value).apply()

    var bitrateKbps: Int
        get() = prefs.getInt("bitrate", 2500)
        set(value) = prefs.edit().putInt("bitrate", value).apply()

    var isPortrait: Boolean
        get() = prefs.getBoolean("is_portrait", false)
        set(value) = prefs.edit().putBoolean("is_portrait", value).apply()

    // Format pixel yang diminta ke capture card: true = MJPG (cepat, 30fps stabil),
    // false = YUYV/UYVY (mentah tapi berat di USB, biasanya max ~10-15fps di 1080p)
    var useMjpeg: Boolean
        get() = prefs.getBoolean("use_mjpeg", true)
        set(value) = prefs.edit().putBoolean("use_mjpeg", value).apply()

    // Audio dari capture card (UAC) yang masuk ke aplikasi. Default ON supaya
    // suara PC ikut tertangkap; switch di Settings bisa mematikan kalau
    // capture card tertentu bikin crash native di libUACAudio.so.
    var useDeviceMic: Boolean
        get() = prefs.getBoolean("use_device_mic", true)
        set(value) = prefs.edit().putBoolean("use_device_mic", value).apply()

    var monitorAudio: Boolean
        get() = prefs.getBoolean("monitor_audio", true)
        set(value) = prefs.edit().putBoolean("monitor_audio", value).apply()

    var useOpengl: Boolean
        get() = prefs.getBoolean("use_opengl", false)
        set(value) = prefs.edit().putBoolean("use_opengl", value).apply()

    // Tampilan preview: true = stretch/penuhi layar (gambar bisa gepeng),
    // false = "biasa" yaitu jaga rasio aspek (letterbox, tidak ada bagian terpotong).
    var stretchPreview: Boolean
        get() = prefs.getBoolean("stretch_preview", false)
        set(value) = prefs.edit().putBoolean("stretch_preview", value).apply()

    // Tampilkan penghitung FPS aktual di overlay saat preview/streaming.
    var showFpsOverlay: Boolean
        get() = prefs.getBoolean("show_fps_overlay", true)
        set(value) = prefs.edit().putBoolean("show_fps_overlay", value).apply()

    // PENTING: ini akar penyebab bug "End of stream" yang berulang.
    // Kalau rtmpUrl yang disimpan tidak diakhiri "/", hasil gabungan jadi
    // rusak, contoh: "rtmp://x.rtmp.youtube.com/live2" + "9hd7-..." menjadi
    // ".../live29hd7-..." (application name RTMP jadi salah total), dan
    // server YouTube langsung menutup koneksi begitu terima app name yang
    // tidak valid itu. Sekarang separator dipaksa ada di sini, bukan
    // mengandalkan input user selalu benar.
    val fullUrl: String
        get() {
            val base = if (rtmpUrl.endsWith("/")) rtmpUrl else "$rtmpUrl/"
            return "$base$streamKey"
        }
}
