package com.example.usbcam.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Data_Shoebox_Detail")
data class ShoeboxDetail(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Qty: Int,
    val DateScan: String, // Format: yyyy-MM-dd HH:mm:ss
    val Modify: String?,
    val Article: String?,
    val ShoeImage: String?,
    val User_Serial_Key: String?,
    val Line: String?,
    var Synced: Int = 0 // 0: Not synced, 1: Synced
)

@Entity(tableName = "Data_Shoebox_Total")
data class ShoeboxTotal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val RY: String?,
    val Size: String?,
    val PO: String?,
    val UPC: String?,
    val Total_Qty_Scan: Int,
    val Total_Qty_ERP: Int,
    val Article: String?,
    val DateScan: String?,
    val Modify: String?,
    val User_Serial_Key: String?,
    val Line: String?,
    var Synced: Int = 0
)
