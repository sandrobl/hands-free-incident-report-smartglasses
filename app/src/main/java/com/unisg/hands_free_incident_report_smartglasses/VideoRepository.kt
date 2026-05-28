// File: app/src/main/java/com/handsfree_incident_report_mobile/data/VideoRepository.kt
package com.unisg.hands_free_incident_report_smartglasses

import android.Manifest
import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import com.auth0.android.authentication.storage.CredentialsManager
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class VideoRepository(
    val context: Context,
    private val credentialsManager: CredentialsManager,
) {
    private val httpClient = OkHttpClient()

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun encryptAndUploadVideo(videoUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val encryptedData = encryptVideo(videoUri)
            val token = credentialsManager.awaitCredentials().accessToken
            
            // Get reportId from the backend's new_report endpoint
            val reportId = createReport(token)
            
            val location = fetchLocation()
            val orientation = fetchOrientation()
            uploadVideo(token, reportId, encryptedData, location, orientation)
            Unit
        }
    }

    private suspend fun encryptVideo(uri: Uri): EncryptedVideoResult {
        val publicKey = fetchPublicKey()

        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

        val videoBytes = context.contentResolver.openInputStream(uri)?.readBytes()
            ?: throw IllegalStateException("Failed to read video at $uri")

        val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        }
        val encryptedVideo = iv + aesCipher.doFinal(videoBytes)

        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
        }
        val encryptedSessionKey = Base64.encodeToString(rsaCipher.doFinal(aesKey), Base64.NO_WRAP)

        return EncryptedVideoResult(encryptedVideo, encryptedSessionKey)
    }

    private suspend fun createReport(token: String): String = withContext(Dispatchers.IO) {
        val connection = URL("$BASE_URL/new_report/").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("accept", "application/json")

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.readText()
            throw Exception("new_report failed ($responseCode): $error")
        }

        val body = connection.inputStream.bufferedReader().readText()
        JSONObject(body).getString("report_id")
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun fetchLocation(): Location {
        return LocationServices
            .getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .await()
            ?: throw IllegalStateException("Location unavailable — GPS may be disabled")
    }

    private suspend fun fetchOrientation(): DeviceOrientation = callbackFlow{
        val client = LocationServices.getFusedOrientationProviderClient(context)
        val request = DeviceOrientationRequest.Builder(DeviceOrientationRequest.OUTPUT_PERIOD_DEFAULT).build()
        val executor = Executors.newSingleThreadExecutor()

        val listener = DeviceOrientationListener{orientation -> trySend(orientation)}

        client.requestOrientationUpdates(request,executor,listener)

        awaitClose{
            client.removeOrientationUpdates(listener)
        }

    }.first()

    private suspend fun uploadVideo(
        token: String,
        reportId: String,
        data: EncryptedVideoResult,
        location: Location,
        orientation: DeviceOrientation,
    ) = withContext(Dispatchers.IO) {
        val createdAt = SimpleDateFormat("dd.MM.yy", Locale("de", "CH")).format(Date())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("encrypted_session_key", data.encryptedSessionKey)
            .addFormDataPart("latitude", location.latitude.toString())
            .addFormDataPart("longitude", location.longitude.toString())
            .addFormDataPart("orientation", orientation.headingDegrees.toString())
            .addFormDataPart("created_at", createdAt)
            .addFormDataPart(
                "encrypted_video",
                "video.enc",
                data.encryptedVideo.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/upload_video/?report_id=$reportId")
            .post(requestBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Upload failed (${response.code}): ${response.body?.string()}")
            }
            Log.d(TAG, "Upload complete: ${response.body?.string()}")
        }
    }

    private suspend fun fetchPublicKey(): PublicKey = withContext(Dispatchers.IO) {
        val pem = (URL("$BASE_URL/public_key").openConnection() as HttpsURLConnection)
            .inputStream.bufferedReader().readText()
        parsePemPublicKey(pem)
    }

    private fun parsePemPublicKey(pem: String): PublicKey {
        val pemBody = pem.lines()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
        val keyBytes = Base64.decode(pemBody, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
    }


    data class EncryptedVideoResult(
        val encryptedVideo: ByteArray,
        val encryptedSessionKey: String,
    )

    companion object {
        private const val TAG = "VideoRepository"
        private const val BASE_URL = "https://api.hands-free-incident-report.ch"
    }
}