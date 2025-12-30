package com.example.usbcam

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.example.usbcam.databinding.FragmentUvcBinding
import com.example.usbcam.R
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import org.opencv.imgcodecs.Imgcodecs
import java.util.concurrent.ArrayBlockingQueue

class DemoFragment : CameraFragment(), IPreviewDataCallBack {
    private var mViewBinding: FragmentUvcBinding? = null
    // Native UI references
    private var tvState: android.widget.TextView? = null
    private var pbMotion: android.widget.ProgressBar? = null
    private var tvBarcode: android.widget.TextView? = null
    private var tvPO: android.widget.TextView? = null
    private var ivRoiDebug: ImageView? = null

    // --- Configuration ---
    // No specific processing size needed for basic pixel diff, uses full or stride
    // --- Box Processor ---

    // --- Box Processor ---
    private val boxProcessor = BoxProcessor()
    private var frameCount = 0

    // --- Reusable Mats ---
    private var mRgba: Mat? = null
    private var mYuvMat: Mat? = null
    
    // --- Motion Detection Data ---
    private var mLastFrameData: ByteArray? = null

    // --- Threading ---
    private val frameQueue = ArrayBlockingQueue<ByteArray>(1)
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
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
        // Bind native views manually or via viewBinding if IDs are added to binding class generated
        // Since we edited XML, they should be in binding if we rebuild, but findViewById is safe.
        tvState = view.findViewById(R.id.tvState)
        pbMotion = view.findViewById(R.id.pbMotion)
        tvBarcode = view.findViewById(R.id.tvBarcode)
        tvPO = view.findViewById(R.id.tvPO)
        // We kept iv_roi_debug in XML for PO debugging
        ivRoiDebug = view.findViewById(R.id.iv_roi_debug)
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
        Log.i(TAG, "Camera opened")
        addPreviewDataCallBack(this)
        startProcessingThread()
    }

    private fun handleCameraClosed() {
        Log.i(TAG, "Camera closed")
        removePreviewDataCallBack(this)
        stopProcessingThread()
    }

    private fun handleCameraError(msg: String?) {
        Log.e(TAG, "Camera error: $msg")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingThread()
    }

    override fun getGravity(): Int = Gravity.TOP
    
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG) // Request MJPEG
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(true) // Enable raw data callback
            .create()
    }

    override fun onPreviewData(
        data: ByteArray?,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        if (data == null) return
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            initMats()
        }
        // Offer frame to queue, drop if full to maintain realtime
        frameQueue.offer(data)
    }

    private fun initMats() {
        // Only RGBA mat needed for display/MLKit
        mYuvMat = Mat(frameHeight + frameHeight / 2, frameWidth, CvType.CV_8UC1)
        mRgba = Mat()
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
                } catch (e: Exception) {
                    Log.e(TAG, "Worker thread error", e)
                }
            }
        }.apply { start() }
    }

    private fun stopProcessingThread() {
        isProcessingThreadRunning = false
        processingThread?.interrupt()
        try { processingThread?.join(300) } catch (e: InterruptedException) { e.printStackTrace() }
        processingThread = null
        frameQueue.clear()
        mYuvMat?.release()
        mRgba?.release()
        mLastFrameData = null
    }

    private var avgVx = 0.0
    private var avgVy = 0.0

    private fun processFrame(data: ByteArray) {
        val size = data.size
        // Log Camera Data Retrieval
        if (frameCount % 30 == 0) Log.d(TAG, "DEBUG: [1] Received frame from Camera thread: size=$size, expected_nv21=${frameWidth * frameHeight * 3 / 2}")

        var decoded = Mat()
        var isMjPeg = false

        // Try MJPEG Decode
        try {
            val buf = MatOfByte(*data)
            decoded = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
            buf.release()
            
            if (!decoded.empty()) {
                isMjPeg = true
                if (frameCount % 30 == 0) Log.d(TAG, "DEBUG: [2] MJPEG Decode Success")
                // imdecode returns BGR, convert to RGBA
                Imgproc.cvtColor(decoded, mRgba, Imgproc.COLOR_BGR2RGBA)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG decode exception", e)
        }

        // If MJPEG failed, try NV21/YUV
        if (decoded.empty()) {
            val expectedSize = frameWidth * frameHeight * 3 / 2
            if (size == expectedSize) {
                if (frameCount % 30 == 0) Log.d(TAG, "DEBUG: [2] Processing as NV21 (Fallback)")
                
                // Ensure mYuvMat is ready
                if (mYuvMat == null || mYuvMat!!.cols() != frameWidth || mYuvMat!!.rows() != frameHeight + frameHeight / 2) {
                     mYuvMat = Mat(frameHeight + frameHeight / 2, frameWidth, CvType.CV_8UC1)
                }
                
                mYuvMat!!.put(0, 0, data)
                Imgproc.cvtColor(mYuvMat, mRgba, Imgproc.COLOR_YUV2RGBA_NV21)
            } else {
                Log.e(TAG, "DEBUG: [2] Failed to decode: Not MJPEG and size $size mismatches NV21 $expectedSize")
                return
            }
        }
        
        decoded.release()

        val gray = Mat()
        Imgproc.cvtColor(mRgba, gray, Imgproc.COLOR_RGBA2GRAY)
        
        frameCount++

        // 1. Motion Detection (Frame Differencing)
        // We use the raw Y data (start of NV21 buffer) for speed. 
        // No need to convert to grayscale Mat if we just want pixel diff.
        // NOTE: 'data' might be MJPEG or NV21. 
        // If MJPEG (isMjPeg=true), 'data' is compressed. We need the raw pixels.
        // We have 'mRgba'. We can get Grayscale from it, or if it was NV21 originally, we use 'data'.
        
        // Complex case: If MJPEG, we don't have raw Y buffer easily without converting mRgba -> Gray.
        // But the user asked for "direct frame difference calculation on a ByteArray (NV21)".
        // If the camera is MJPEG, we are kind of stuck unless we convert RGBA back to Gray.
        
        var yBuffer: ByteArray? = null
        if (!isMjPeg) {
            // It's NV21. The first w*h bytes are Y.
            yBuffer = data
        } else {
            // MJPEG case: we must convert mRgba to Gray for diffing, or just use Green channel?
            // Let's create a temporary gray mat -> byte array.
            // Or simpler: convert mRgba to Gray Mat, then extract bytes. 
            // Performance hit vs NV21 but necessary for MJPEG mode.
            val gray = Mat()
            Imgproc.cvtColor(mRgba, gray, Imgproc.COLOR_RGBA2GRAY)
            yBuffer = ByteArray(gray.rows() * gray.cols())
            gray.get(0, 0, yBuffer)
            gray.release()
        }
        
        // Calculate diff
        val diff = boxProcessor.processFrameDifference(mLastFrameData, yBuffer!!, frameWidth, frameHeight)
        
        // Store current as last. We clone it because 'data' array might be reused/garbage.
        mLastFrameData = yBuffer.clone()

        // 2. ML Kit Scanning
        var isBarcodeDetected = false
        // Only run scan if in SCANNING state or slightly checking? 
        // User: "SCANNING state: When motion is detected... triggers a continuous barcode scanning"
        // "IDLE state: pause ML Kit scanners"
        
        if (boxProcessor.currentState == AppState.SCANNING || 
            boxProcessor.currentState == AppState.STABLE ||
            boxProcessor.currentState == AppState.VALIDATED ||
            boxProcessor.currentState == AppState.RESET) {
            
            // Optimization: Stability Check for SCANNING phase
            // "Wait for Stability... Once the image is static, send the Bitmap to the ML Kit."
            val isStable = diff < Config.THRESH_STABILITY
            
            // If we are definitely scanning for new data (SCANNING), wait for stable image.
            // If we are in RESET/STABLE, we need to scan to know if it's LOST, even if moving (lifting).
            // But if moving *too fast* logic might fail anyway.
            // Let's rely on the user's specific request for SCANNING state optimization.
            
            var shouldScan = true
            if (boxProcessor.currentState == AppState.SCANNING && !isStable) {
                shouldScan = false
            } else if (frameCount % 6 != 0) { 
                // Throttle the existing scans for other states or stable scanning
                shouldScan = false
            }
            
            // Note: RESET state needs fairly frequent updates to detect "lost", 
            // but we have 500ms debounce now, so slightly slower FPS is okay.
            
            if (shouldScan) {
                 val bmp = convertMatToBitmap(mRgba!!)
                 if (bmp != null) {
                     scanBarcode(bmp) 
                     // scanText called internally on success
                 }
            }
        }
        
        // Check if we detected a barcode recently (e.g. within last 500ms)
        // We'll add a helper in BoxProcessor or just check if currentBarcode is non-null?
        // But RESET needs to know if it's *lost*.
        // We can assume if 'currentBarcode' is not null, it's detected.
        // BoxProcessor.updateBarcode should clear it or we need a timeout?
        // I'll assume scanBarcode result handling updates the detection status.
        
        isBarcodeDetected = boxProcessor.currentBarcode != null

        // 3. Update Logic
        boxProcessor.updateLogic(diff, isBarcodeDetected)

        // 4. Update Native UI
        // We no longer draw on a HUD mat.
        
        activity?.runOnUiThread {
             tvState?.text = "STATE: ${boxProcessor.currentState}"
             
             // Update Motion Bar
             val diff = boxProcessor.currentPixelDiff
             val maxDiff = 50.0 
             val progress = Math.min((diff / maxDiff) * 100, 100.0).toInt()
             pbMotion?.progress = progress
             
             // Update Results
             tvBarcode?.text = "Barcode: ${boxProcessor.currentBarcode ?: "--"}"
             tvPO?.text = "PO: ${boxProcessor.currentPO ?: "--"}"
             
             // Color coding
             val color = when(boxProcessor.currentState) {
                AppState.STABLE, AppState.VALIDATED -> android.graphics.Color.GREEN
                AppState.SCANNING -> android.graphics.Color.YELLOW
                AppState.RESET -> android.graphics.Color.CYAN
                else -> android.graphics.Color.WHITE
             }
             tvState?.setTextColor(color)
        }
        
        decoded.release() // Safety logic from before
    }


    // Optical Flow Removed


    private fun convertMatToBitmap(mat: Mat): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bmp)
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            null
        }
    }

    private fun scanBarcode(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isEmpty()) {
                    boxProcessor.clearBarcode()
                } else {
                    for (barcode in barcodes) {
                        val value = barcode.rawValue
                        val box = barcode.boundingBox
                        if (value != null && box != null) {
                            boxProcessor.updateBarcode(value)
                            
                            // PO Identification Frame Logic:
                            // "Frame below the barcode frame, 10px away... same length... half the height"
                            val gap = 10
                            val top = box.bottom + gap
                            val height = box.height() / 2
                            val width = box.width()
                            val left = box.left
                            
                            // Calculate safe crop rect
                            val safeLeft = Math.max(0, left)
                            val safeTop = Math.max(0, top)
                            val safeWidth = Math.min(width, bitmap.width - safeLeft)
                            val safeHeight = Math.min(height, bitmap.height - safeTop)
                            
                            if (safeWidth > 0 && safeHeight > 0) {
                                try {
                                    val poBitmap = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
                                    // Debug visualization of ROI
                                    activity?.runOnUiThread {
                                        ivRoiDebug?.setImageBitmap(poBitmap)
                                    }
                                    scanText(poBitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to crop PO bitmap", e)
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun scanText(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                val regex = Regex("\\d{5,}")
                val match = regex.find(text)
                if (match != null) {
                    boxProcessor.updatePO(match.value)
                }
            }
    }



    companion object {
        private const val TAG = "BoxCounter"
    }
}
