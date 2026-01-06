package com.example.usbcam.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxTotal

@Dao
interface ShoeboxDao {

    // --- Detail Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetail(detail: ShoeboxDetail): Long

    @Query("SELECT * FROM Data_Shoebox_Detail ORDER BY id DESC LIMIT 1")
    suspend fun getLatestDetail(): ShoeboxDetail?

    @Query("SELECT * FROM Data_Shoebox_Detail WHERE Synced = 0")
    suspend fun getUnsyncedDetails(): List<ShoeboxDetail>

    @Query("UPDATE Data_Shoebox_Detail SET Synced = 1 WHERE id = :id")
    suspend fun updateDetailSynced(id: Long)

    @Query("SELECT * FROM Data_Shoebox_Detail WHERE UPC = :upc")
    suspend fun getDetailsByUpc(upc: String): List<ShoeboxDetail>

    // --- Total Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTotal(total: ShoeboxTotal)

    @Query("SELECT * FROM Data_Shoebox_Total WHERE UPC = :upc AND PO = :po ORDER BY id DESC LIMIT 1")
    suspend fun getTotalByUpcAndPo(upc: String, po: String): ShoeboxTotal?
    
    @Query("SELECT * FROM Data_Shoebox_Total WHERE Synced = 0")
    suspend fun getUnsyncedTotals(): List<ShoeboxTotal>

    @Query("UPDATE Data_Shoebox_Total SET Synced = 1 WHERE id = :id")
    suspend fun updateTotalSynced(id: Long)

    // --- Stats for UI ---
    @Query("SELECT * FROM Data_Shoebox_Detail WHERE DateScan BETWEEN :startTime AND :endTime")
    suspend fun getDetailsInTimeRange(startTime: String, endTime: String): List<ShoeboxDetail>
}
