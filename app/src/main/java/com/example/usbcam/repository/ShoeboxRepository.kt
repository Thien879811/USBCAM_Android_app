package com.example.usbcam.repository

import android.util.Log
import com.example.usbcam.api.PoApiService
import com.example.usbcam.api.PoResponse
import com.example.usbcam.api.TargetResponse
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
                if (currentTotal != null) {
                    currentTotal.copy(
                            Total_Qty_ERP = data.quantity ?: 0,
                            Total_Qty_Scan = totalQty,
                            Modify = getCurrentTime(),
                            Synced = 0
                    )
                } else {
                    ShoeboxTotal(
                            RY = data.ry,
                            Size = data.size,
                            PO = po,
                            UPC = upc,
                            Total_Qty_Scan = totalQty,
                            Total_Qty_ERP = data.quantity ?: 0,
                            Article = data.article,
                            DateScan = getCurrentTime(),
                            Modify = getCurrentTime(),
                            User_Serial_Key = "DEVICE",
                            Line = data.lean,
                            Synced = 0
                    )
                }
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
    suspend fun getAllSlotsToday(target: Int): List<TimeSlotItem> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dbFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val today = dateFormat.format(Date())

        val startDay = "$today 00:00:00"
        val endDay = dateFormat.format(
            Calendar.getInstance().apply {
                time = Date()
                add(Calendar.DAY_OF_MONTH, 1)
            }.time
        ) + " 00:00:00"

        val details = dao.getDetailsInTimeRange(startDay, endDay)

        val slots = mutableListOf<TimeSlotItem>()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
        }

        var index = 1

        repeat(10) {
            val start = calendar.time
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            val end = calendar.time

            val frameTime = "${timeFormat.format(start)} - ${timeFormat.format(end)}"

            val count = details.count {
                val scanTime = dbFormat.parse(it.DateScan) ?: return@count false
                scanTime >= start && scanTime < end
            }

            if (count > 0) {
                slots.add(
                    TimeSlotItem(
                        index = index++,
                        frameTime = frameTime,
                        target = target,
                        quantity = count
                    )
                )
            }
        }

        return slots
    }


    suspend fun getAllToday(): Int {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdfDate.format(Date())

        val startDay = "$today 00:00:00"

        val endDay = sdfDate.format(
            Calendar.getInstance().apply {
                time = Date()
                add(Calendar.DAY_OF_MONTH, 1)
            }.time
        ) + " 00:00:00"

        val details = dao.getDetailsInTimeRange(startDay, endDay)
        return details.size
    }


    suspend fun getTargetByTimeSlot(): TargetResponse? {
        return try {
            val response = apiService.getTargetByLean("LHGG4G01")

            if (response.isSuccessful) {
                Log.d("getTargetByTimeSlot", "${response.body()}")
                response.body()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("getTargetByTimeSlot", "API error", e)
            null
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun Date.toDbString(): String = sdf.format(this)

    suspend fun getLocalPoResponse(po: String, barcode: String): PoResponse? {
        val total = dao.getTotalByUpcAndPo(barcode, po) ?: return null
        val details = dao.getDetailsByUpc(barcode).filter { it.PO == po }
        val image = details.firstOrNull()?.ShoeImage

        return PoResponse(
                upc = total.UPC,
                size = total.Size,
                po = total.PO,
                ry = total.RY,
                article = total.Article,
                articleImage = image,
                quantity = total.Total_Qty_Scan,
                zbln = null, // Not stored locally
                khpo = null, // Not stored locally
                country = null, // Not stored locally
                psdt = null, // Not stored locally
                pedt = null, // Not stored locally
                qtyOrder = total.Total_Qty_ERP,
                remainInternal =
                        if (total.Total_Qty_ERP > 0) total.Total_Qty_ERP - total.Total_Qty_Scan
                        else 0,
                doneInternal = total.Total_Qty_Scan,
                lean = total.Line
        )
    }
}
