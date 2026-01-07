package com.example.usbcam

object Config {
    // --- OPTICAL FLOW & KINEMATICS ---
    // --- OPTICAL FLOW & KINEMATICS ---
    const val MAX_TRACKING_POINTS = 50
    const val QUALITY_LEVEL = 0.005
    const val MIN_DISTANCE = 5.0

    const val FLOW_WIN_SIZE = 51

    // --- PHYSICS ENGINE ---
    const val COASTING_MAX_FRAMES = 15
    const val EDGE_THRESHOLD_PERCENT = 0.15f

    const val VELOCITY_SMOOTHING = 0.5f

    const val MIN_VELOCITY_THRESHOLD = 0.3f // Reduced sensitivity to ignore jitter
    const val MIN_ENTRY_VELOCITY = 2.0f // Strict Directional Velocity

    // --- MOTION ALLOCATION ---
    const val MAX_VERTICAL_VELOCITY = 8.0f // Increased tolerance for vertical shake
    const val HORIZONTAL_DOMINANCE_RATIO = 1.5f // Relaxed horizontal check

    // --- ROBUSTNESS ---
    const val REPLENISH_THRESHOLD = 25
    const val MAX_DEVIATION_PIXEL = 10.0f
    const val EDGE_INTERRUPT_ZONE_PERCENT = 0.20f // Left/Right 20%
    const val ENTRY_GRACE_FRAMES = 10

    // --- LOGIC ---
    const val VOTING_BUFFER_SIZE = 3
    const val DEDUPLICATION_WINDOW_MS = 3000L
    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 15
    const val PO_TIMEOUT_MS = 8000L

    // --- PERFORMANCE & THERMAL CONTROL ---
    const val MAX_PROCESSING_FPS = 20
    const val SCAN_THROTTLE_MS = 500L

    // --- AUDIO ---
    const val BEEP_VOLUME = 50 // 0-100
}
