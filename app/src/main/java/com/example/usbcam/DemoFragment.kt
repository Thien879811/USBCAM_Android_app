package com.example.usbcam

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.usbcam.api.PoApiService
import com.example.usbcam.databinding.LayoutDashboardBinding
import com.example.usbcam.viewmodel.MainViewModel
import com.example.usbcam.viewmodel.MainViewModelFactory
import com.example.usbcam.viewmodel.TimeSlotAdapter
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.IAspectRatio
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

class DemoFragment : CameraFragment(), IPreviewDataCallBack {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(requireActivity().application)
    }

    // UI
    private var mViewBinding: LayoutDashboardBinding? = null

    // Logic Processor
    private val boxProcessor = BoxProcessor()

    // OpenCV Mats
    private var mRgba: Mat? = null
    private var mYuvMat: Mat? = null

    // Threading
    private val frameQueue = ArrayBlockingQueue<ByteArray>(3)
    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var isProcessingThreadRunning = false
    private var processingThread: Thread? = null

    private var mClahe: org.opencv.imgproc.CLAHE? = null

    @Volatile private var isScanningBarcode = false
    @Volatile private var isScanningPO = false

    // Scheduling & Performance
    private var lastProcessTime = 0L
    private var lastScanTime = 0L

    private var toneGen: android.media.ToneGenerator? = null
    private var vibrator: android.os.Vibrator? = null
    private var lastState = AppState.IDLE

    private val apiService = PoApiService.create()

    private var isApiCalling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        }
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (mViewBinding == null) {
            mViewBinding = LayoutDashboardBinding.inflate(inflater, container, false)
        }
        return mViewBinding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = TimeSlotAdapter()
        mViewBinding?.recyclerTimeSlot?.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }

        viewModel.timeSlotList.observe(viewLifecycleOwner) { list -> adapter.submitList(list) }

        viewModel.targetData.observe(viewLifecycleOwner) { target ->
            if (target != null) {
                boxProcessor.target = target.quantityTarget
            }
        }

        viewModel.totalScan.observe(viewLifecycleOwner) { total ->
            if (total != null) {
                boxProcessor.totalCount = total
            }
        }

        viewModel.loadTotal()
        viewModel.loadTarget()
        viewModel.loadAllTimeSlots()

        mViewBinding?.btnSettings?.setOnClickListener {
            val configFragment = ConfigFragment()
            parentFragmentManager
                    .beginTransaction()
                    .replace(android.R.id.content, configFragment) // Or a specific container ID
                    .addToBackStack(null)
                    .commit()
        }

        // Monitor Network
        val networkMonitor = com.example.usbcam.utils.NetworkConnectionMonitor(requireContext())
        networkMonitor.observe(viewLifecycleOwner) { isConnected ->
            Log.d(TAG, "Network Status: $isConnected")
            mViewBinding?.tvNoInternet?.visibility = if (isConnected) View.GONE else View.VISIBLE
        }
    }

    override fun getCameraView(): IAspectRatio? = mViewBinding?.tvCameraRender
    override fun getCameraViewContainer(): ViewGroup? = null
    override fun getGravity(): Int = Gravity.TOP

    override fun onCameraState(
            self: com.jiangdg.ausbc.MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?
    ) {
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
                .setPreviewWidth(1280)
                .setPreviewHeight(960)
                .setRenderMode(CameraRequest.RenderMode.OPENGL)
                .setDefaultRotateType(RotateType.ANGLE_0)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                .setRawPreviewData(true)
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
            mYuvMat = Mat(frameHeight + frameHeight / 2, frameWidth, CvType.CV_8UC1)
            mRgba = Mat()
        }
        frameQueue.offer(data)
    }

    private fun startProcessingThread() {
        stopProcessingThread()
        isProcessingThreadRunning = true
        processingThread =
                Thread {
                    while (isProcessingThreadRunning) {
                        try {
                            val data = frameQueue.take()
                            if (data != null) {
                                processFrame(data)
                            }
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
                        .apply {
                            name = "FrameProcessing"
                            isDaemon = false
                            start()
                        }
    }

    private fun stopProcessingThread() {
        isProcessingThreadRunning = false
        processingThread?.interrupt()
        processingThread?.join(5000L) // Wait up to 5 seconds for thread to finish
        processingThread = null
        frameQueue.clear()

        try {
            mYuvMat?.release()
            mYuvMat = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing mYuvMat", e)
        }

        try {
            mRgba?.release()
            mRgba = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing mRgba", e)
        }
    }

    private fun processFrame(data: ByteArray) {
        val now = System.currentTimeMillis()

        // 1. FPS Limiter
        if (now - lastProcessTime < (1000L / Config.MAX_PROCESSING_FPS)) {
            return
        }
        lastProcessTime = now

        var decoded = Mat()
        try {
            val buf = MatOfByte(*data)
            decoded = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
            buf.release() // Release MatOfByte

            val rgba = mRgba
            if (rgba == null) {
                decoded.release()
                return
            }

            if (!decoded.empty()) {
                Imgproc.cvtColor(decoded, rgba, Imgproc.COLOR_BGR2RGBA)
            } else {
                // Fallback for NV21/YUV
                val yuv = mYuvMat
                if (yuv != null) {
                    yuv.put(0, 0, data)
                    Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
            decoded.release()
            return
        }
        decoded.release()

        val rgba = mRgba
        if (rgba == null || rgba.empty()) return

        // Gray for processing
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

        // --- IMAGE ADJUSTMENT (Focus/Enhancement) ---
        applyImageAdjustments(gray)

        // --- CORE KINEMATIC UPDATE ---
        try {
            boxProcessor.updateLogic(gray)
        } catch (e: Exception) {
            Log.e(TAG, "Error in boxProcessor logic", e)
        }

        // 2. Throttle MLKit Scans
        if (now - lastScanTime > Config.SCAN_THROTTLE_MS) {
            val currentState = boxProcessor.currentState

            // Only convert to Bitmap if necessary for scanning
            if ((currentState == AppState.IDLE && !isScanningBarcode) ||
                            (currentState == AppState.PROCESSING && !isScanningPO)
            ) {

                val bmp = convertMatToBitmap(rgba)

                if (bmp != null) {
                    when (currentState) {
                        AppState.IDLE -> {
                            if (!isScanningBarcode) {
                                isScanningBarcode = true
                                lastScanTime = now
                                val grayClone = gray.clone()
                                scanBarcode(bmp, grayClone)
                            }
                        }
                        AppState.PROCESSING -> {
                            boxProcessor.getSafeTrackingRect()?.let { rectF ->
                                if (!isScanningPO) {
                                    isScanningPO = true
                                    lastScanTime = now
                                    val cropRect = android.graphics.Rect()
                                    rectF.round(cropRect)
                                    scanPOInRegion(bmp, cropRect)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        updateUI()
        gray.release()
    }

    private fun applyImageAdjustments(gray: Mat) {
        // 1. CLAHE (Contrast Limited Adaptive Histogram Equalization)
        if (Config.USE_CLAHE) {
            if (mClahe == null) {
                mClahe =
                        Imgproc.createCLAHE(
                                Config.CLAHE_CLIP_LIMIT,
                                Size(Config.CLAHE_TILE_SIZE, Config.CLAHE_TILE_SIZE)
                        )
            }
            mClahe?.apply(gray, gray)
        }

        // 2. UNSIHARP MASK (Sharpening)
        if (Config.USE_SHARPEN) {
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(gray, 1.5, blurred, -0.5, 0.0, gray)
            blurred.release()
        }
    }

    private fun scanBarcode(bitmap: Bitmap, grayMatClone: Mat) {
        val image = InputImage.fromBitmap(bitmap, 0)
        BarcodeScanning.getClient()
                .process(image)
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
                    try {
                        grayMatClone.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing grayMatClone", e)
                    }
                    isScanningBarcode = false
                }
                .addOnFailureListener {
                    try {
                        grayMatClone.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing grayMatClone", e)
                    }
                    isScanningBarcode = false
                }
    }

    private fun scanPOInRegion(fullBitmap: Bitmap, anchorRect: android.graphics.Rect) {
        val gap = 10
        val roiX = Math.max(0, anchorRect.left - 40)
        val roiY = Math.max(0, anchorRect.bottom + gap)
        val roiW = Math.min(fullBitmap.width - roiX, anchorRect.width() + 200)
        val roiH = Math.min(fullBitmap.height - roiY, 300)

        if (roiW > 10 && roiH > 10) {
            try {
                val poBitmap = Bitmap.createBitmap(fullBitmap, roiX, roiY, roiW, roiH)

                val image = InputImage.fromBitmap(poBitmap, 0)
                val options = TextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options)
                        .process(image)
                        .addOnSuccessListener { visionText -> processOcrResult(visionText.text) }
                        .addOnCompleteListener { isScanningPO = false }
            } catch (e: Exception) {
                Log.e(TAG, "Error in scanPOInRegion", e)
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
            if (clean.length in Config.MIN_PO_LENGTH..Config.MAX_PO_LENGTH &&
                            clean.all { it.isDigit() }
            ) {
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

            // Map Logic -> New UI
            mViewBinding?.let { binding ->
                // Status
                binding.tvStatusOk.text =
                        when (currentState) {
                            AppState.IDLE -> "IDLE"
                            AppState.PROCESSING-> "PROCESSING"
                            AppState.SUCCESS -> "SUCCESS"
                            AppState.ERROR_LOCKED -> "FAILURE"
                            else -> ""
                        }

                // Fields
                binding.tvUpcValue.text = boxProcessor.currentBarcode ?: "--"
                binding.tvPoValue.text = boxProcessor.currentPO ?: "--"
                binding.tvTotalActual.text = "${boxProcessor.totalCount}"

                // API Response
                binding.tvQtyValue.text = "${boxProcessor.apiResponse?.quantity}"
                binding.tvRyValue.text = "${boxProcessor.apiResponse?.ry}"
                binding.tvSizeValue.text = "${boxProcessor.apiResponse?.size}"
                binding.tvArt.text = "${boxProcessor.apiResponse?.article ?: "--"}"
                binding.tvRemaining.text = "${boxProcessor.apiResponse?.remainInternal}"
                binding.tvCompleted.text = "${boxProcessor.apiResponse?.doneInternal}"
                binding.tvCountry.text = "${boxProcessor.apiResponse?.country}"
                binding.tvLean.text = "${boxProcessor.apiResponse?.lean}"
                binding.tvTotalOrder.text = "${boxProcessor.apiResponse?.qtyOrder}"
                binding.tvTotalTarget.text = "${boxProcessor.target}"

                Glide.with(this)
                        .load(
                                "http://192.168.30.19:5000/shoes-photos/${boxProcessor.apiResponse?.articleImage}"
                        )
                        .into(binding.ivShoeImage)

                // Color Logic for Status
                val color =
                        when (currentState) {
                            AppState.SUCCESS -> Color.GREEN
                            AppState.ERROR_LOCKED -> Color.RED
                            AppState.PROCESSING -> Color.YELLOW
                            AppState.VALIDATING -> Color.BLUE
                            else -> Color.DKGRAY
                        }
                binding.tvStatusOk.setTextColor(color)

                if (currentState == AppState.VALIDATING && !isApiCalling) {
                    callApi()
                }

                if (currentState == AppState.PROCESSING || currentState == AppState.VALIDATING) {
                    binding.pbMotion.visibility = View.VISIBLE
                } else {
                    binding.pbMotion.visibility = View.GONE
                }

                // Update Scan Time if available (using lastScanTime or specific logic)
                binding.tvScanTime.text =
                        "Thời gian (ss.fff): ${String.format("%.3f", (System.currentTimeMillis() - lastProcessTime)/1000.0)}"
            }
        }
    }

    private fun callApi() {
        val po = boxProcessor.currentPO ?: return
        val barcode = boxProcessor.currentBarcode ?: return

        isApiCalling = true
        Log.i("DemoFragment", "Calling API for PO: $po, Barcode: $barcode")

        apiService
                .getPoDetails(po, barcode)
                .enqueue(
                        object : retrofit2.Callback<com.example.usbcam.api.PoResponse> {
                            override fun onResponse(
                                    call: retrofit2.Call<com.example.usbcam.api.PoResponse>,
                                    response: retrofit2.Response<com.example.usbcam.api.PoResponse>
                            ) {
                                isApiCalling = false
                                if (response.isSuccessful && response.body() != null) {
                                    val data = response.body()!!
                                    Log.d("DemoFragment", "API Success: $data")
                                    boxProcessor.apiResponse = data
                                    boxProcessor.currentState = AppState.SUCCESS
                                    boxProcessor.totalCount++
                                    viewModel.saveScanData(po, barcode, data)
                                } else {
                                    Log.e("DemoFragment", "API Error: ${response.code()}")
                                    boxProcessor.currentState = AppState.ERROR_LOCKED
                                }
                                updateUI()
                            }

                            override fun onFailure(
                                    call: retrofit2.Call<com.example.usbcam.api.PoResponse>,
                                    t: Throwable
                            ) {
                                isApiCalling = false
                                Log.e("DemoFragment", "API Failure: ${t.message}")
                                // Try checking local data
                                if (isAdded) {
                                    checkLocalDataFallback(po, barcode)
                                } else {
                                    boxProcessor.currentState = AppState.ERROR_LOCKED
                                    updateUI()
                                }
                            }
                        }
                )
    }

    private fun checkLocalDataFallback(po: String, barcode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val localData = viewModel.getLocalData(po, barcode)
                if (localData != null) {
                    Log.d("DemoFragment", "Offline Fallback Success: $localData")
                    boxProcessor.apiResponse = localData
                    boxProcessor.currentState = AppState.SUCCESS
                    boxProcessor.totalCount++
                    viewModel.saveScanData(po, barcode, localData)
                } else {
                    Log.e("DemoFragment", "Offline Fallback Failed: No local data")
                    boxProcessor.currentState = AppState.ERROR_LOCKED
                }
                updateUI()
            } catch (e: Exception) {
                Log.e("DemoFragment", "Error in fallback: ${e.message}")
                boxProcessor.currentState = AppState.ERROR_LOCKED
                updateUI()
            }
        }
    }

    private fun handleStateFeedback(newState: AppState) {
        if (toneGen == null)
                toneGen =
                        android.media.ToneGenerator(
                                android.media.AudioManager.STREAM_ALARM,
                                Config.BEEP_VOLUME
                        )
        if (vibrator == null)
                vibrator =
                        activity?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as
                                android.os.Vibrator?

        when (newState) {
            AppState.SUCCESS -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                            android.os.VibrationEffect.createOneShot(
                                    150L,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                            )
                    )
                } else {
                    @Suppress("DEPRECATION") vibrator?.vibrate(150L)
                }
            }
            AppState.ERROR_LOCKED -> {
                toneGen?.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                            android.os.VibrationEffect.createOneShot(
                                    500L,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                            )
                    )
                } else {
                    @Suppress("DEPRECATION") vibrator?.vibrate(500L)
                }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error converting Mat to Bitmap", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingThread()
        boxProcessor.release()
        try {
            toneGen?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing tone generator", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mViewBinding = null
    }

    companion object {
        private const val TAG = "BoxScanner"
    }
}
