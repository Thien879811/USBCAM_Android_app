package com.example.usbcam.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Handles APK updates for Android 14+. */
class UpdateManager(private val context: Context) {

    private val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long = -1

    /** Start downloading APK from a URL. */
    fun startUpdate(apkUrl: String) {
        if (apkUrl.isEmpty()) return

        // 1. Check if we can install unknown apps (Mandatory for Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                                context,
                                "Vui lòng cấp quyền cài đặt ứng dụng từ nguồn không xác định",
                                Toast.LENGTH_LONG
                        )
                        .show()
                val intent =
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                context.startActivity(intent)
                return
            }
        }

        // 2. Setup Download Request
        val request =
                DownloadManager.Request(Uri.parse(apkUrl)).apply {
                    setTitle("Đang tải cập nhật")
                    setDescription("Hệ thống đếm Box Counter")
                    setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "usbcam_update.apk"
                    )
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

        // Delete old file if exists
        val oldFile =
                File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "usbcam_update.apk"
                )
        if (oldFile.exists()) oldFile.delete()

        downloadId = downloadManager.enqueue(request)
        Toast.makeText(context, "Bắt đầu tải bản cập nhật...", Toast.LENGTH_SHORT).show()

        // 3. Register Receiver for completion
        val onComplete =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (downloadId == id) {
                            installApk()
                            context.unregisterReceiver(this)
                        }
                    }
                }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApk() {
        val downloadFolder =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadFolder, "usbcam_update.apk")

        if (file.exists()) {
            val uri =
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("UpdateManager", "Cài đặt thất bại", e)
                Toast.makeText(context, "Cài đặt thất bại: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Không tìm thấy file cài đặt", Toast.LENGTH_SHORT).show()
        }
    }
}
