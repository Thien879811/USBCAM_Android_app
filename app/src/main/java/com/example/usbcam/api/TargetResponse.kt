package com.example.usbcam.api

import com.google.gson.annotations.SerializedName

data class TargetResponse(
    @SerializedName("Qty") val qtyByLean: String?,
    @SerializedName("scgs") val scgs: String?,
    @SerializedName("Qty_Target") val quantityTarget: Int,
)
