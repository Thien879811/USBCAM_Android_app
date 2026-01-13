package com.example.usbcam

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.usbcam.api.PoApiService
import com.example.usbcam.utils.UpdateManager
import kotlinx.coroutines.launch

/**
 * Fragment responsible for checking and performing application updates. Configuration sliders have
 * been removed to focus solely on updates.
 */
class ConfigFragment : Fragment() {

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvCurrentVersion = view.findViewById<TextView>(R.id.tvCurrentVersion)

        // Display current version
        try {
            val pInfo =
                    requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            tvCurrentVersion.text = "Phiên bản hiện tại: ${pInfo.versionName}"
        } catch (e: Exception) {
            e.printStackTrace()
        }

        view.findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate() }
        view.findViewById<Button>(R.id.btnExit).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupConfigurationUI(view)
    }

    private fun setupConfigurationUI(view: View) {
        val container = view.findViewById<android.widget.LinearLayout>(R.id.containerSettings)

        // 1. Beep Volume
        addSlider(
                container,
                "Beep Volume",
                "Âm lượng phản hồi (0-100)",
                Config.BEEP_VOLUME.toFloat(),
                0f,
                100f,
                1f
        ) { value -> Config.BEEP_VOLUME = value.toInt() }

        // 2. KNN History
        addSlider(
                container,
                "KNN History",
                "Số khung hình học nền (Mặc định: 200)",
                Config.KNN_HISTORY.toFloat(),
                10f,
                500f,
                10f
        ) { value ->
            Config.KNN_HISTORY = value.toInt()
            Config.isKnnConfigChanged = true
        }

        // 3. KNN Dist Threshold
        addSlider(
                container,
                "KNN Dist Threshold",
                "Ngưỡng phân biệt nền (Mặc định: 150.0). Cao = ít nhiễu hơn.",
                Config.KNN_DIST_2_THRESHOLD.toFloat(),
                10f,
                1000f,
                10f
        ) { value ->
            Config.KNN_DIST_2_THRESHOLD = value.toDouble()
            Config.isKnnConfigChanged = true
        }

        // 4. KNN Disappear Percent
        addSlider(
                container,
                "KNN Disappear %",
                "Tỷ lệ biến mất (Mặc định: 0.35). Dưới % này coi như Gone.",
                Config.KNN_DISAPPEAR_PERCENT,
                0.01f,
                1.0f,
                0.01f
        ) { value -> Config.KNN_DISAPPEAR_PERCENT = value }
    }

    private fun addSlider(
            container: android.widget.LinearLayout,
            label: String,
            description: String,
            initialValue: Float,
            min: Float,
            max: Float,
            step: Float,
            onChange: (Float) -> Unit
    ) {
        val itemView = layoutInflater.inflate(R.layout.item_config_slider, container, false)
        val tvLabel = itemView.findViewById<TextView>(R.id.tvLabel)
        val tvValue = itemView.findViewById<TextView>(R.id.tvValue)
        val tvDesc = itemView.findViewById<TextView>(R.id.tvDescription)
        val slider = itemView.findViewById<com.google.android.material.slider.Slider>(R.id.slider)

        tvLabel.text = label
        tvDesc.text = description
        tvValue.text = formatValue(initialValue)

        slider.valueFrom = min
        slider.valueTo = max
        slider.stepSize = step
        slider.value = initialValue.coerceIn(min, max)

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvValue.text = formatValue(value)
                onChange(value)
            }
        }

        container.addView(itemView)
    }

    private fun formatValue(value: Float): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format("%.2f", value)
        }
    }

    private fun checkUpdate() {
        val updateManager = UpdateManager(requireContext())
        val apiService = PoApiService.create()

        lifecycleScope.launch {
            try {
                val response = apiService.checkUpdate()
                if (response.isSuccessful && response.body() != null) {
                    val updateInfo = response.body()!!
                    val currentVersionCode =
                            requireContext()
                                    .packageManager
                                    .getPackageInfo(requireContext().packageName, 0)
                                    .let {
                                        if (android.os.Build.VERSION.SDK_INT >=
                                                        android.os.Build.VERSION_CODES.P
                                        ) {
                                            it.longVersionCode.toInt()
                                        } else {
                                            @Suppress("DEPRECATION") it.versionCode
                                        }
                                    }

                    if (updateInfo.versionCode > currentVersionCode) {
                        AlertDialog.Builder(requireContext())
                                .setTitle("Cập nhật mới")
                                .setMessage(
                                        "Đã có phiên bản ${updateInfo.versionName}. Bạn có muốn cập nhật không?\n\n${updateInfo.description ?: ""}"
                                )
                                .setPositiveButton("Cập nhật") { dialog: DialogInterface, which: Int
                                    ->
                                    updateManager.startUpdate(updateInfo.apkUrl)
                                }
                                .setNegativeButton("Để sau", null)
                                .show()
                    } else {
                        Toast.makeText(
                                        requireContext(),
                                        "Ứng dụng đã ở phiên bản mới nhất",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                } else {
                    Toast.makeText(
                                    requireContext(),
                                    "Không thể kiểm tra cập nhật",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
