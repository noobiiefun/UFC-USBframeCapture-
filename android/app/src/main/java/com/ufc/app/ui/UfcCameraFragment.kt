package com.ufc.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraEncodeData
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.ufc.app.StatusRepository
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
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .create()
    }

    override fun onCameraState(
        self: com.jiangdg.ausbc.callback.ICameraStateCallBack.ICamera,
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

    override fun initEncodeDataCallBack() {
        addEncodeDataCallBack(object : IEncodeDataCallBack {
            override fun onEncodeData(data: CameraEncodeData) {
                rtmpPusher?.onEncodedData(data)
            }
        })
    }
}
