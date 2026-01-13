package com.example.usbcam

import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorKNN
import org.opencv.video.Video

class BoxProcessor {

    // State
    @Volatile var currentState = AppState.IDLE
    @Volatile var currentBarcode: String? = null
    @Volatile var currentPO: String? = null
    @Volatile var feedbackMessage: String = "READY"
    @Volatile var confidenceScore: Int = 0
    @Volatile var totalCount = 0

    @Volatile var target = 0

    // Gate scanning
    @Volatile var isMotionTriggered = false

    // Internal State
    private var lastSuccessBarcode: String? = null
    private var lastSuccessTime = 0L
    private var startSide = 0 // 0: None, -1: Left, 1: Right

    // --- KINEMATIC TRACKING ---
    // User Requirement: Use synchronized when reading/writing trackingRect
    private val trackingLock = Any()

    private var trackingRect: RectF? = null // Access only within sync block
    private var prevGray: Mat? = null
    private var prevPoints: MatOfPoint2f? = null

    // Physics
    private val kinematicsLock = Any() // Lock cho prevGray, prevPoints, velocity
    private var velocityX = 0f
    private var velocityY = 0f
    private var coastingCounter = 0
    private var isCoasting = false

    // Logic
    private var stateStartTime = 0L
    private var lastApiAttemptTime = 0L

    // API result
    var apiResponse: com.example.usbcam.api.PoResponse? = null
    var identificationStarted = false

    // --- KNN cho phát hiện chuyển động giả & vật thể rời đi ---
    private var knnSubtractor: BackgroundSubtractorKNN? = null
    private var fgMask: Mat? = null
    private var isObjectGone = false

    // --- THREAD SAFE GETTERS CHO UI ---
    fun getSafeTrackingRect(): RectF? {
        synchronized(trackingLock) {
            return trackingRect?.let { RectF(it) } // Return copy
        }
    }

    /** MAIN UPDATE LOOP (Camera Thread) */
    fun updateLogic(currentGray: Mat) {
        val now = System.currentTimeMillis()

        synchronized(kinematicsLock) {
            if (currentState != AppState.IDLE && prevGray == null) {
                prevGray = currentGray.clone()
                return
            }
        }

        when (currentState) {
            AppState.IDLE -> {
                feedbackMessage = "READY"
                resetTrackingData()
                detectIdleMotion(currentGray) // Check for motion
            }
            AppState.ENTERING,
            AppState.PROCESSING,
            AppState.VALIDATING,
            AppState.SUCCESS,
            AppState.ERROR_LOCKED -> {
                // Cập nhật KNN trước để thuật toán có mặt nạ (mask) mới nhất

                updateKNN(currentGray)

                // 1. KINEMATICS UPDATE (Optical Flow)
                val trackResult = synchronized(kinematicsLock) { updateKinematics(currentGray) }

                if (!trackResult) {
                    Log.w("BoxProcessor", "Track failed -> State: $currentState -> Reset")
                    resetToIdle()
                    return
                }

                // 2. STRICT EXIT CHECK (Mép biên)

                //                 if (shouldStrictlyReset(currentGray.cols())) {
                //                     Log.d("BoxProcessor", "Strict Reset: Edge reached")
                //                     resetToIdle()
                //                     return
                //                 }

                // 3. KNN DISAPPEARANCE CHECK (Xác nhận vật thể rời khỏi)
                if (currentState == AppState.SUCCESS || currentState == AppState.ERROR_LOCKED) {
                    if (checkDisappearanceKNN()) {
                        Log.d("BoxProcessor", "KNN: Object Gone -> Reset to IDLE")
                        resetToIdle()
                        return
                    }
                }

                handleStateLogic(now)
            }
        }

        synchronized(kinematicsLock) {
            prevGray?.release()
            prevGray = currentGray.clone()
        }
    }

    /** CORE LOGIC: Optical Flow + Outlier Rejection + Replenishment */
    private fun updateKinematics(currentGray: Mat): Boolean {
        if (prevPoints == null || prevGray == null) return false

        // A. Optical Flow
        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        try {
            Video.calcOpticalFlowPyrLK(
                    prevGray,
                    currentGray,
                    prevPoints,
                    nextPoints,
                    status,
                    err,
                    Size(Config.FLOW_WIN_SIZE.toDouble(), Config.FLOW_WIN_SIZE.toDouble()),
                    3
            )
        } catch (e: Exception) {
            nextPoints.release()
            status.release()
            err.release()
            return false
        }

        val statusArr = status.toArray()
        val p0 = prevPoints!!.toArray()
        val p1 = nextPoints.toArray()

        val goodP1 = ArrayList<Point>()
        val vectors = ArrayList<Point>()

        // B. Filter successful points
        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1) {
                goodP1.add(p1[i])
                vectors.add(Point(p1[i].x - p0[i].x, p1[i].y - p0[i].y))
            }
        }

        // C. Logic Coasting vs Tracking
        if (goodP1.size > 5) {
            isCoasting = false
            coastingCounter = 0

            // --- OUTLIER REJECTION ---
            var sumDx = 0.0
            var sumDy = 0.0
            for (v in vectors) {
                sumDx += v.x
                sumDy += v.y
            }
            val meanDx = sumDx / vectors.size
            val meanDy = sumDy / vectors.size

            var finalDx = 0.0
            var finalDy = 0.0
            var validCount = 0
            val validPointsForNextLoop = ArrayList<Point>()

            for (i in vectors.indices) {
                val dx = vectors[i].x
                val dy = vectors[i].y
                val dist = sqrt((dx - meanDx) * (dx - meanDx) + (dy - meanDy) * (dy - meanDy))

                if (dist < Config.MAX_DEVIATION_PIXEL) {
                    finalDx += dx
                    finalDy += dy
                    validCount++
                    validPointsForNextLoop.add(goodP1[i])
                }
            }

            if (validCount > 0) {
                var currVx = (finalDx / validCount).toFloat()
                var currVy = (finalDy / validCount).toFloat()

                // --- DIRECTION CHECK (Robust) ---
                // 1. Vertical Drift Check
                if (abs(currVy) > Config.MAX_VERTICAL_VELOCITY) {
                    // Instead of resetting, we just ignore this "shake" frame
                    currVx = 0f
                    currVy = 0f
                    Log.d("BoxProcessor", "Ignored Vertical Shake: $currVy")
                }

                // 2. Horizontal Dominance Check
                // Only enforce if significant movement is detected
                else if (abs(currVx) > Config.MIN_VELOCITY_THRESHOLD ||
                                abs(currVy) > Config.MIN_VELOCITY_THRESHOLD
                ) {
                    if (abs(currVx) < abs(currVy) * Config.HORIZONTAL_DOMINANCE_RATIO) {
                        // Irregular/Diagonal move -> Dampen it
                        currVx = 0f
                        currVy = 0f
                        Log.d("BoxProcessor", "Ignored Irregular Move: Vx=$currVx, Vy=$currVy")
                    }
                }

                // --- VELOCITY UPDATE & DEADBAND ---
                var rawVx =
                        velocityX * Config.VELOCITY_SMOOTHING +
                                currVx * (1.0f - Config.VELOCITY_SMOOTHING)
                var rawVy =
                        velocityY * Config.VELOCITY_SMOOTHING +
                                currVy * (1.0f - Config.VELOCITY_SMOOTHING)

                // Deadband Fix: Force to 0 if < MIN_VELOCITY_THRESHOLD
                if (abs(rawVx) < Config.MIN_VELOCITY_THRESHOLD) rawVx = 0f
                if (abs(rawVy) < Config.MIN_VELOCITY_THRESHOLD) rawVy = 0f

                velocityX = rawVx
                velocityY = rawVy

                // Update Box Position Thread-Safe
                synchronized(trackingLock) { trackingRect?.offset(velocityX, velocityY) }

                // Update Points for Next Frame
                prevPoints!!.fromList(validPointsForNextLoop)

                // --- FEATURE REPLENISHMENT ---
                if (validCount < Config.REPLENISH_THRESHOLD) {
                    replenishFeatures(currentGray)
                }
            } else {
                enterCoasting()
            }
        } else {
            enterCoasting()
        }

        nextPoints.release()
        status.release()
        err.release()
        return true
    }

    private fun enterCoasting() {
        isCoasting = true
        coastingCounter++
        feedbackMessage = "COASTING"

        // Coasting prediction
        synchronized(trackingLock) { trackingRect?.offset(velocityX, velocityY) }

        // Logic check
        if (coastingCounter > Config.COASTING_MAX_FRAMES) {
            resetToIdle()
        }
    }

    private fun replenishFeatures(gray: Mat) {
        val rect = synchronized(trackingLock) { trackingRect } ?: return

        try {
            // Check bounds
            val x = rect.left.toInt().coerceIn(0, gray.cols() - 1)
            val y = rect.top.toInt().coerceIn(0, gray.rows() - 1)
            val w = rect.width().toInt().coerceAtMost(gray.cols() - x)
            val h = rect.height().toInt().coerceAtMost(gray.rows() - y)

            if (w <= 10 || h <= 10) return

            val roi = gray.submat(y, y + h, x, x + w)
            val newCorners = MatOfPoint()

            val needed = Config.MAX_TRACKING_POINTS - (prevPoints?.rows() ?: 0)

            if (needed > 5) {
                Imgproc.goodFeaturesToTrack(
                        roi,
                        newCorners,
                        needed,
                        Config.QUALITY_LEVEL,
                        Config.MIN_DISTANCE
                )

                if (newCorners.rows() > 0) {
                    // Convert ROI coords to Global coords
                    val newPointsList = newCorners.toArray().map { p -> Point(p.x + x, p.y + y) }

                    val currentPoints = prevPoints?.toArray()?.toMutableList() ?: ArrayList()
                    currentPoints.addAll(newPointsList)

                    prevPoints?.fromList(currentPoints)
                }
            }
            roi.release()
            newCorners.release()
        } catch (e: Exception) {
            Log.e("BoxProcessor", "Replenish failed", e)
        }
    }

    private fun shouldStrictlyReset(imgW: Int): Boolean {
        val rect = synchronized(trackingLock) { trackingRect } ?: return true
        val centerX = rect.centerX()
        val thresholdW = imgW * Config.EDGE_THRESHOLD_PERCENT

        val atLeftEdge = centerX < thresholdW
        val atRightEdge = centerX > (imgW - thresholdW)

        val movingLeft = velocityX < -2.0f
        val movingRight = velocityX > 2.0f

        return (atLeftEdge && movingLeft) || (atRightEdge && movingRight)
    }

    private var entryFrameCount = 0

    // --- SETUP & UTILS ---

    fun onBarcodeDetected(barcode: String, boxRect: android.graphics.Rect, grayMat: Mat) {
        val now = System.currentTimeMillis()

        // 1. MOTION CHECK (New Requirement)
//        if (!isMotionTriggered) {
//            // Ignore scan if no motion detected
//            return
//        }

        // 2. DEDUPLICATION (Global)
        if (barcode == lastSuccessBarcode &&
                        (now - lastSuccessTime < Config.DEDUPLICATION_WINDOW_MS)
        ) {
            return
        }

        // 2. EDGE CHECK & INTERRUPT
        // Allow interrupt if we are at the edges, even if currently busy
        val centerX = boxRect.centerX()
        val imgW = grayMat.cols()
        val isLeftEdge = centerX < (imgW * Config.EDGE_INTERRUPT_ZONE_PERCENT)
        val isRightEdge = centerX > (imgW * (1.0f - Config.EDGE_INTERRUPT_ZONE_PERCENT))

        if (isLeftEdge || isRightEdge) {
            // Force reset and new track
            if (currentState != AppState.IDLE) {
                resetTrackingData() // Only reset data, keep state flow
                Log.d("BoxProcessor", "INTERRUPT: New box at edge")
            }
        } else {
            // Center detection: Only accepted if IDLE
            if (currentState != AppState.IDLE) return
        }

        // 3. INITIALIZE
        if (initializeTracking(boxRect, grayMat)) {
            synchronized(kinematicsLock) {
                currentState = AppState.PROCESSING
                stateStartTime = now
                currentBarcode = barcode
                currentPO = null
                feedbackMessage = "PROCESSING..."

                prevGray?.release()
                prevGray = grayMat.clone()
                entryFrameCount = 0

                // Record Start Side
                startSide = if (isLeftEdge) -1 else if (isRightEdge) 1 else 0
            }
            Log.d("BoxProcessor", "New tracking started for: $barcode")
        }
    }

    private fun initializeTracking(rect: android.graphics.Rect, gray: Mat): Boolean {
        try {
            val roiMat = gray.submat(rect.top, rect.bottom, rect.left, rect.right)
            val corners = MatOfPoint()

            Imgproc.goodFeaturesToTrack(
                    roiMat,
                    corners,
                    Config.MAX_TRACKING_POINTS,
                    Config.QUALITY_LEVEL,
                    Config.MIN_DISTANCE
            )

            if (corners.rows() > 0) {
                val roiPoints = corners.toArray()
                val fullPoints =
                        roiPoints.map { p -> Point(p.x + rect.left, p.y + rect.top) }.toTypedArray()

                prevPoints = MatOfPoint2f(*fullPoints)

                // Tinh chỉnh tọa độ điểm đặc trưng đến mức sub-pixel để tăng độ bám dính
                Imgproc.cornerSubPix(
                        gray,
                        prevPoints!!,
                        Size(5.0, 5.0),
                        Size(-1.0, -1.0),
                        TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.1)
                )

                synchronized(trackingLock) {
                    trackingRect =
                            RectF(
                                    rect.left.toFloat(),
                                    rect.top.toFloat(),
                                    rect.right.toFloat(),
                                    rect.bottom.toFloat()
                            )
                }

                velocityX = 0f
                velocityY = 0f

                corners.release()
                roiMat.release()
                return true
            } else {
                corners.release()
                roiMat.release()
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }

    // --- KNN LOGIC ---

    /**
     * Cập nhật mô hình trừ nền bằng KNN. Giúp lọc bỏ các chuyển động nhỏ, rung lắc hoặc thay đổi
     * ánh sáng không phải vật thể.
     */
    private fun updateKNN(gray: Mat) {
        if (knnSubtractor == null || Config.isKnnConfigChanged) {
            Log.d("BoxProcessor", "UpdateKNN: Re-creating (Changed: ${Config.isKnnConfigChanged})")
            knnSubtractor =
                    Video.createBackgroundSubtractorKNN(
                            Config.KNN_HISTORY,
                            Config.KNN_DIST_2_THRESHOLD,
                            false
                    )
            Config.isKnnConfigChanged = false
        }
        if (fgMask == null) fgMask = Mat()

        // Giảm độ phân giải để tăng tốc độ xử lý
        val smallGray = Mat()
        Imgproc.resize(gray, smallGray, Size(gray.cols() / 2.0, gray.rows() / 2.0))

        knnSubtractor?.apply(smallGray, fgMask!!)
        smallGray.release()
    }

    /**
     * Kiểm tra xem vật thể có còn nằm trong vùng tracking dựa trên mặt nạ KNN. Trả về true nếu tỷ
     * lệ pixel "chuyển động" quá thấp (vật thể đã đi mất).
     */
    private fun checkDisappearanceKNN(): Boolean {
        var isStationary = false
        var trackingOK = false

        synchronized(kinematicsLock) {
            isStationary =
                    abs(velocityX) < Config.MIN_VELOCITY_THRESHOLD &&
                            abs(velocityY) < Config.MIN_VELOCITY_THRESHOLD
            trackingOK = !isCoasting
        }

        if (isStationary && trackingOK) {
            return false
        }

        return getKNNRatio() < Config.KNN_DISAPPEAR_PERCENT
    }

    private fun getKNNRatio(): Float {
        val mask = fgMask ?: return 0f
        val rect = synchronized(trackingLock) { trackingRect } ?: return 0f

        try {
            val x = (rect.left / 2).toInt().coerceIn(0, mask.cols() - 1)
            val y = (rect.top / 2).toInt().coerceIn(0, mask.rows() - 1)
            val w = (rect.width() / 2).toInt().coerceAtMost(mask.cols() - x)
            val h = (rect.height() / 2).toInt().coerceAtMost(mask.rows() - y)

            if (w <= 5 || h <= 5) return 0f

            val roi = mask.submat(y, y + h, x, x + w)
            val nonZero = Core.countNonZero(roi)
            val totalPixels = w * h
            val ratio = nonZero.toFloat() / totalPixels
            roi.release()
            Log.d("BoxProcessor", "Ratio ${ratio}")
            return ratio
        } catch (e: Exception) {
            return 0f
        }
    }

    // --- IDLE MOTION DETECTION ---
    private fun detectIdleMotion(gray: Mat) {
        val prev = synchronized(kinematicsLock) { prevGray } ?: return

        // Use Frame Difference
        val diff = Mat()
        try {
            Core.absdiff(gray, prev, diff)
            Imgproc.threshold(diff, diff, 25.0, 255.0, Imgproc.THRESH_BINARY)

            val nonZero = Core.countNonZero(diff)
            val total = gray.total()
            val ratio = nonZero.toFloat() / total.toFloat()

            isMotionTriggered = ratio > Config.IDLE_MOTION_PERCENT

            if (isMotionTriggered) {
                // feedbackMessage = "MOTION: ${"%.2f".format(ratio * 100)}%" // Optional debug
            }
        } catch (e: Exception) {
            Log.e("BoxProcessor", "Motion Detect Failed", e)
        } finally {
            diff.release()
        }
    }

    // --- LOGIC ---
    private fun handleStateLogic(now: Long) {
        when (currentState) {
            AppState.ENTERING -> {
                // Directional Validation based on Start Side
                var validEntry = false

                if (startSide == -1) { // Left Start -> Needs Positive Velocity (Right)
                    if (velocityX > Config.MIN_ENTRY_VELOCITY) validEntry = true
                } else if (startSide == 1) { // Right Start -> Needs Negative Velocity (Left)
                    if (velocityX < -Config.MIN_ENTRY_VELOCITY) validEntry = true
                } else {
                    // Center Start (IDLE only) -> Needs any strong horizontal velocity
                    if (abs(velocityX) > Config.MIN_ENTRY_VELOCITY) validEntry = true
                }

                if (validEntry) {
                    val ratio = getKNNRatio()
                    if (ratio >= Config.KNN_DISAPPEAR_PERCENT) {
                        currentState = AppState.PROCESSING
                        stateStartTime = now
                        feedbackMessage = "SCANNING..."
                        Log.d("BoxProcessor", "Motion Confirmed: ratio=$ratio, vel=$velocityX")
                    } else {
                        feedbackMessage = "NOISE FILTER..."
                        entryFrameCount++
                        if (entryFrameCount % 5 == 0) {
                            Log.d("BoxProcessor", "Noise Filter: ratio=$ratio, vel=$velocityX")
                        }
                    }
                } else {
                    entryFrameCount++
                    if (entryFrameCount > Config.ENTRY_GRACE_FRAMES) {
                        resetToIdle()
                        Log.d("BoxProcessor", "Entry failed: No valid velocity ($velocityX)")
                    }
                }
            }
            AppState.PROCESSING -> {
                if (now - stateStartTime > Config.PO_TIMEOUT_MS) {
                    feedbackMessage = "TIMEOUT PO"
                    currentState = AppState.ERROR_LOCKED
                    stateStartTime = now
                }
            }
            AppState.VALIDATING -> {
                feedbackMessage = "VALIDATING..."
                if (now - lastApiAttemptTime > 1000) {
                    if (currentPO == "99999") {
                        currentState = AppState.ERROR_LOCKED
                        feedbackMessage = "WRONG PO!"
                    } else {
                        currentState = AppState.SUCCESS
                        feedbackMessage = "COUNTED!"
                        totalCount++
                        lastSuccessBarcode = currentBarcode
                        lastSuccessTime = now
                    }
                    stateStartTime = now
                }
            }
            AppState.ERROR_LOCKED -> {
                if (now - stateStartTime > Config.ERROR_LOCKED_TIMEOUT_MS) {
                    Log.d("BoxProcessor", "Error Locked Timeout -> Reset to IDLE")
                    resetToIdle()
                }
            }
            else -> {}
        }
    }

    fun addPO(startPO: String) {
        if (currentState == AppState.PROCESSING) {
            currentPO = startPO
            triggerValidation()
            feedbackMessage = "PO DETECTED: $startPO"
        }
    }

    private fun triggerValidation() {
        currentState = AppState.VALIDATING
        lastApiAttemptTime = System.currentTimeMillis()
    }

    fun resetToIdle() {
        currentState = AppState.IDLE
        resetTrackingData()
        currentBarcode = null
        currentPO = null
        feedbackMessage = "READY"
        identificationStarted = false
        // resetKNN()
    }

    private fun resetTrackingData() {
        synchronized(kinematicsLock) {
            try {
                prevPoints?.release()
                prevPoints = null
            } catch (e: Exception) {
                Log.e("BoxProcessor", "Error releasing prevPoints", e)
            }

            // prevGray is NOT released here anymore to support IDLE motion detection
            // It will be released in release() or overwritten in initializeTracking()

            synchronized(trackingLock) { trackingRect = null }

            velocityX = 0f
            velocityY = 0f
            isCoasting = false
            coastingCounter = 0
        }
    }

    fun release() {
        resetTrackingData()

        synchronized(kinematicsLock) {
            prevGray?.release()
            prevGray = null
        }

        knnSubtractor = null
        fgMask?.release()
        fgMask = null
    }

    private fun resetKNN() {
        Log.d("BoxProcessor", "resetKNN")
        knnSubtractor =
                Video.createBackgroundSubtractorKNN(
                        Config.KNN_HISTORY,
                        Config.KNN_DIST_2_THRESHOLD,
                        false
                )
        fgMask?.release()
        fgMask = Mat()
    }
}
