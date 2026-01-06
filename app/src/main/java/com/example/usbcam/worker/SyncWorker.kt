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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class SyncWorker(context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    withTimeout(15000L) { // 15 second timeout
                        val database = AppDatabase.getDatabase(applicationContext)
                        val apiService = PoApiService.create()
                        val repository = ShoeboxRepository(database.shoeboxDao(), apiService)
                        Log.d("SyncWorker", "SyncWorker auto update date form server")
                        repository.syncData()
                        Result.success()
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e("SyncWorker", "Sync timeout - will retry on next run")
                    Result.success()
                } catch (e: Exception) {
                    Log.e("SyncWorker", "Sync error: ${e.message}")
                    e.printStackTrace()
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Log.e("SyncWorker", "Max retries reached, failing gracefully")
                        Result.success()
                    }
                }
            }
}
