package com.ufc.app.model

import org.json.JSONObject

/**
 * Snapshot status stream saat ini. Instance baru dibuat tiap ada update
 * (immutable), lalu dipublish lewat StateFlow di StatusRepository.
 */
data class StreamStatus(
    val connected: Boolean = false,          // capture card UVC aktif?
    val youtubeConnected: Boolean = false,    // RTMP ke YouTube tersambung?
    val resolution: String = "-",
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val uploadSpeedKbps: Int = 0,
    val downloadSpeedKbps: Int = 0,
    val droppedFrames: Long = 0,
    val uptimeSec: Long = 0
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("connected", connected)
        obj.put("youtubeConnected", youtubeConnected)
        obj.put("resolution", resolution)
        obj.put("fps", fps)
        obj.put("bitrateKbps", bitrateKbps)
        obj.put("uploadSpeedKbps", uploadSpeedKbps)
        obj.put("downloadSpeedKbps", downloadSpeedKbps)
        obj.put("droppedFrames", droppedFrames)
        obj.put("uptimeSec", uptimeSec)
        obj.put("timestamp", System.currentTimeMillis() / 1000)
        return obj.toString()
    }
}
