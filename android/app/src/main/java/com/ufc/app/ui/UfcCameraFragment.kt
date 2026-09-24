package com.ufc.app.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.ufc.app.StatusRepository
import com.ufc.app.model.StreamConfig
import com.ufc.app.stream.RtmpPusher
import java.nio.ByteBuffer

/**
 * Fragment inti yang menangani capture UVC.
 */
class UfcCameraFragment : CameraFragment() {

    private var previewView: AspectRatioTextureView? = null
    private var container: FrameLayout? = null
    private var videoBufferCopy: ByteBuffer? = null
    private var audioBufferCopy: ByteBuffer? = null
    private var isClosing = false

    var rtmpPusher: RtmpPusher? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = FrameLayout(requireContext())
        this.container = FrameLayout(requireContext())
        root.addView(this.container)
        return root
    }

    override fun getCameraView(): IAspectRatio? {
        if (previewView == null) {
            previewView = AspectRatioTextureView(requireContext())
        }
        return previewView
    }

    override fun getCameraViewContainer(): ViewGroup? = container

    override fun onStart() {
        super.onStart()
        if (previewView?.isAvailable == true) {
            registerMultiCamera()
        }
    }

    override fun onStop() {
        super.onStop()
        unRegisterMultiCamera()
    }

    override fun getCameraRequest(): CameraRequest {
        val config = StreamConfig(requireContext())
        var width = config.resolutionWidth
        var height = config.resolutionHeight
        
        // Pastikan resolusi minimal 720p untuk landscape
        if (!config.isPortrait) {
            // Landscape mode: width harus >= 1280, height >= 720
            if (width < 1280 || height < 720) {
                width = 1280
                height = 720
            }
        } else {
            // Portrait mode: height harus >= 1280, width >= 720
            if (width < 720 || height < 1280) {
                width = 720
                height = 1280
            }
        }
        
        // Simpan konfigurasi yang sudah disesuaikan
        config.resolutionWidth = width
        config.resolutionHeight = height

        Log.i("UfcCamera", "Requesting camera resolution: ${width}x${height}, portrait=${config.isPortrait}")

        return CameraRequest.Builder()
            .setPreviewWidth(width)
            .setPreviewHeight(height)
            .setRenderMode(if (config.useOpengl) CameraRequest.RenderMode.OPENGL else CameraRequest.RenderMode.NORMAL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            .setAudioSource(
                // === AUDIO DARI CAPTURE CARD (UAC) ===
                // SOURCE_DEV_MIC mengambil suara HDMI dari PC lewat interface
                // USB Audio Class capture card, sehingga suara PC ikut masuk
                // ke aplikasi dan menjadi bagian dari output stream.
                // Bisa dimatikan lewat Settings ("Audio dari Capture Card")
                // kalau device tertentu memicu crash native di libUACAudio.so
                // (kasus lama: SIGSEGV di USBAudio::interface_claim_if untuk
                // dongle dengan descriptor USB tidak standar -- fallback ke
                // mic internal HP jika switch dimatikan).
                if (config.useDeviceMic) CameraRequest.AudioSource.SOURCE_DEV_MIC
                else CameraRequest.AudioSource.SOURCE_SYS_MIC
            )
            .setPreviewFormat(if (config.useMjpeg) CameraRequest.PreviewFormat.FORMAT_MJPEG else CameraRequest.PreviewFormat.FORMAT_YUYV)
            .setAspectRatioShow(true)
            .create()
    }

    override fun onCameraState(
        self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.i("UfcCamera", "Camera Opened - Setting up encoding callbacks")
                StatusRepository.update { it.copy(connected = true) }
                setupEncodingCallbacks()

                val config = StreamConfig(requireContext())
                if (config.monitorAudio) {
                    Log.i("UfcCamera", "Mulai audio monitoring lokal ke speaker HP")
                    startPlayMic()
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                StatusRepository.update { it.copy(connected = false) }
                captureStreamStop()
                stopPlayMic()
            }
            ICameraStateCallBack.State.ERROR -> {
                StatusRepository.update { it.copy(connected = false) }
                if (isFragmentAttached()) {
                    android.widget.Toast.makeText(requireContext(), "Camera Error: $msg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupEncodingCallbacks() {
        setEncodeDataCallBack(object : IEncodeDataCallBack {
            override fun onEncodeData(
                type: IEncodeDataCallBack.DataType,
                buffer: ByteBuffer,
                offset: Int,
                size: Int,
                timestamp: Long
            ) {
                if (isClosing) return

                val isAudio = type == IEncodeDataCallBack.DataType.AAC

                try {
                    // Deep Copy data ke buffer mandiri agar tidak kena Bad FD (ioctl error).
                    // Video dan audio pakai buffer terpisah supaya tidak saling timpa
                    // kalau kedua callback ini datang berdekatan.
                    val target: ByteBuffer = if (isAudio) {
                        if (audioBufferCopy == null || audioBufferCopy!!.capacity() < size) {
                            audioBufferCopy = ByteBuffer.allocateDirect(size * 2)
                        }
                        audioBufferCopy!!
                    } else {
                        if (videoBufferCopy == null || videoBufferCopy!!.capacity() < size) {
                            videoBufferCopy = ByteBuffer.allocateDirect(size * 2)
                        }
                        videoBufferCopy!!
                    }

                    target.clear()
                    val originalPos = buffer.position()
                    val originalLimit = buffer.limit()

                    buffer.position(offset)
                    buffer.limit(offset + size)
                    target.put(buffer)
                    target.flip()

                    // Kembalikan posisi asli buffer library
                    buffer.position(originalPos)
                    buffer.limit(originalLimit)

                    // RootEncoder butuh timestamp dalam Microseconds (Us)
                    val timestampUs = timestamp * 1000

                    when (type) {
                        IEncodeDataCallBack.DataType.H264_SPS -> {
                            Log.v("UfcCamera", "H264_SPS received")
                            extractSpsPps(target, size)
                        }
                        IEncodeDataCallBack.DataType.H264_KEY -> {
                            rtmpPusher?.onVideoData(target, 0, size, timestampUs, true)
                        }
                        IEncodeDataCallBack.DataType.H264 -> {
                            rtmpPusher?.onVideoData(target, 0, size, timestampUs, false)
                        }
                        IEncodeDataCallBack.DataType.AAC -> {
                            // Audio dari mic internal HP (lihat catatan SOURCE_SYS_MIC
                            // di getCameraRequest()), diteruskan langsung sebagai raw AAC
                            // (tanpa ADTS, sesuai kontrak IEncodeDataCallBack).
                            rtmpPusher?.onDeviceAudioData(target, size, timestampUs)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UfcCamera", "Gagal salin buffer ${if (isAudio) "audio" else "video"}: ${e.message}")
                }
            }
        })
    }

    override fun initData() {
        super.initData()
    }

    /**
     * Memisahkan SPS dan PPS dari buffer yang sudah disalin.
     * Mendukung start code 3-byte (00 00 01) dan 4-byte (00 00 00 01) untuk
     * kompatibilitas dengan MediaTek GPU (Helio G36 / Xiaomi Redmi A3) & Snapdragon.
     */
    private fun extractSpsPps(buffer: ByteBuffer, size: Int) {
        try {
            val data = ByteArray(size)
            buffer.get(data)
            buffer.flip()

            val nalIndices = mutableListOf<Pair<Int, Int>>() // Pair(index, prefixLength)
            var i = 0
            while (i <= size - 3) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (i <= size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        nalIndices.add(Pair(i, 4))
                        i += 4
                        continue
                    } else if (data[i + 2] == 1.toByte()) {
                        nalIndices.add(Pair(i, 3))
                        i += 3
                        continue
                    }
                }
                i++
            }

            var spsStart = -1
            var spsEnd = -1
            var ppsStart = -1
            var ppsEnd = -1

            if (nalIndices.isNotEmpty()) {
                for (k in nalIndices.indices) {
                    val (start, prefixLen) = nalIndices[k]
                    val nalType = if (start + prefixLen < size) (data[start + prefixLen].toInt() and 0x1F) else -1
                    val end = if (k + 1 < nalIndices.size) nalIndices[k + 1].first else size
                    if (nalType == 7) { // SPS
                        spsStart = start
                        spsEnd = end
                    } else if (nalType == 8) { // PPS
                        ppsStart = start
                        ppsEnd = end
                    }
                }
            }

            if (spsStart != -1 && ppsStart != -1 && spsEnd > spsStart && ppsEnd > ppsStart) {
                val sps = ByteBuffer.wrap(data, spsStart, spsEnd - spsStart)
                val pps = ByteBuffer.wrap(data, ppsStart, ppsEnd - ppsStart)
                rtmpPusher?.setVideoMetadata(sps, pps)
            } else if (nalIndices.size >= 2) {
                val (firstIdx, _) = nalIndices[0]
                val (secondIdx, _) = nalIndices[1]
                val sps = ByteBuffer.wrap(data, firstIdx, secondIdx - firstIdx)
                val pps = ByteBuffer.wrap(data, secondIdx, size - secondIdx)
                rtmpPusher?.setVideoMetadata(sps, pps)
            }
        } catch (e: Exception) {
            Log.e("UfcCamera", "Gagal ekstraksi SPS/PPS: ${e.message}")
        }
    }

    data class AudioConfig(val sampleRate: Int, val isStereo: Boolean)

    private fun fetchAudioConfig(): AudioConfig? {
        val camera = getCurrentCamera() ?: return null
        var clazz: Class<*>? = camera.javaClass
        while (clazz != null && clazz != Any::class.java) {
            try {
                val method = clazz.getDeclaredMethod("getAudioStrategy")
                method.isAccessible = true
                val strategy = method.invoke(camera) as? IAudioStrategy
                if (strategy != null) {
                    val sr = strategy.getSampleRate()
                    val isStereo = strategy.getChannelCount() > 1
                    return AudioConfig(sr, isStereo)
                }
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            } catch (e: Exception) {
                Log.w("UfcCamera", "Gagal me-reflect getAudioStrategy: ${e.message}")
                break
            }
        }
        return null
    }

    fun startEncoding() {
        Log.i("UfcCamera", "startEncoding() requested")
        captureStreamStart()
        try {
            val audioCfg = fetchAudioConfig()
            if (audioCfg != null && audioCfg.sampleRate > 0) {
                rtmpPusher?.setAudioInfo(audioCfg.sampleRate, audioCfg.isStereo)
            }
        } catch (e: Throwable) {
            Log.w("UfcCamera", "Gagal baca audio config: ${e.message}")
        }
    }

    fun updateAudioMonitoring(enable: Boolean) {
        if (enable) {
            startPlayMic()
        } else {
            stopPlayMic()
        }
    }

    fun stopEncoding() {
        Log.i("UfcCamera", "stopEncoding() requested")
        captureStreamStop()
    }

    fun showDeviceListDialog() {
        closeCamera()
        val usbDevices = getDeviceList()
        if (usbDevices.isNullOrEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No USB devices found", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = usbDevices.map { 
            "${it.productName} (${it.deviceName})"
        }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Capture Card")
            .setItems(deviceNames) { _, which ->
                switchCamera(usbDevices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        isClosing = true
        stopPlayMic()
        captureStreamStop()
        container?.postDelayed({
            unRegisterMultiCamera()
        }, 200)
        
        previewView = null
        container = null
        super.onDestroyView()
    }
}
