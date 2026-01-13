package com.example.usbcam

object Config {
    // --- OPTICAL FLOW & KINEMATICS ---
    const val MAX_TRACKING_POINTS = 50
    const val QUALITY_LEVEL = 0.005
    const val MIN_DISTANCE = 5.0

    const val FLOW_WIN_SIZE = 31 // Cân bằng giữa độ chính xác và tốc độ

    // --- PHYSICS ENGINE ---
    const val COASTING_MAX_FRAMES = 15
    const val EDGE_THRESHOLD_PERCENT = 0.15f

    const val VELOCITY_SMOOTHING = 0.4f // Phản ứng nhanh hơn với thay đổi vận tốc

    const val MIN_VELOCITY_THRESHOLD = 0.2f // Nhạy hơn để bắt chuyển động từ trạng thái nghỉ
    const val MIN_ENTRY_VELOCITY = 1.2f // Ngưỡng vận tốc tối thiểu để bắt đầu ENTERING

    const val IDLE_MOTION_PERCENT = 0.05f // 5% diện tích thay đổi -> coi là có chuyển động

    // --- MOTION ALLOCATION ---
    const val MAX_VERTICAL_VELOCITY = 8.0f // Increased tolerance for vertical shake
    const val HORIZONTAL_DOMINANCE_RATIO = 1.5f // Relaxed horizontal check

    // --- ROBUSTNESS ---
    const val REPLENISH_THRESHOLD = 25
    const val MAX_DEVIATION_PIXEL = 10.0f
    const val EDGE_INTERRUPT_ZONE_PERCENT = 0.20f // Left/Right 20%
    const val ENTRY_GRACE_FRAMES = 25 // Tăng từ 10 để hệ thống ổn định KNN phông nền

    // --- LOGIC ---
    const val DEDUPLICATION_WINDOW_MS = 3000L
    const val MIN_PO_LENGTH = 5
    const val MAX_PO_LENGTH = 15
    const val PO_TIMEOUT_MS = 8000L
    const val ERROR_LOCKED_TIMEOUT_MS = 3000L

    // --- PERFORMANCE & THERMAL CONTROL ---
    const val MAX_PROCESSING_FPS = 20
    const val SCAN_THROTTLE_MS = 250L

    // --- AUDIO ---
    var BEEP_VOLUME = 50 // 0-100

    // --- KNN DETECTION (Phát hiện vật thể biến mất) ---
    @Volatile var isKnnConfigChanged = false

    // Số lượng khung hình dùng để học phông nền (Background model). Giá trị cao sẽ lọc nhiễu tốt
    // nhưng phản ứng chậm.
    var KNN_HISTORY = 70

    // Ngưỡng khoảng cách để phân biệt pixel là vật thể (foreground) hay phông nền (background).
    var KNN_DIST_2_THRESHOLD = 70.0

    // Ngưỡng % pixel chuyển động trong vùng theo dõi. Nếu dưới 15%, coi như vật thể đã đi khỏi.
    var KNN_DISAPPEAR_PERCENT = 0.50f

    // --- IMAGE ADJUSTMENT ---
    const val USE_CLAHE = true
    const val CLAHE_CLIP_LIMIT = 2.0
    const val CLAHE_TILE_SIZE = 8.0
    const val USE_SHARPEN = true
}
