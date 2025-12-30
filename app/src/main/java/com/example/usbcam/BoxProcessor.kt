package com.example.usbcam

import com.example.usbcam.AppState
import com.example.usbcam.Config

class BoxProcessor {
    @Volatile var currentState = AppState.IDLE
    @Volatile var currentBarcode: String? = null
    @Volatile var currentPO: String? = null
    
    // Motion metric
    @Volatile var currentPixelDiff = 0.0
    
    // Debounce tracking
    private var lastBarcodeSeenTime: Long = 0

    /**
     * Calculates the average pixel intensity difference between two Y-channel buffers.
     * Assumes nv21 arrays where the first width*height bytes are Y.
     */
    fun processFrameDifference(prevY: ByteArray?, currY: ByteArray, width: Int, height: Int): Double {
        if (prevY == null || prevY.size != currY.size) return 0.0

        var sumDiff = 0L
        val pixelCount = width * height
        
        // Safety check loop limit
        val loopLimit = Math.min(pixelCount, currY.size)

        // Stride for speed
        var i = 0
        while (i < loopLimit) {
            val p1 = prevY[i].toInt() and 0xFF
            val p2 = currY[i].toInt() and 0xFF
            sumDiff += Math.abs(p1 - p2)
            i += 4
        }
        
        val parsedPixels = pixelCount / 4
        return if (parsedPixels > 0) sumDiff.toDouble() / parsedPixels else 0.0
    }

    fun updateLogic(pixelDiff: Double, isBarcodeDetected: Boolean) {
        currentPixelDiff = pixelDiff
        val now = System.currentTimeMillis()

        when (currentState) {
            AppState.IDLE -> {
                if (pixelDiff > Config.THRESH_MOTION) {
                    currentState = AppState.SCANNING
                    currentBarcode = null
                    currentPO = null
                }
            }
            AppState.SCANNING -> {
                if (!currentBarcode.isNullOrEmpty() && !currentPO.isNullOrEmpty()) {
                    triggerValidation()
                }
            }
            AppState.VALIDATING -> { }
            AppState.STABLE, AppState.VALIDATED -> {
                if (pixelDiff > Config.THRESH_MOTION) {
                    currentState = AppState.RESET
                }
            }
            AppState.RESET -> {
                // Debounce Logic:
                if (currentBarcode == null) {
                    // Check timeout
                    if ((now - lastBarcodeSeenTime) > Config.RESET_DEBOUNCE_MS) {
                        currentState = AppState.IDLE
                        currentPO = null
                    }
                } else {
                    // Revalidate if stable
                    if (pixelDiff < Config.THRESH_STABILITY) {
                         currentState = AppState.VALIDATED
                    }
                }
            }
        }
    }

    private fun triggerValidation() {
        currentState = AppState.VALIDATING
        val isValid = !currentBarcode.isNullOrEmpty() && !currentPO.isNullOrEmpty()
        
        if (isValid) {
            currentState = AppState.VALIDATED 
        } else {
            currentState = AppState.SCANNING
        }
    }

    fun updateBarcode(rawValue: String?) {
        if (rawValue != null) {
             currentBarcode = rawValue
             lastBarcodeSeenTime = System.currentTimeMillis()
        }
    }
    
    fun clearBarcode() {
        // We only clear the value, we DO NOT reset the timestamp here necessarily,
        // or we do? No, timestamp marks the LAST TIME we saw it.
        // So just nulling the value is correct.
        currentBarcode = null
    }

    fun updatePO(text: String?) {
        if (text != null) {
            currentPO = text
        }
    }
    
    fun resetState() {
        currentState = AppState.IDLE
        currentBarcode = null
        currentPO = null
    }
}
