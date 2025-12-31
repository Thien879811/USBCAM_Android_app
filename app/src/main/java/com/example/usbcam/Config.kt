package com.example.usbcam

object Config {
    // --- OPTICAL FLOW & KINEMATICS ---
    const val MAX_TRACKING_POINTS = 50       
    const val QUALITY_LEVEL = 0.01           
    const val MIN_DISTANCE = 10.0            
    const val FLOW_WIN_SIZE = 21             

    // --- PHYSICS ENGINE ---
    const val COASTING_MAX_FRAMES = 15       
    const val EDGE_THRESHOLD_PERCENT = 0.15f // Đã sửa thành Float
    const val VELOCITY_SMOOTHING = 0.7f      // Đã sửa thành Float (Fix lỗi biên dịch)
    const val MIN_VELOCITY_THRESHOLD = 0.5f  // Ngưỡng chết (Deadband) để tránh trôi khi đứng yên

    // --- ROBUSTNESS (SỰ BỀN BỈ) ---
    const val REPLENISH_THRESHOLD = 25       // Nếu số điểm < 25 -> Tự động tìm thêm điểm
    const val MAX_DEVIATION_PIXEL = 10.0f    // Nếu 1 điểm di chuyển lệch quá 10px so với đám đông -> Loại bỏ (Outlier)

    // --- LOGIC ---
    const val VOTING_BUFFER_SIZE = 3
    const val DEDUPLICATION_WINDOW_MS = 2000L
    const val MIN_PO_LENGTH = 5
}