package com.example.usbcam.api

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface PoApiService {
    @GET("api/select-po")
    fun getPoDetails(
        @Query("po") po: String,
        @Query("barcode") barcode: String
    ): Call<PoResponse>

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
