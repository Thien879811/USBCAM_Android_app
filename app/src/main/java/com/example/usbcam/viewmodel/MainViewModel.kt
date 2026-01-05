package com.example.usbcam.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.usbcam.api.PoApiService
import com.example.usbcam.api.PoResponse
import com.example.usbcam.data.db.AppDatabase
import com.example.usbcam.repository.ShoeboxRepository
import com.example.usbcam.worker.SyncWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class MainViewModel(private val repository: ShoeboxRepository) : ViewModel() {


    private val _totalScan = MutableLiveData<Int>()
    val totalScan: LiveData<Int> = _totalScan

    private val _timeSlotData = MutableLiveData<MainViewData>()
    val timeSlotData: LiveData<MainViewData> = _timeSlotData

    private val _targetData = MutableLiveData<TargetData>()
    val targetData: LiveData<TargetData> = _targetData
    // UI State for scan result
    private val _scanResult = MutableLiveData<PoResponse?>()
    val scanResult: LiveData<PoResponse?> = _scanResult

    fun handleScan(po: String, barcode: String) {
        viewModelScope.launch {
            val result = repository.processScan(po, barcode)
            _scanResult.postValue(result)
            // loadDataForCurrentTimeSlot()
            loadAllTimeSlots()
        }
    }

    suspend fun getLocalData(po: String, barcode: String): PoResponse? {
        return repository.getLocalPoResponse(po, barcode)
    }

    fun saveScanData(po: String, barcode: String, data: PoResponse) {
        Log.d("saveScanData", "API Success: $data")
        viewModelScope.launch { repository.saveLocal(po, barcode, data) }
        // loadDataForCurrentTimeSlot()
        loadAllTimeSlots()
    }

    fun startSyncWorker(context: Context) {
        val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val syncRequest =
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("SyncWork", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    private val _timeSlotList = MutableLiveData<List<TimeSlotItem>>()
    val timeSlotList: LiveData<List<TimeSlotItem>> = _timeSlotList

    fun loadAllTimeSlots() {
        viewModelScope.launch {
            val targetResponse = repository.getTargetByTimeSlot()

            val target = targetResponse?.quantityTarget ?: 140
            Log.d("loadAllTimeSlots", "${targetResponse?.quantityTarget}")
            val slots = repository.getAllSlotsToday(target)
            _timeSlotList.postValue(slots)

            targetResponse?.let {
                _targetData.postValue(
                        TargetData(
                                quantityTarget = it.quantityTarget,
                                scgs = it.scgs,
                                qtyByLean = it.qtyByLean
                        )
                )
            }
        }
    }

    fun loadDataForCurrentTimeSlot() {
        viewModelScope.launch {
            val (start, end) = calculateTimeSlot()
            val details = repository.getDetailsByTimeSlot(start, end)

            val targetResponse = repository.getTargetByTimeSlot()
            val target = targetResponse?.quantityTarget ?: 160

            val frameTime = "${start} - ${end}"

            _timeSlotData.postValue(
                    MainViewData(frameTime = frameTime, target = target, quantity = details.size)
            )
        }
    }

    fun loadTarget() {
        viewModelScope.launch {
            val target = repository.getTargetByTimeSlot()

            target?.let {
                _targetData.postValue(
                        TargetData(
                                quantityTarget = it.quantityTarget,
                                scgs = it.scgs,
                                qtyByLean = it.qtyByLean
                        )
                )
            }
        }
    }

    fun loadTotal(){
        viewModelScope.launch {
            val total = repository.getAllToday()
            _totalScan.postValue(
                total
            )
        }
    }


    private fun formatTime(time: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(time))
    }

    private fun calculateTimeSlot(): Pair<String, String> {
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMinute = cal.get(Calendar.MINUTE)

        // Logic: 7:30 - 8:30 etc.
        var startHour = currentHour

        // If 8:15 -> 7:30 - 8:30 (start 7)
        // If 8:45 -> 8:30 - 9:30 (start 8)
        if (currentMinute < 30) {
            startHour -= 1
        }

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val startStr = String.format("%s %02d:30:00", date, startHour)
        val endStr = String.format("%s %02d:30:00", date, startHour + 1)

        return Pair(startStr, endStr)
    }
}

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val database = AppDatabase.getDatabase(application.applicationContext)
            val apiService = PoApiService.create()
            val repository = ShoeboxRepository(database.shoeboxDao(), apiService)
            @Suppress("UNCHECKED_CAST") return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
