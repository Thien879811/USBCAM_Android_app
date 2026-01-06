package com.example.usbcam.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.usbcam.api.PoApiService
import com.example.usbcam.data.db.AppDatabase
import com.example.usbcam.repository.ShoeboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                val database = AppDatabase.getDatabase(applicationContext)
                val apiService = PoApiService.create()
                val repository = ShoeboxRepository(database.shoeboxDao(), apiService)
                Log.d("SyncWorker" , "SyncWorker auto update date form server")
                try {
                    repository.syncData()
                    Result.success()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Result.retry()
                }
            }
}
