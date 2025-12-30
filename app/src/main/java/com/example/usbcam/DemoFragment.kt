package com.example.usbcam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.render.env.RotateType
import com.example.usbcam.databinding.FragmentUvcBinding

class DemoFragment : CameraFragment() {
    private var mViewBinding: FragmentUvcBinding? = null

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (mViewBinding == null) {
            mViewBinding = FragmentUvcBinding.inflate(inflater, container, false)
        }
        return mViewBinding?.root
    }

    override fun getCameraView(): IAspectRatio? {
        return mViewBinding?.tvCameraRender
    }

    override fun getCameraViewContainer(): ViewGroup? {
        return mViewBinding?.container
    }

    override fun onCameraState(
        self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> handleCameraOpened()
            ICameraStateCallBack.State.CLOSED -> handleCameraClosed()
            ICameraStateCallBack.State.ERROR -> handleCameraError(msg)
        }
    }

    private fun handleCameraOpened() {
        // Handle camera opened
    }

    private fun handleCameraClosed() {
        // Handle camera closed
    }

    private fun handleCameraError(msg: String?) {
        // Handle camera error
    }

    override fun getGravity(): Int = Gravity.TOP
    
    // Optional: override getCameraRequest if needed
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }
}
