package com.unisg.hands_free_incident_report_smartglasses

import android.content.Context
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WearablesManager(private val context: Context) {

    private var session: StreamSession? = null
    private var frameJob: Job? = null
    private var stateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun startStream(onFrame: (VideoFrame) -> Unit, onStop: () -> Unit) {
        val streamSession = Wearables.startStreamSession(
            context = context,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = StreamConfiguration(
                videoQuality = VideoQuality.HIGH,
                frameRate = 30,
            ),
        )
        session = streamSession

        // Collect video frames
        frameJob = scope.launch {
            streamSession.videoStream.collect { frame ->
                onFrame(frame)
            }
        }

        // Monitor session state
        stateJob = scope.launch {
            var lastState: StreamSessionState? = null
            streamSession.state.collect { currentState ->
                android.util.Log.d("WearablesManager", "Stream state: $currentState (last: $lastState)")
                
                // Only trigger stop/upload if we transitioned to a final state from a different state
                if ((currentState == StreamSessionState.STOPPED || currentState == StreamSessionState.CLOSED) &&
                    lastState != null && lastState != currentState) {
                    
                    if (session != null) {
                        session = null // Clear session before calling onStop to prevent loops
                        onStop()
                        stopStream()
                    }
                }
                lastState = currentState
            }
        }
    }

    fun stopStream() {
        frameJob?.cancel()
        stateJob?.cancel()
        session?.close()
        session = null
    }
}