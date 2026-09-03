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

/**
 * Fragment inti yang menangani capture UVC.
 */
class UfcCameraFragment : CameraFragment() {

    private var previewView: AspectRatioTextureView? = null
    private var container: FrameLayout? = null
    private var cachedData: ByteArray? = null

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
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
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
                
                val config = StreamConfig(requireContext())
                if (config.monitorAudio) {
                    // Beri sedikit jeda agar tidak crash saat inisialisasi UAC
                    container?.postDelayed({
                        if (isFragmentAttached()) {
                            startPlayMic(null)
                        }
                    }, 500)
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

    override fun initData() {
        super.initData()
        setEncodeDataCallBack(object : IEncodeDataCallBack {
            override fun onEncodeData(
                type: IEncodeDataCallBack.DataType,
                buffer: java.nio.ByteBuffer,
                offset: Int,
                size: Int,
                timestamp: Long
            ) {
                val typeInt = when (type) {
                    IEncodeDataCallBack.DataType.AAC -> 0
                    else -> 1
                }
                
                // Reuse buffer untuk menghemat memori (Anti-Lag/Anti-Crash)
                if (cachedData == null || cachedData!!.size != size) {
                    cachedData = ByteArray(size)
                }
                
                try {
                    buffer.get(cachedData!!, offset, size)
                    rtmpPusher?.onEncodedData(typeInt, cachedData!!, size, timestamp)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    fun startEncoding() {
        captureStreamStart()
    }

    fun stopEncoding() {
        captureStreamStop()
    }

    fun showDeviceListDialog() {
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
        // Pembersihan manual yang lebih aman untuk mencegah Native Crash
        stopPlayMic()
        captureStreamStop()
        unRegisterMultiCamera()
        previewView = null
        container = null
        super.onDestroyView()
    }
}
