package com.unisg.hands_free_incident_report_smartglasses

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class WearablesManager(private val context: Context) {

    private var session: DeviceSession? = null
    private var cameraStream: Stream? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var errorJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var sessionStateJob: Job? = null

    private val deviceSelector = AutoDeviceSelector()

    init {
        // Keep the SDK warm by monitoring discovery in the background
        scope.launch {
            deviceSelector.activeDeviceFlow().collect { device ->
                if (device != null) Log.d("WearablesManager", "Warm Link: Active device $device")
            }
        }
    }

    suspend fun startStream(onFrame: (VideoFrame) -> Unit, onStop: () -> Unit): Result<Unit> {
        Log.d("WearablesManager", "startStream requested")
        
        // Ensure Capability Jobs are dead before starting new ones
        videoJob?.cancel()
        stateJob?.cancel()
        errorJob?.cancel()

        // Create session if needed
        if (session == null || session?.state?.value == DeviceSessionState.STOPPED) {
            val result = Wearables.createSession(deviceSelector)
            val createdSession = result.getOrNull()
            if (createdSession == null) {
                return Result.failure(Exception("NO_DEVICE_FOUND"))
            }
            session = createdSession
            sessionErrorJob?.cancel()
            sessionErrorJob = scope.launch {
                createdSession.errors.collect { error ->
                    Log.e("WearablesManager", "Session error: ${error.description}")
                    onStop()
                    stopStream()
                }
            }
            createdSession.start()
        }

        // Wait for hardware handshake
        try {
            withTimeout(8000) {
                session?.state?.first { it == DeviceSessionState.STARTED }
            }
        } catch (e: Exception) {
            stopStream()
            return Result.failure(e)
        }

        // Add Capability
        val streamResult = session?.addStream(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)
        )
        
        var failureReason: String? = null
        streamResult?.onSuccess { addedStream ->
            cameraStream = addedStream
            
            // Collect frames on DEFAULT thread to avoid UI jank
            videoJob = CoroutineScope(Dispatchers.Default).launch {
                addedStream.videoStream.collect { onFrame(it) }
            }
            
            stateJob = scope.launch {
                addedStream.state.collect { state ->
                    if (state == StreamState.STOPPED || state == StreamState.CLOSED) {
                        onStop()
                        stopStream()
                    }
                }
            }
            
            errorJob = scope.launch {
                addedStream.errorStream.collect { error ->
                    Log.e("WearablesManager", "Stream error: ${error.description}")
                    onStop()
                    stopStream()
                }
            }
            
            addedStream.start()
            Log.d("WearablesManager", "Stream STARTED")
        }?.onFailure { error, _ ->
            failureReason = error.description
            stopStream()
        }

        return if (failureReason == null) Result.success(Unit) else Result.failure(Exception(failureReason))
    }

    fun stopStream() {
        Log.d("WearablesManager", "HARD STOP: Killing all jobs and capability")
        videoJob?.cancel()
        stateJob?.cancel()
        errorJob?.cancel()
        sessionErrorJob?.cancel()
        sessionStateJob?.cancel()
        cameraStream?.stop()
        session?.stop()
        session = null
        cameraStream = null
    }
}
