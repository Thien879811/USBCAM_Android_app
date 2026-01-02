package com.example.usbcam.api

import com.google.android.gms.internal.mlkit_vision_text_bundled_common.zbln
import com.google.gson.annotations.SerializedName
import java.nio.ByteOrder

data class PoResponse(
    @SerializedName("UPC") val upc: String?,
    @SerializedName("SIZE") val size: String?,
    @SerializedName("PO") val po: String?,
    @SerializedName("RY") val ry: String?,
    @SerializedName("Article") val article: String?,
    @SerializedName("Article_Image") val articleImage: String?,
    @SerializedName("Quantity") val quantity: Int?,
    @SerializedName("zlbh") val zbln: String?,
    @SerializedName("khpo") val khpo: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("psdt") val psdt: String?,
    @SerializedName("pedt") val pedt: String?,
    @SerializedName("QTYOrder") val qtyOrder: Int?,
    @SerializedName("Remain") val remainInternal: Int?,
    @SerializedName("Done") val doneInternal: Int?,
    @SerializedName("lean") val  lean: String?,
)
