package com.unisg.hands_free_incident_report_smartglasses

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.activity.result.launch
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class HomeViewModel(private val repository: VideoRepository) : ViewModel() {
    private val _uploadStatus = MutableLiveData<Result<Unit>?>()
    val uploadStatus: LiveData<Result<Unit>?> = _uploadStatus
    @SuppressLint("MissingPermission")
    fun sendVideo(uri: Uri) {
        viewModelScope.launch {
            Log.d("HomeViewModel", "Sending video with URI: $uri")
            val result = repository.encryptAndUploadVideo(uri)
            _uploadStatus.value = result
        }
    }

    val orientation = callbackFlow {
        val client = LocationServices.getFusedOrientationProviderClient(repository.context)
        val request = DeviceOrientationRequest.Builder(DeviceOrientationRequest.OUTPUT_PERIOD_DEFAULT).build()
        val listener = DeviceOrientationListener { trySend(it) }
        client.requestOrientationUpdates(request, Executors.newSingleThreadExecutor(), listener)
        awaitClose { client.removeOrientationUpdates(listener) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun resetStatus() { _uploadStatus.value = null }
}