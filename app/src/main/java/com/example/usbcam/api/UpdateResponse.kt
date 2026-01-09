package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

data class UpdateResponse(
        @SerializedName("versionCode") val versionCode: Int,
        @SerializedName("versionName") val versionName: String,
        @SerializedName("apkUrl") val apkUrl: String,
        @SerializedName("description") val description: String? = null
)
