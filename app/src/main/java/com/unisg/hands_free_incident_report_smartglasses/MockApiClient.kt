package com.unisg.hands_free_incident_report_smartglasses

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object MockApiClient {

    private const val TAG = "MockApiClient"
    private const val MOCK_ENDPOINT = "https://api.example.com/incidents"

    suspend fun upload(videoFile: File): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Uploading report to $MOCK_ENDPOINT")
            Log.d(TAG, "File: ${videoFile.name}, Size: ${videoFile.length() / 1024} KB")

            // Simulate network delay
            delay(2000)

            // Mock response
            val mockIncidentId = "INC-${System.currentTimeMillis()}"
            Log.d(TAG, "Upload successful — Incident ID: $mockIncidentId")

            Result.success(mockIncidentId)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }
}