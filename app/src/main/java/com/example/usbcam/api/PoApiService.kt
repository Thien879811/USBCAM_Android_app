package com.example.usbcam.api

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface PoApiService {
    @GET("api/select-po")
    fun getPoDetails(@Query("po") po: String, @Query("barcode") barcode: String): Call<PoResponse>

    @GET("api/select-po")
    suspend fun getPoDetailsSuspend(
            @Query("po") po: String,
            @Query("barcode") barcode: String
    ): retrofit2.Response<PoResponse>

    @GET("api/select-po") fun getPoDetails(@Query("po") po: String): Call<PoResponse>

    @GET("api/target-value")
    suspend fun getTargetByLean(
        @Query("depno") depno: String
    ):TargetResponse

    @retrofit2.http.POST("api/sync/detail")
    suspend fun syncDetail(
            @retrofit2.http.Body detail: com.example.usbcam.data.model.ShoeboxDetail
    ): retrofit2.Response<Void>

    @retrofit2.http.POST("api/sync/total")
    suspend fun syncTotal(
            @retrofit2.http.Body total: com.example.usbcam.data.model.ShoeboxTotal
    ): retrofit2.Response<Void>

    companion object {
        private const val BASE_URL = "http://192.168.30.169:3000/"

        fun create(): PoApiService {
            return Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(PoApiService::class.java)
        }
    }
}
