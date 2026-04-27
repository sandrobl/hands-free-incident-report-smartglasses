package com.unisg.hands_free_incident_report_smartglasses

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec.BufferInfo
import java.io.File
import java.nio.ByteBuffer

class VideoFileEncoder(private val context: Context) {

    companion object {
        private const val MIME_TYPE = "video/avc"       // H.264
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val BIT_RATE = 2_000_000          // 2 Mbps
        private const val FRAME_RATE = 24
        private const val I_FRAME_INTERVAL = 1
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var frameCount = 0L
    private lateinit var outputFile: File

    fun start() {
        outputFile = File(context.cacheDir, "report_${System.currentTimeMillis()}.mp4")

        val format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun addFrame(bitmap: Bitmap) {
        val codec = codec ?: return
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, false)
        val yuvBytes = bitmapToYUV420(scaledBitmap)

        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
            inputBuffer.clear()
            inputBuffer.put(yuvBytes)

            val presentationTimeUs = frameCount * 1_000_000L / FRAME_RATE
            codec.queueInputBuffer(inputBufferIndex, 0, yuvBytes.size, presentationTimeUs, 0)
            frameCount++
        }

        drainEncoder(endOfStream = false)
    }

    fun finish(audioFile: File? = null): File {
        // Signal end of stream
        val inputBufferIndex = codec?.dequeueInputBuffer(10_000) ?: -1
        if (inputBufferIndex >= 0) {
            codec?.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainEncoder(endOfStream = true)

        codec?.stop()
        codec?.release()
        muxer?.stop()
        muxer?.release()

        return outputFile
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        val bufferInfo = BufferInfo()

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        && videoTrackIndex >= 0) {
                        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> if (endOfStream) break else return
            }
        }
    }

    // Bitmap (ARGB) → YUV420 für MediaCodec
    private fun bitmapToYUV420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}