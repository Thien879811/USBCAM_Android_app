package com.example.usbcam

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.example.usbcam.databinding.FragmentUvcBinding
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.IAspectRatio
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import java.util.concurrent.ArrayBlockingQueue

class DemoFragment : CameraFragment(), IPreviewDataCallBack {
    private var mViewBinding: FragmentUvcBinding? = null
    
    // UI References
    private var tvState: android.widget.TextView? = null
    private var pbMotion: android.widget.ProgressBar? = null
    private var tvBarcode: android.widget.TextView? = null
    private var tvPO: android.widget.TextView? = null
    private var tvCount: android.widget.TextView? = null
    private var ivRoiDebug: ImageView? = null

    // Logic Processor
    private val boxProcessor = BoxProcessor()
    
    // OpenCV Mats
    private var mRgba: Mat? = null
    private var mYuvMat: Mat? = null
    
    // Threading
    private val frameQueue = ArrayBlockingQueue<ByteArray>(1) 
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    @Volatile private var isScanningBarcode = false
    @Volatile private var isScanningPO = false

    private var toneGen: android.media.ToneGenerator? = null
    private var vibrator: android.os.Vibrator? = null
    private var lastState = AppState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        }
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (mViewBinding == null) {
            mViewBinding = FragmentUvcBinding.inflate(inflater, container, false)
        }
        return mViewBinding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvState = view.findViewById(R.id.tvState)
        pbMotion = view.findViewById(R.id.pbMotion)
        tvBarcode = view.findViewById(R.id.tvBarcode)
        tvPO = view.findViewById(R.id.tvPO)
        tvCount = view.findViewById(R.id.tvCount)
        ivRoiDebug = view.findViewById(R.id.iv_roi_debug)
    }

    override fun getCameraView(): IAspectRatio? = mViewBinding?.tvCameraRender
    override fun getCameraViewContainer(): ViewGroup? = null
    override fun getGravity(): Int = Gravity.TOP

    override fun onCameraState(self: com.jiangdg.ausbc.MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                addPreviewDataCallBack(this)
                startProcessingThread()
            }
            ICameraStateCallBack.State.CLOSED -> {
                removePreviewDataCallBack(this)
                stopProcessingThread()
            }
            else -> {}
        }
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG) 
            .setRawPreviewData(true)
            .create()
    }

    override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
        if (data == null) return
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            mYuvMat = Mat(frameHeight + frameHeight / 2, frameWidth, CvType.CV_8UC1)
            mRgba = Mat()
        }
        frameQueue.offer(data)
    }

    private fun startProcessingThread() {
        stopProcessingThread()
        isProcessingThreadRunning = true
        processingThread = Thread {
            while (isProcessingThreadRunning) {
                try {
                    val data = frameQueue.take()
                    processFrame(data)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { start() }
    }

    private fun stopProcessingThread() {
        isProcessingThreadRunning = false
        processingThread?.interrupt()
        processingThread = null
        frameQueue.clear()
        mYuvMat?.release()
        mRgba?.release()
    }

    private fun processFrame(data: ByteArray) {
        var decoded = Mat()
        try {
            val buf = MatOfByte(*data)
            decoded = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
            buf.release()
            if (!decoded.empty()) {
                Imgproc.cvtColor(decoded, mRgba, Imgproc.COLOR_BGR2RGBA)
            } else {
                mYuvMat?.put(0, 0, data)
                Imgproc.cvtColor(mYuvMat, mRgba, Imgproc.COLOR_YUV2RGBA_NV21)
            }
        } catch (e: Exception) { return }
        decoded.release()

        if (mRgba == null || mRgba!!.empty()) return

        // Gray for processing
        val gray = Mat()
        Imgproc.cvtColor(mRgba, gray, Imgproc.COLOR_RGBA2GRAY)

        // --- CORE KINEMATIC UPDATE ---
        boxProcessor.updateLogic(gray)

        // Convert for UI
        val bmp = convertMatToBitmap(mRgba!!)
        
        if (bmp != null) {
            // Visual Debugging (Vẽ chồng lên ảnh trước khi quét)
            drawTrackingOverlay(bmp)

            val currentState = boxProcessor.currentState
            when (currentState) {
                AppState.IDLE -> {
                    if (!isScanningBarcode) {
                        isScanningBarcode = true
                        val grayClone = gray.clone()
                        scanBarcode(bmp, grayClone)
                    }
                }
                AppState.PROCESSING -> {
                    // Dùng Safe Getter
                    boxProcessor.getSafeTrackingRect()?.let { rectF ->
                         if (!isScanningPO) {
                             isScanningPO = true
                             val cropRect = android.graphics.Rect()
                             rectF.round(cropRect)
                             scanPOInRegion(bmp, cropRect)
                         }
                    }
                }
                else -> {}
            }
        }

        updateUI()
        gray.release()
    }
    
    // --- VISUAL DEBUGGING ---
    private fun drawTrackingOverlay(bmp: Bitmap) {
        val canvas = Canvas(bmp)
        val paintBox = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val paintPoint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            strokeWidth = 8f
        }

        // 1. Vẽ Box (Sử dụng Safe Getter)
        val rectF = boxProcessor.getSafeTrackingRect()
        if (rectF != null) {
            canvas.drawRect(rectF, paintBox)
        }
        
        // 2. Vẽ các điểm Features (Để debug xem nó bám vào đâu)
        val points = boxProcessor.getSafeDebugPoints()
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 6f, paintPoint)
        }
    }

    private fun scanBarcode(bitmap: Bitmap, grayMatClone: Mat) {
        val image = InputImage.fromBitmap(bitmap, 0)
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull()
                if (barcode != null) {
                    val raw = barcode.rawValue
                    val box = barcode.boundingBox 
                    if (raw != null && box != null) {
                        boxProcessor.onBarcodeDetected(raw, box, grayMatClone)
                    }
                }
            }
            .addOnCompleteListener {
                grayMatClone.release()
                isScanningBarcode = false
            }
            .addOnFailureListener {
                 grayMatClone.release()
                 isScanningBarcode = false
            }
    }

    private fun scanPOInRegion(fullBitmap: Bitmap, anchorRect: android.graphics.Rect) {
        val gap = 5
        val roiX = Math.max(0, anchorRect.left - 20)
        val roiY = Math.max(0, anchorRect.bottom + gap)
        val roiW = Math.min(fullBitmap.width - roiX, anchorRect.width() + 100)
        val roiH = Math.min(fullBitmap.height - roiY, 150)
        
        if (roiW > 10 && roiH > 10) {
            try {
                val poBitmap = Bitmap.createBitmap(fullBitmap, roiX, roiY, roiW, roiH)
                activity?.runOnUiThread {
                    ivRoiDebug?.setImageBitmap(poBitmap)
                    ivRoiDebug?.visibility = View.VISIBLE
                }

                val image = InputImage.fromBitmap(poBitmap, 0)
                val options = TextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options).process(image)
                    .addOnSuccessListener { visionText ->
                        processOcrResult(visionText.text)
                    }
                    .addOnCompleteListener {
                         isScanningPO = false
                    }
            } catch (e: Exception) {
                isScanningPO = false
            }
        } else {
            isScanningPO = false
        }
    }

    private fun processOcrResult(text: String) {
        val lines = text.split("\n", " ")
        for (line in lines) {
            val clean = line.trim()
            if (clean.length >= Config.MIN_PO_LENGTH && clean.all { it.isDigit() }) {
                boxProcessor.addPO(clean)
                return 
            }
        }
    }

    private fun updateUI() {
        activity?.runOnUiThread {
            val currentState = boxProcessor.currentState
            
            if (currentState != lastState) {
                handleStateFeedback(currentState)
                lastState = currentState
            }

            tvState?.text = "STATUS: $currentState"
            tvBarcode?.text = "BARCODE: ${boxProcessor.currentBarcode ?: "--"}"
            tvPO?.text = "PO: ${boxProcessor.currentPO ?: "--"}"
            tvCount?.text = "COUNT: ${boxProcessor.totalCount} [${boxProcessor.feedbackMessage}]"

            val color = when(currentState) {
                AppState.SUCCESS -> Color.GREEN
                AppState.ERROR_LOCKED -> Color.RED
                AppState.PROCESSING -> Color.YELLOW
                AppState.VALIDATING -> Color.BLUE
                else -> Color.WHITE
            }
            tvState?.setTextColor(color)
            
            if (currentState == AppState.PROCESSING || currentState == AppState.VALIDATING) {
                 pbMotion?.visibility = View.VISIBLE
            } else {
                 pbMotion?.visibility = View.INVISIBLE
            }
        }
    }
    
    private fun handleStateFeedback(newState: AppState) {
        if (toneGen == null) toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
        if (vibrator == null) vibrator = activity?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator?
        
        when (newState) {
            AppState.SUCCESS -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
            AppState.ERROR_LOCKED -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
            AppState.PROCESSING -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 50)
            }
            else -> {}
        }
    }

    private fun convertMatToBitmap(mat: Mat): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bmp)
            bmp
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingThread()
        toneGen?.release()
    }

    companion object {
        private const val TAG = "BoxScanner"
    }
}