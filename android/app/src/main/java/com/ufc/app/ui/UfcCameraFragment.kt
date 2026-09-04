package com.ufc.app.ui

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
            .setAudioSource(CameraRequest.AudioSource.NONE) // Matikan audio total untuk hemat daya
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
                StatusRepository.update { it.copy(connected = true) }
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

    override fun initData() {
        super.initData()
        setEncodeDataCallBack(object : IEncodeDataCallBack {
            override fun onEncodeData(
                type: IEncodeDataCallBack.DataType,
                buffer: ByteBuffer,
                offset: Int,
                size: Int,
                timestamp: Long
            ) {
                if (isClosing) return
                
                // RootEncoder butuh timestamp dalam Microseconds (Us)
                val timestampUs = timestamp * 1000

                when (type) {
                    IEncodeDataCallBack.DataType.H264_SPS -> {
                        extractSpsPps(buffer, offset, size)
                    }
                    IEncodeDataCallBack.DataType.H264_KEY -> {
                        rtmpPusher?.onVideoData(buffer, offset, size, timestampUs, true)
                    }
                    IEncodeDataCallBack.DataType.H264 -> {
                        rtmpPusher?.onVideoData(buffer, offset, size, timestampUs, false)
                    }
                    IEncodeDataCallBack.DataType.AAC -> {
                        rtmpPusher?.onAudioData(buffer, offset, size, timestampUs)
                    }
                }
            }
        })
    }

    /**
     * Memisahkan SPS dan PPS dari buffer gabungan library AUSBC.
     */
    private fun extractSpsPps(buffer: ByteBuffer, offset: Int, size: Int) {
        // Simpan posisi asli
        val originalPos = buffer.position()
        val originalLimit = buffer.limit()

        try {
            buffer.position(offset)
            buffer.limit(offset + size)
            
            val data = ByteArray(size)
            buffer.get(data)
            
            // Cari start code 00 00 00 01 untuk memisahkan SPS dan PPS
            var ppsIndex = -1
            for (i in 4 until size - 4) {
                if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && 
                    data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                    ppsIndex = i
                    break
                }
            }
            
            if (ppsIndex != -1) {
                val sps = ByteBuffer.wrap(data, 0, ppsIndex)
                val pps = ByteBuffer.wrap(data, ppsIndex, size - ppsIndex)
                rtmpPusher?.setVideoMetadata(sps, pps)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            buffer.position(originalPos)
            buffer.limit(originalLimit)
        }
    }

    fun startEncoding() {
        captureStreamStart()
    }

    fun stopEncoding() {
        captureStreamStop()
    }

    fun showDeviceListDialog() {
        // Pastikan kamera ditutup dulu sebelum ganti perangkat untuk hindari Bad FD
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
        // Pembersihan manual yang lebih aman untuk mencegah Native Crash
        stopPlayMic()
        captureStreamStop()
        
        // Beri delay sangat singkat agar thread library menyelesaikan loop terakhirnya
        container?.postDelayed({
            unRegisterMultiCamera()
        }, 200)
        
        previewView = null
        container = null
        super.onDestroyView()
    }
}
