package com.example.usbcam

import android.graphics.RectF
import android.util.Log
import java.util.Collections
import kotlin.math.abs
import kotlin.math.sqrt
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
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
    private var velocityX = 0f
    private var velocityY = 0f
    private var coastingCounter = 0
    private var isCoasting = false

    // Logic
    private val poBuffer = Collections.synchronizedList(ArrayList<String>())
    private var stateStartTime = 0L
    private var lastApiAttemptTime = 0L

    // API result
    var apiResponse: com.example.usbcam.api.PoResponse? = null
    var identificationStarted = false

    // --- THREAD SAFE GETTERS CHO UI ---
    fun getSafeTrackingRect(): RectF? {
        synchronized(trackingLock) {
            return trackingRect?.let { RectF(it) } // Return copy
        }
    }

    /** MAIN UPDATE LOOP (Camera Thread) */
    fun updateLogic(currentGray: Mat) {
        val now = System.currentTimeMillis()

        if (currentState != AppState.IDLE && prevGray == null) {
            prevGray = currentGray.clone()
            return
        }

        when (currentState) {
            AppState.IDLE -> {
                feedbackMessage = "READY"
                resetTrackingData()
            }
            AppState.ENTERING,
            AppState.PROCESSING,
            AppState.VALIDATING,
            AppState.SUCCESS,
            AppState.ERROR_LOCKED -> {
                // 1. KINEMATICS UPDATE
                val trackResult = updateKinematics(currentGray)

                if (!trackResult) {
                    Log.w("BoxProcessor", "Lost Track -> Reset")
                    resetToIdle()
                    return
                }

                // 2. STRICT EXIT CHECK
                if (shouldStrictlyReset(currentGray.cols(), currentGray.rows())) {
                    resetToIdle()
                    return
                }

                handleStateLogic(now)
            }
        }

        prevGray?.release()
        prevGray = currentGray.clone()
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
        feedbackMessage = "COASTING ($coastingCounter)"

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

    private fun shouldStrictlyReset(imgW: Int, imgH: Int): Boolean {
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

        // 1. DEDUPLICATION (Global)
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
            currentState = AppState.ENTERING
            stateStartTime = now
            currentBarcode = barcode
            currentPO = null
            poBuffer.clear()
            feedbackMessage = "ENTERING..."
            prevGray = grayMat.clone()
            entryFrameCount = 0

            // Record Start Side
            startSide = if (isLeftEdge) -1 else if (isRightEdge) 1 else 0
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
                    currentState = AppState.PROCESSING
                    stateStartTime = now
                    feedbackMessage = "SCANNING..."
                } else {
                    entryFrameCount++
                    if (entryFrameCount > Config.ENTRY_GRACE_FRAMES) {
                        resetToIdle()
                        Log.d("BoxProcessor", "Entry failed: No valid velocity ($velocityX)")
                    }
                }
            }
            AppState.PROCESSING -> {
                val votedPO = getVotedPO()
                if (votedPO != null) {
                    currentPO = votedPO
                    triggerValidation()
                } else {
                    if (now - stateStartTime > Config.PO_TIMEOUT_MS) {
                        feedbackMessage = "TIMEOUT PO"
                        currentState = AppState.ERROR_LOCKED
                        stateStartTime = now
                    }
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
            else -> {}
        }
    }

    fun addPO(startPO: String) {
        if (currentState == AppState.PROCESSING) {
            poBuffer.add(startPO)
            feedbackMessage = "SCANNING... ${poBuffer.size}/${Config.VOTING_BUFFER_SIZE}"
        }
    }

    private fun getVotedPO(): String? {
        synchronized(poBuffer) {
            if (poBuffer.size >= Config.VOTING_BUFFER_SIZE) {
                val counts = poBuffer.groupingBy { it }.eachCount()
                val best = counts.maxByOrNull { it.value }
                if (best != null) {
                    confidenceScore = (best.value * 100) / poBuffer.size
                    return best.key
                }
            }
        }
        return null
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
        poBuffer.clear()
        feedbackMessage = "READY"
        identificationStarted = false
    }

    private fun resetTrackingData() {
        try {
            prevPoints?.release()
            prevPoints = null
        } catch (e: Exception) {
            Log.e("BoxProcessor", "Error releasing prevPoints", e)
        }

        try {
            prevGray?.release()
            prevGray = null
        } catch (e: Exception) {
            Log.e("BoxProcessor", "Error releasing prevGray", e)
        }

        synchronized(trackingLock) { trackingRect = null }

        velocityX = 0f
        velocityY = 0f
        isCoasting = false
        coastingCounter = 0
    }
}
