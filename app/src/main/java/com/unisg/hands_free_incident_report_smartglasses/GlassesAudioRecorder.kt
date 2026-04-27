package com.unisg.hands_free_incident_report_smartglasses

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GlassesAudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 8000      // HFP is 8kHz
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Raw PCM output file — merged into MP4 later by VideoFileEncoder
    lateinit var pcmFile: File
        private set

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        routeAudioToGlasses()

        pcmFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.pcm")
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)


        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )

        audioRecord?.startRecording()

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            FileOutputStream(pcmFile).use { fos ->
                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) fos.write(buffer, 0, read)
                }
            }
        }
    }

    fun stop() {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun routeAudioToGlasses() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bluetoothDevice = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

        bluetoothDevice?.let {
            audioManager.setCommunicationDevice(it)
        }
    }
}