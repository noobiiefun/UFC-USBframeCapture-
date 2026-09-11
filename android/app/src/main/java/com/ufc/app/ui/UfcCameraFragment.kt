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
    private var videoDataCache: ByteArray? = null
    private var audioDataCache: ByteArray? = null
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

    override fun getCameraRequest(): CameraRequest {
        val config = StreamConfig(requireContext())
        var width = config.resolutionWidth
        var height = config.resolutionHeight
        
        if (config.isPortrait) {
            if (width > height) {
                val temp = width
                width = height
                height = temp
            }
        } else {
            if (height > width) {
                val temp = width
                width = height
                height = temp
            }
        }

        return CameraRequest.Builder()
            .setPreviewWidth(width)
            .setPreviewHeight(height)
            .setRenderMode(if (config.useOpengl) CameraRequest.RenderMode.OPENGL else CameraRequest.RenderMode.NORMAL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            // Coba ambil audio dari capture card, tetapi di RtmpPusher kita pakai Mic HP
            // sebagai fail-safe jika driver UAC bermasalah.
            .setAudioSource(CameraRequest.AudioSource.SOURCE_DEV_MIC)
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
                
                // Pakai ByteArray murni (Java Heap) agar tidak kena Bad FD (ioctl error)
                // Driver ION MediaTek sering crash kalau Direct ByteBuffer diakses sembarangan
                val cache: ByteArray = if (isAudio) {
                    if (audioDataCache == null || audioDataCache!!.size < size) audioDataCache = ByteArray(size * 2)
                    audioDataCache!!
                } else {
                    if (videoDataCache == null || videoDataCache!!.size < size) videoDataCache = ByteArray(size * 2)
                    videoDataCache!!
                }

                try {
                    val originalPos = buffer.position()
                    buffer.position(offset)
                    buffer.get(cache, 0, size)
                    buffer.position(originalPos)

                    // RootEncoder butuh timestamp dalam Microseconds (Us)
                    val timestampUs = timestamp * 1000

                    when (type) {
                        IEncodeDataCallBack.DataType.H264_SPS -> {
                            Log.v("UfcCamera", "H264_SPS received")
                            extractSpsPps(cache, size)
                        }
                        IEncodeDataCallBack.DataType.H264_KEY -> {
                            rtmpPusher?.onVideoData(ByteBuffer.wrap(cache, 0, size), 0, size, timestampUs, true)
                        }
                        IEncodeDataCallBack.DataType.H264 -> {
                            rtmpPusher?.onVideoData(ByteBuffer.wrap(cache, 0, size), 0, size, timestampUs, false)
                        }
                        IEncodeDataCallBack.DataType.AAC -> {
                            // Abaikan audio dari USB, kita pakai Mic HP via RtmpPusher
                            // karena driver USB Audio (UAC) sering crash atau missing .so
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UfcCamera", "Error copy buffer: ${e.message}")
                }
            }
        })
    }

    override fun initData() {
        super.initData()
    }

    /**
     * Memisahkan SPS dan PPS serta membersihkan start code untuk YouTube.
     */
    private fun extractSpsPps(data: ByteArray, size: Int) {
        try {
            var ppsIndex = -1
            for (i in 4 until size - 4) {
                if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && 
                    data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                    ppsIndex = i
                    break
                }
            }
            
            if (ppsIndex != -1) {
                // RootEncoder memerlukan SPS & PPS tanpa start code 00 00 00 01
                val sps = ByteBuffer.wrap(data, 4, ppsIndex - 4)
                val pps = ByteBuffer.wrap(data, ppsIndex + 4, size - ppsIndex - 4)
                rtmpPusher?.setVideoMetadata(sps, pps)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startEncoding() {
        Log.i("UfcCamera", "startEncoding() requested")
        captureStreamStart()
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
