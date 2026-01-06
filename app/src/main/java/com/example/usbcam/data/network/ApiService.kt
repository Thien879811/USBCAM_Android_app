package com.example.usbcam.data.network

import com.example.usbcam.data.model.ShoeboxDetail
import com.example.usbcam.data.model.ShoeboxTotal
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("sync/detail")
    suspend fun syncDetail(@Body detail: ShoeboxDetail): Response<Void>

    @POST("sync/total")
    suspend fun syncTotal(@Body total: ShoeboxTotal): Response<Void>
}
