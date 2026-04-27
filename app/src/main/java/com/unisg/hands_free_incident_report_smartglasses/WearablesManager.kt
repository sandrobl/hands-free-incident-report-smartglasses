package com.unisg.hands_free_incident_report_smartglasses

import android.content.Context
import android.graphics.Bitmap
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.unisg.hands_free_incident_report_smartglasses.stream.YuvToBitmapConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WearablesManager(private val context: Context) {

    private var session: StreamSession? = null
    private var frameJob: Job? = null
    private var stateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun startStream(onFrame: (Bitmap) -> Unit) {
        val streamSession = Wearables.startStreamSession(
            context = context,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = StreamConfiguration(
                videoQuality = VideoQuality.HIGH,
                frameRate = 24,
            ),
        )
        session = streamSession

        // Collect video frames
        frameJob = scope.launch {
            streamSession.videoStream.collect { frame ->
                // Convert VideoFrame to Bitmap and pass upstream
                val bitmap = YuvToBitmapConverter.convert(
                    yuvData = frame.buffer,
                    width = frame.width,
                    height = frame.height
                )
                if(bitmap != null){
                    onFrame(bitmap)
                }
            }
        }

        // Monitor session state
        stateJob = scope.launch {
            streamSession.state.collect { state ->
                if (state == StreamSessionState.STOPPED || state == StreamSessionState.CLOSED) {
                    stopStream()
                }
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