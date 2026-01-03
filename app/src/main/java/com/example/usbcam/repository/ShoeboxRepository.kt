package com.example.usbcam.repository

import android.util.Log
import com.example.usbcam.api.PoApiService
import com.example.usbcam.api.PoResponse
import com.example.usbcam.data.db.ShoeboxDao
import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxTotal
import com.example.usbcam.viewmodel.TimeSlotItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ShoeboxRepository(private val dao: ShoeboxDao, private val apiService: PoApiService) {

    // Used when logic is fully delegated to Repository (API + DB)
    suspend fun processScan(po: String, barcode: String): PoResponse? {
        try {
            val response = apiService.getPoDetailsSuspend(po, barcode)
            if (response.isSuccessful) {
                val body = response.body() ?: return null
                saveLocal(po, barcode, body)
                return body
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Used when API is called externally (e.g., in Fragment), just save DB
    suspend fun saveLocal(po: String, barcode: String, data: PoResponse) {
        // 2. Save Detail
        val detail =
                ShoeboxDetail(
                        RY = data.ry,
                        Size = data.size,
                        PO = po,
                        UPC = barcode,
                        Qty = 1,
                        DateScan = getCurrentTime(),
                        Modify = getCurrentTime(),
                        Article = data.article,
                        ShoeImage = data.articleImage,
                        User_Serial_Key = "DEVICE",
                        Line = data.lean,
                        Synced = 0
                )
        dao.insertDetail(detail)

        // 3. Update Total
        updateTotal(po, barcode, data)
    }

    private suspend fun updateTotal(po: String, upc: String, data: PoResponse) {
        val details = dao.getDetailsByUpc(upc).filter { it.PO == po }
        val totalQty = details.sumOf { it.Qty }

        val currentTotal = dao.getTotalByUpcAndPo(upc, po)
        val newTotal =
                currentTotal?.copy(Total_Qty_Scan = totalQty, Modify = getCurrentTime(), Synced = 0)
                        ?: ShoeboxTotal(
                                RY = data.ry,
                                Size = data.size,
                                PO = po,
                                UPC = upc,
                                Total_Qty_Scan = totalQty,
                                Total_Qty_ERP = 0,
                                Article = data.article,
                                DateScan = getCurrentTime(),
                                Modify = getCurrentTime(),
                                User_Serial_Key = "DEVICE",
                                Line = data.lean,
                                Synced = 0
                        )
        dao.insertTotal(newTotal)
    }

    suspend fun getLatestDetail(): ShoeboxDetail? {
        return dao.getLatestDetail()
    }

    suspend fun getDetailsByTimeSlot(start: String, end: String): List<ShoeboxDetail> {
        return dao.getDetailsInTimeRange(start, end)
    }

    suspend fun syncData() {
        val unsyncedDetails = dao.getUnsyncedDetails()
        Log.d("SyncWorker" , "SyncWorker auto update date form server")
        unsyncedDetails.forEach { detail ->
            try {
                val response = apiService.syncDetail(detail)
                if (response.isSuccessful) {
                    dao.updateDetailSynced(detail.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val unsyncedTotals = dao.getUnsyncedTotals()
        unsyncedTotals.forEach { total ->
            try {
                val response = apiService.syncTotal(total)
                if (response.isSuccessful) {
                    dao.updateTotalSynced(total.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getAllSlotsToday(): List<TimeSlotItem> {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdfDate.format(Date())

        val startDay = "$today 00:00:00"
        val endDay = "$today 23:59:00"

        val details = dao.getDetailsInTimeRange(startDay,endDay)

        // Khung giờ cố định (ví dụ 7:30 → 17:30)
        val slots = mutableListOf<TimeSlotItem>()
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 7)
        calendar.set(Calendar.MINUTE, 30)

        var index = 1

        repeat(10) {   // 10 khung giờ
            val start = calendar.time
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            val end = calendar.time

            val frameTime =
                "${start} - ${end}"

            val startStr = start.toDbString()
            val endStr = end.toDbString()

            val count = details.count {
                it.DateScan >= startStr && it.DateScan < endStr
            }

            slots.add(
                TimeSlotItem(
                    index = index++,
                    frameTime = frameTime,
                    target = 160,      // hoặc gọi API target
                    quantity = count
                )
            )
        }

        return slots
    }


    suspend fun getTargetByTimeSlot(): Int {
        val response = apiService.getTargetByLean("LHGG4G01")
        return  response.quantityTarget
    }
    private fun getCurrentTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }


    private val sdf = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    )

    private fun Date.toDbString(): String = sdf.format(this)



}
