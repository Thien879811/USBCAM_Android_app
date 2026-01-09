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
