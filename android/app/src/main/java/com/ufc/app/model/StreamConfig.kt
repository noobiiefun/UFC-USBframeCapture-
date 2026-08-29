package com.ufc.app.model

import android.content.Context

/**
 * Pengaturan persistensi untuk stream.
 */
class StreamConfig(context: Context) {
    private val prefs = context.getSharedPreferences("ufc_prefs", Context.MODE_PRIVATE)

    var rtmpUrl: String
        get() = prefs.getString("rtmp_url", "rtmp://a.rtmp.youtube.com/live2/") ?: ""
        set(value) = prefs.edit().putString("rtmp_url", value).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", "") ?: ""
        set(value) = prefs.edit().putString("stream_key", value).apply()

    var resolutionWidth: Int
        get() = prefs.getInt("res_w", 1280)
        set(value) = prefs.edit().putInt("res_w", value).apply()

    var resolutionHeight: Int
        get() = prefs.getInt("res_h", 720)
        set(value) = prefs.edit().putInt("res_h", value).apply()

    var fps: Int
        get() = prefs.getInt("fps", 30)
        set(value) = prefs.edit().putInt("fps", value).apply()

    var bitrateKbps: Int
        get() = prefs.getInt("bitrate", 2500)
        set(value) = prefs.edit().putInt("bitrate", value).apply()

    var isPortrait: Boolean
        get() = prefs.getBoolean("is_portrait", false)
        set(value) = prefs.edit().putBoolean("is_portrait", value).apply()

    val fullUrl: String get() = "$rtmpUrl$streamKey"
}
