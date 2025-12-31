package com.example.usbcam

object Config {
    // --- OPTICAL FLOW & KINEMATICS ---
    const val MAX_TRACKING_POINTS = 50
    const val QUALITY_LEVEL = 0.01
    const val MIN_DISTANCE = 10.0
    const val FLOW_WIN_SIZE = 21

    // --- PHYSICS ENGINE ---
    const val COASTING_MAX_FRAMES = 15
    const val EDGE_THRESHOLD_PERCENT = 0.15f
    const val VELOCITY_SMOOTHING = 0.7f
    const val MIN_VELOCITY_THRESHOLD = 0.5f

    // --- ROBUSTNESS (SỰ BỀN BỈ) ---
    const val REPLENISH_THRESHOLD = 25
    const val MAX_DEVIATION_PIXEL = 10.0f

    // --- LOGIC ---
    const val VOTING_BUFFER_SIZE = 3
    const val DEDUPLICATION_WINDOW_MS = 2000L
    const val MIN_PO_LENGTH = 5

    // --- PERFORMANCE & SCHEDULING ---
    const val MAX_PROCESSING_FPS = 10
    const val SCAN_THROTTLE_MS = 300L
}
