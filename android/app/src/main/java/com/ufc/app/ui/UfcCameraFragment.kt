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

    private lateinit var previewView: AspectRatioTextureView
    private lateinit var container: FrameLayout

    var rtmpPusher: RtmpPusher? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = FrameLayout(requireContext())
        previewView = AspectRatioTextureView(requireContext())
        this.container = FrameLayout(requireContext())
        this.container.addView(previewView)
        root.addView(this.container)
        return root
    }

    override fun getCameraView(): IAspectRatio = previewView

    override fun getCameraViewContainer(): ViewGroup = container

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
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
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
                captureStreamStart()
            }
            ICameraStateCallBack.State.CLOSED -> {
                StatusRepository.update { it.copy(connected = false) }
                captureStreamStop()
            }
            ICameraStateCallBack.State.ERROR -> {
                StatusRepository.update { it.copy(connected = false) }
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
                // Konversi DataType enum ke Int untuk RtmpPusher
                // Berdasarkan AusbcPusher 3.6.0: 0 untuk Audio, 1 untuk Video
                val typeInt = when (type) {
                    IEncodeDataCallBack.DataType.AAC -> 0
                    else -> 1 // H264, H264_KEY, H264_SPS
                }
                val data = ByteArray(size)
                buffer.get(data, offset, size)
                rtmpPusher?.onEncodedData(typeInt, data, size, timestamp)
            }
        })
    }
}
