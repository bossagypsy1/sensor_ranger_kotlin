package com.sensorranger.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    data class Result(val success: Boolean, val status: Int?, val error: String?)

    suspend fun post(url: String, body: String, bearerToken: String?): Result {
        val first = doPost(url, body, bearerToken)
        if (first.success) return first
        // Single retry after 2 seconds
        kotlinx.coroutines.delay(2000)
        return doPost(url, body, bearerToken)
    }

    private fun doPost(url: String, body: String, bearerToken: String?): Result {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .apply { if (bearerToken != null) addHeader("Authorization", "Bearer $bearerToken") }
                .post(body.toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            response.close()
            Result(success = response.isSuccessful, status = response.code, error = null)
        } catch (e: Exception) {
            val msg = if (e is java.net.SocketTimeoutException) "Timeout (20s)" else e.message ?: "Unknown error"
            Result(success = false, status = null, error = msg)
        }
    }
}
