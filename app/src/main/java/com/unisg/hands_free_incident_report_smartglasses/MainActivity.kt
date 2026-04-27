package com.unisg.hands_free_incident_report_smartglasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.unisg.hands_free_incident_report_smartglasses.ui.HomeScreen
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private lateinit var wearablesManager: WearablesManager
    private lateinit var audioRecorder: GlassesAudioRecorder
    private lateinit var videoEncoder: VideoFileEncoder

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkIntentAndStart(intent)
        } else {
            Log.e("Report", "System permissions denied")
        }
    }

    // Wearable permission state
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Wearables.initialize(this)
        wearablesManager = WearablesManager(this)
        audioRecorder = GlassesAudioRecorder(this)
        videoEncoder = VideoFileEncoder(this)

        setupMediaSession()
        pushDynamicShortcut()

        setContent {
            HomeScreen(
                onStopClick = { stopAndUpload() }
            )
        }
        if (hasPermissions()) {
            checkIntentAndStart(intent)
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (hasPermissions()) {
            checkIntentAndStart(intent)
        }
    }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablesPermissionLauncher.launch(permission)
            }
        }
    }

    private fun setupMediaSession() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "IncidentReportSession")

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(
                    Intent.EXTRA_KEY_EVENT
                )

                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    val isPlayPause = keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                            keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE

                    if (isPlayPause) {
                        Log.d("Report", "Hardware tap intercepted. Stopping recording.")
                        stopAndUpload()
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .build()

        audioFocusRequest?.let {
            audioManager.requestAudioFocus(it)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkIntentAndStart(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            startReport()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startReport() {
        if (!hasPermissions()) {
            Log.e("Report", "System permissions not granted.")
            return
        }

        requestAudioFocus()
        mediaSession.isActive = true

        lifecycleScope.launch {
            try {
                // Check Wearable Camera Permission
                val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
                if (status != PermissionStatus.Granted) {
                    val result = requestWearablesPermission(Permission.CAMERA)
                    if (result != PermissionStatus.Granted) {
                        Log.e("Report", "Wearable camera permission denied")
                        return@launch
                    }
                }

                audioRecorder.start()
                videoEncoder.start()
                wearablesManager.startStream { frame ->
                    videoEncoder.addFrame(frame)
                }
            } catch (e: Exception) {
                Log.e("Report", "Error starting report", e)
            }
        }
    }

    private fun stopAndUpload() {
        mediaSession.isActive = false
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }

        lifecycleScope.launch {
            wearablesManager.stopStream()
            audioRecorder.stop()
            val outputFile = videoEncoder.finish(audioRecorder.pcmFile)

            val result = MockApiClient.upload(outputFile)
            result.onSuccess { id -> Log.d("Report", "Done: $id") }
        }
    }

    private fun pushDynamicShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "start_report_shortcut")
            .setShortLabel("Manta")
            .setLongLabel("Manta starten")
            .setIcon(IconCompat.createWithResource(this, R.drawable.camera_access_icon))
            .setIntent(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                }
            )
            .addCapabilityBinding("actions.intent.OPEN_APP_FEATURE", "feature", listOf("manta"))
            .addCapabilityBinding("custom.actions.intent.START_REPORT")
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(
            this, shortcut)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
    }
}