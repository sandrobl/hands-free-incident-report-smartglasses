package com.unisg.hands_free_incident_report_smartglasses

import android.content.Context
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer

class VideoFileEncoder(private val context: Context) {
    companion object {
        private const val TAG = "VideoFileEncoder"
        private const val MIME_VIDEO = "video/hevc"
        private const val MIME_AUDIO = "audio/mp4a-latm"
        private const val AUDIO_SAMPLE_RATE = 24000
        private const val BIT_RATE_VIDEO = 15_000_000 // High-quality 15Mbps HEVC
        private const val BIT_RATE_AUDIO = 128_000
        private const val FRAME_RATE = 30
    }

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    
    private var firstFramePts = -1L
    private var firstFrameSystemTimeUs = -1L
    private var audioSamplesCount = 0L
    private var outputFile: File? = null
    
    private val pendingSamples = mutableListOf<Pair<Int, PendingSample>>()
    private data class PendingSample(val data: ByteArray, val info: BufferInfo)
    
    private val lock = Any()
    private var isReleased = false

    fun start() = synchronized(lock) {
        isReleased = false
        outputFile = File(context.cacheDir, "report_${System.currentTimeMillis()}.mp4")
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
        firstFramePts = -1L
        firstFrameSystemTimeUs = -1L
        audioSamplesCount = 0L
        pendingSamples.clear()
        setupAudioEncoder()
        Log.d(TAG, "Recording started: ${outputFile?.absolutePath}")
    }

    fun addFrame(yuvBuffer: ByteBuffer, width: Int, height: Int, pts: Long) = synchronized(lock) {
        if (isReleased) return@synchronized
        if (firstFramePts == -1L) {
            firstFramePts = pts
            firstFrameSystemTimeUs = SystemClock.elapsedRealtimeNanos() / 1000
        }
        
        if (videoCodec == null) setupVideoEncoder(width, height)
        val codec = videoCodec ?: return@synchronized
        
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            val nv12 = i420ToNv12(yuvBuffer, width, height)
            inputBuffer.put(nv12)
            codec.queueInputBuffer(inputIndex, 0, nv12.size, pts - firstFramePts, 0)
        }
        drainEncoder(codec, true)
    }

    fun addAudio(data: ByteArray) = synchronized(lock) {
        if (isReleased) return@synchronized
        if (firstFrameSystemTimeUs == -1L) return@synchronized
        val codec = audioCodec ?: return@synchronized
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            inputBuffer.put(data)
            val pts = (audioSamplesCount * 1_000_000L) / AUDIO_SAMPLE_RATE
            codec.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
            audioSamplesCount += data.size / 2
        }
        drainEncoder(codec, false)
    }

    fun finish(): File? = synchronized(lock) {
        if (isReleased) return null
        isReleased = true
        
        videoCodec?.let { 
            try {
                signalEndOfStream(it)
                drainEncoder(it, true)
                it.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video codec", e)
            } finally {
                it.release()
            }
        }
        audioCodec?.let { 
            try {
                signalEndOfStream(it)
                drainEncoder(it, false)
                it.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio codec", e)
            } finally {
                it.release()
            }
        }
        
        if (!muxerStarted && (videoTrack != -1 || audioTrack != -1)) {
            try {
                muxer?.start()
                muxerStarted = true
                writePending()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting muxer in finish()", e)
            }
        }
        
        muxer?.let { 
            try { 
                if (muxerStarted) it.stop() 
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping muxer", e)
            } finally {
                it.release() 
            }
        }
        
        val result = outputFile
        videoCodec = null
        audioCodec = null
        muxer = null
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
        return result
    }

    private fun drainEncoder(codec: MediaCodec, isVideo: Boolean) {
        val info = BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxer == null) {
                    muxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
                val track = muxer!!.addTrack(codec.outputFormat)
                if (isVideo) videoTrack = track else audioTrack = track
                if (videoTrack != -1 && audioTrack != -1 && !muxerStarted) {
                    muxer!!.start(); muxerStarted = true; writePending()
                }
            } else if (outputIndex >= 0) {
                val track = if (isVideo) videoTrack else audioTrack
                if (muxerStarted && track != -1) {
                    muxer!!.writeSampleData(track, codec.getOutputBuffer(outputIndex)!!, info)
                } else if (track != -1) {
                    val data = ByteArray(info.size)
                    codec.getOutputBuffer(outputIndex)!!.get(data)
                    val newInfo = BufferInfo()
                    newInfo.set(0, info.size, info.presentationTimeUs, info.flags)
                    pendingSamples.add(track to PendingSample(data, newInfo))
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else break
        }
    }

    private fun writePending() {
        for (sample in pendingSamples) muxer?.writeSampleData(sample.first, ByteBuffer.wrap(sample.second.data), sample.second.info)
        pendingSamples.clear()
    }

    private fun setupVideoEncoder(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_VIDEO)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
        }
        
        // Ensure creation on Main Thread to associate with stable Main Looper
        // Use runOnMainThread helper to avoid deadlock if already on Main
        videoCodec = runOnMainThread {
            MediaCodec.createEncoderByType(MIME_VIDEO).apply { 
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start() 
            }
        }
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_AUDIO)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        
        // Ensure creation on Main Thread to associate with stable Main Looper
        audioCodec = runOnMainThread {
            MediaCodec.createEncoderByType(MIME_AUDIO).apply { 
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start() 
            }
        }
    }

    private fun <T> runOnMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        return runBlocking(Dispatchers.Main) {
            block()
        }
    }

    private fun signalEndOfStream(codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun i420ToNv12(i420: ByteBuffer, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val uvPlaneSize = frameSize / 4
        val out = ByteArray(frameSize + uvPlaneSize * 2)
        val pos = i420.position()
        
        val dup = i420.duplicate().apply { position(pos) }
        dup.get(out, 0, frameSize)
        
        val uBytes = ByteArray(uvPlaneSize)
        val vBytes = ByteArray(uvPlaneSize)
        dup.position(pos + frameSize)
        dup.get(uBytes)
        dup.position(pos + frameSize + uvPlaneSize)
        dup.get(vBytes)
        
        var uvIndex = frameSize
        for (i in 0 until uvPlaneSize) {
            out[uvIndex++] = uBytes[i]
            out[uvIndex++] = vBytes[i]
        }
        return out
    }

    fun saveToMediaStore(relativePath: String): android.net.Uri? {
        val file = outputFile ?: return null
        if (!file.exists() || file.length() == 0L) return null
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return uri
        } catch (e: Exception) {return null}
    }
}