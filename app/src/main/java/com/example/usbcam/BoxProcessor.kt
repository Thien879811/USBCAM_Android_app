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
    @Volatile var totalCount = 0

    // Internal State
    private var lastSuccessBarcode: String? = null
    private var lastSuccessTime = 0L

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
            AppState.PROCESSING, AppState.VALIDATING, AppState.SUCCESS, AppState.ERROR_LOCKED -> {
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
                val currVx = (finalDx / validCount).toFloat()
                val currVy = (finalDy / validCount).toFloat()

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

    // --- SETUP & UTILS ---

    fun onBarcodeDetected(barcode: String, boxRect: android.graphics.Rect, grayMat: Mat) {
        if (currentState != AppState.IDLE) return

        val now = System.currentTimeMillis()
        if (barcode == lastSuccessBarcode &&
                        (now - lastSuccessTime < Config.DEDUPLICATION_WINDOW_MS)
        ) {
            return
        }

        if (initializeTracking(boxRect, grayMat)) {
            currentState = AppState.PROCESSING
            stateStartTime = now
            currentBarcode = barcode
            currentPO = null
            poBuffer.clear()
            feedbackMessage = "SCANNING..."
            prevGray = grayMat.clone()
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
            AppState.PROCESSING -> {
                val votedPO = getVotedPO()
                if (votedPO != null) {
                    currentPO = votedPO
                    triggerValidation()
                } else {
                    if (now - stateStartTime > 2000) {
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
        if (currentState == AppState.PROCESSING) poBuffer.add(startPO)
    }

    private fun getVotedPO(): String? {
        synchronized(poBuffer) {
            if (poBuffer.size >= 2) {
                return poBuffer.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
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
    }

    private fun resetTrackingData() {
        prevPoints?.release()
        prevPoints = null
        prevGray?.release()
        prevGray = null

        synchronized(trackingLock) { trackingRect = null }

        velocityX = 0f
        velocityY = 0f
        isCoasting = false
        coastingCounter = 0
    }
}
