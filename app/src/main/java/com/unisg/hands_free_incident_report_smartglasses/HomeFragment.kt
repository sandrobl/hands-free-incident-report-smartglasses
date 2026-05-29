package com.unisg.hands_free_incident_report_smartglasses

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media.session.MediaButtonReceiver
import androidx.navigation.fragment.findNavController
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.storage.CredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.google.android.material.snackbar.Snackbar
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.unisg.hands_free_incident_report_smartglasses.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class HomeFragment : Fragment() {

    private lateinit var wearablesManager: WearablesManager
    private lateinit var audioRecorder: GlassesAudioRecorder
    private lateinit var videoEncoder: VideoFileEncoder

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var registrationRequested = false
    private var lastMusicVolume: Int = -1
    private var volumePollHandler: Handler? = null
    private var volumePollRunnable: Runnable? = null

    private var lastMediaButtonAt: Long = 0L
    private val isGlassesConnected = mutableStateOf(false)

    enum class RecordingStatus {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING
    }

    private val recordingStatus = mutableStateOf(RecordingStatus.IDLE)
    private var startJob: Job? = null

    private lateinit var account: Auth0
    private lateinit var credentialsManager: CredentialsManager

    private val viewModel: HomeViewModel by viewModels {
        val authClient = AuthenticationAPIClient(Auth0.getInstance(getString(R.string.com_auth0_client_id), getString(R.string.com_auth0_domain)))
        val cm = CredentialsManager(authClient, SharedPreferencesStorage(requireContext()))
        HomeViewModelFactory(VideoRepository(requireContext(), cm))
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()){
            uri -> uri?.let{
        AlertDialog.Builder(requireContext())
            .setTitle("Video Selected")
            .setMessage("What do you want to do with the video?")
            .setPositiveButton("Send"){dialog, _ -> viewModel.sendVideo(it); dialog.dismiss()}
            .setNegativeButton("Cancel"){dialog, _ -> dialog.dismiss()}
            .setCancelable(false)
            .show()
    }
    }

    fun degreesToCardinal(degrees: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        return directions[((degrees + 22.5f) / 45f).toInt() % 8]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        account = Auth0.getInstance(
            getString(R.string.com_auth0_client_id),
            getString(R.string.com_auth0_domain)
        )
        val authClient = AuthenticationAPIClient(account)
        credentialsManager = CredentialsManager(authClient, SharedPreferencesStorage(requireContext()))

        // Initialize related managers after system permissions granted
        wearablesManager = WearablesManager(requireContext())
        audioRecorder = GlassesAudioRecorder(requireContext())
        videoEncoder = VideoFileEncoder(requireContext())

        setupMediaSession()
        startVolumePolling()

        // Observe Wearables devices after initialization
        lifecycleScope.launch {
            // Wait for Wearables to be initialized to avoid crash on startup
            while (true) {
                try {
                    Wearables.registrationState
                    break
                } catch (e: Exception) {
                    kotlinx.coroutines.delay(500)
                }
            }

            try {
                Wearables.devices.collect { devices ->
                    Log.d("Report", "Wearables.devices count=${devices.size} -> $devices")
                    isGlassesConnected.value = devices.isNotEmpty()
                    
                    devices.forEach { deviceId ->
                        // Observe metadata for each device
                        lifecycleScope.launch {
                            Wearables.devicesMetadata[deviceId]?.collect { metadata ->
                                Log.d("Report", "Device $deviceId Metadata: Name='${metadata.name}', Type=${metadata.deviceType}, Compatibility=${metadata.compatibility}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("Report", "Failed to observe devices: $e")
                isGlassesConnected.value = false
            }
        }

        // Observe registration state to diagnose UNREGISTERED state
        lifecycleScope.launch {
            try {
                Wearables.registrationState.collect { state ->
                    Log.d("Report", "Wearables.registrationState -> $state")
                    when (state) {
                        RegistrationState.REGISTERED -> {
                            Log.d("Report", "App registered with Meta")
                            binding.videoCaptureButton.text = "Ready to Swipe"
                            binding.videoCaptureButton.backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.palette_blue))
                            binding.videoCaptureButton.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.white))
                        }
                        RegistrationState.AVAILABLE -> {
                            Log.d("Report", "App is available - waiting for user to start Meta registration flow")
                            binding.videoCaptureButton.text = "register app with Meta"
                            binding.videoCaptureButton.backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.palette_lime))
                            binding.videoCaptureButton.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.black))
                        }
                        RegistrationState.UNAVAILABLE -> {
                            Log.d("Report", "App is UNREGISTERED - call Wearables.startRegistration()")
                            binding.videoCaptureButton.text = "register app with Meta"
                            binding.videoCaptureButton.backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.palette_lime))
                            binding.videoCaptureButton.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.black))
                        }
                        else -> {
                            Log.d("Report", "RegistrationState: $state")
                            binding.videoCaptureButton.text = "register app with Meta"
                            binding.videoCaptureButton.backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.palette_lime))
                            binding.videoCaptureButton.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.black))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("Report", "Failed to observe registrationState: $e")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.orientation.collect { orientation ->
                    orientation?.let {
                        val degrees = it.headingDegrees
                        val cardinal = degreesToCardinal(degrees)
                        binding.compassValues.text = getString(R.string.compass_display, degrees.toInt(), cardinal)
                    }
                }
            }
        }

        checkIntentAndStart(requireActivity().intent)

        binding.testButton.setOnClickListener { 
            // Force a clean registration state
            Log.d("Report", "Force re-register button clicked")
            try {
                Wearables.startUnregistration(requireActivity())
                // Small delay to let unregistration settle
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1000)
                    Wearables.startRegistration(requireActivity())
                }
                showSnackBar("Clearing SDK state and opening Meta pairing...")
            } catch (e: Exception) {
                Log.e("Report", "Force re-register failed: ${e.message}")
            }
        }
        binding.btnLogout.setOnClickListener { logout() }
        binding.useExisting.setOnClickListener { pickVideoLauncher.launch("video/*")}
        binding.videoCaptureButton.setOnClickListener {
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                Wearables.startRegistration(requireActivity())
            }
        }
    }


    private fun setupMediaSession() {
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(requireContext(), "IncidentReportSession")

        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(
            requireContext(),
            MediaButtonReceiver::class.java
        )
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            requireContext(),
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession.setMediaButtonReceiver(mediaButtonPendingIntent)

        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                .build()
        )

        mediaSession.isActive = true

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(
                    Intent.EXTRA_KEY_EVENT
                )
                if (keyEvent != null) {
                    Log.d("Report", "onMediaButtonEvent keyCode=${keyEvent.keyCode} action=${keyEvent.action}")

                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        val isPlayPause = keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                                keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                                keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                        if (isPlayPause) {
                            Log.d("Report", "Media button stop trigger")
                            stopAndUpload()
                            return true
                        }

                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                Log.d("Report", "Media button volume up - starting report")
                                startReportIfIdle()
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                Log.d("Report", "Media button volume down - stopping report")
                                stopAndUpload()
                                return true
                            }
                        }
                    }
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })

        requestAudioFocus()
    }

    private fun checkIntentAndStart(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            startReportIfIdle()
        }
    }

    private fun startVolumePolling() {
        if (volumePollHandler != null) {
            return
        }

        if (!::audioManager.isInitialized) {
            audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }

        lastMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (currentVolume != lastMusicVolume) {
                    val delta = currentVolume - lastMusicVolume
                    lastMusicVolume = currentVolume

                    if (delta > 0) {
                        Log.d("Report", "Polled volume up detected: $currentVolume")
                        startReportIfIdle()
                    } else if (delta < 0) {
                        Log.d("Report", "Polled volume down detected: $currentVolume")
                        stopAndUpload()
                    }
                }
                handler.postDelayed(this, 250L)
            }
        }

        volumePollHandler = handler
        volumePollRunnable = runnable
        handler.post(runnable)
    }

    private fun stopVolumePolling() {
        val handler = volumePollHandler ?: return
        val runnable = volumePollRunnable
        if (runnable != null) {
            handler.removeCallbacks(runnable)
        }
        volumePollHandler = null
        volumePollRunnable = null
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


    private fun startReportIfIdle() {
        if (recordingStatus.value != RecordingStatus.IDLE) {
            return
        }

        if (::audioManager.isInitialized) {
            lastMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        recordingStatus.value = RecordingStatus.STARTING
        startReport()
    }

    private fun stopReportIfActive() {
        if (recordingStatus.value != RecordingStatus.RECORDING &&
            recordingStatus.value != RecordingStatus.STARTING) {
            return
        }
        stopAndUpload()
    }

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null

    private val permissionMutex = Mutex()
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
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

    @SuppressLint("MissingPermission")
    private fun startReport() {
        Log.d("Report", "startReport() called - status: ${recordingStatus.value}")
        Log.d("Report", "Registration State: ${Wearables.registrationState.value}")
        
        requestAudioFocus()
        mediaSession.isActive = true

        startJob = lifecycleScope.launch {
            try {
                Log.d("Report", "Checking wearable permissions...")
                
                // Camera Permission
                val cameraResult = Wearables.checkPermissionStatus(Permission.CAMERA)
                if (cameraResult.getOrNull() != PermissionStatus.Granted) {
                    Log.d("Report", "Requesting wearable camera permission")
                    val result = requestWearablesPermission(Permission.CAMERA)
                    if (result != PermissionStatus.Granted) {
                        Log.e("Report", "Wearable camera permission denied")
                        recordingStatus.value = RecordingStatus.IDLE
                        return@launch
                    }
                }
                
                // Microphone Permission
                val micResult = Wearables.checkPermissionStatus(Permission.MICROPHONE)
                if (micResult.getOrNull() != PermissionStatus.Granted) {
                    Log.d("Report", "Requesting wearable microphone permission")
                    val result = requestWearablesPermission(Permission.MICROPHONE)
                    if (result != PermissionStatus.Granted) {
                        Log.e("Report", "Wearable microphone permission denied")
                        recordingStatus.value = RecordingStatus.IDLE
                        return@launch
                    }
                }

                Log.d("Report", "Permissions verified. Attempting to start wearables stream...")
                videoEncoder.start()
                val streamResult = wearablesManager.startStream(
                    onFrame = { frame ->
                        videoEncoder.addFrame(
                            yuvBuffer = frame.buffer,
                            width = frame.width,
                            height = frame.height,
                            pts = frame.presentationTimeUs,
                        )
                    },
                    onStop = {
                        Log.d("Report", "WearablesManager triggered onStop")
                        activity?.runOnUiThread { stopReportIfActive() }
                    }
                )

                if (streamResult.isFailure) {
                    val error = streamResult.exceptionOrNull()
                    Log.e("Report", "Failed to start wearables stream: ${error?.message}")
                    
                    activity?.runOnUiThread {
                        showSnackBar("Glasses error: ${error?.message}")
                    }
                    
                    videoEncoder.finish()
                    recordingStatus.value = RecordingStatus.IDLE
                    return@launch
                }

                Log.d("Report", "Stream started successfully. Starting audio recorder...")
                audioRecorder.start { data ->
                    videoEncoder.addAudio(data)
                }
                recordingStatus.value = RecordingStatus.RECORDING
            } catch (e: Exception) {
                Log.e("Report", "Critical error in startReport coroutine", e)
                recordingStatus.value = RecordingStatus.IDLE
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopVolumePolling()
    }

    private fun stopAndUpload() {
        val currentStatus = recordingStatus.value
        if (currentStatus == RecordingStatus.IDLE || currentStatus == RecordingStatus.STOPPING) {
            return
        }
        
        Log.d("Report", "stopAndUpload() - PERFORMING HARD KILL (Current state: $currentStatus)")
        
        // 1. Force state to STOPPING immediately to prevent double-stop
        recordingStatus.value = RecordingStatus.STOPPING

        // 2. Kill the start-up coroutine immediately
        startJob?.cancel()
        startJob = null
        
        // 3. Hardware Hard-Stop (Immediate Main Thread)
        try {
            wearablesManager.stopStream()
            audioRecorder.stop()
        } catch (e: Exception) {
            Log.e("Report", "Error during immediate hardware stop: ${e.message}")
        }

        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }

        // 4. Finalize file in background thread to avoid UI hang
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // videoEncoder.finish() is synchronous and can block; keep it here
                val outputFile = videoEncoder.finish()
                if (outputFile != null && outputFile.exists()) {
                    Log.d("Report", "Hard Kill finalized file: ${outputFile.name}")
                    videoEncoder.saveToMediaStore("DCIM/Incident-Reports")?.let { uri ->
                        viewModel.sendVideo(uri)
                    }
                }
            } catch (e: Exception) {
                Log.e("Report", "Hard Kill cleanup error: ${e.message}")
            } finally {
                // FORCE IDLE no matter what happened during cleanup
                withContext(Dispatchers.Main) {
                    recordingStatus.value = RecordingStatus.IDLE
                    Log.d("Report", "HARD KILL COMPLETE - State: IDLE")
                }
            }
        }
    }

    private fun logout() {
        try {
            Wearables.startUnregistration(requireActivity())
        } catch (e: Exception) {
            Log.e("Report", "Failed to start unregistration: ${e.message}")
        }
        WebAuthProvider
            .logout(account)
            .withScheme(getString(R.string.com_auth0_scheme))
            .start(requireActivity(), object : Callback<Void?, AuthenticationException> {
                override fun onSuccess(result: Void?) {
                    credentialsManager.clearCredentials()
                    findNavController().navigate(R.id.action_home_to_login)
                }
                override fun onFailure(error: AuthenticationException) {
                    Toast.makeText(requireContext(), "Logout failed: ${error.getCode()}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun callPrivateApi() {
        credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) {
                val token = result.accessToken

                Thread {
                    try {
                        val url = URL("https://api.hands-free-incident-report.ch/api/private")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Authorization", "Bearer $token")

                        val responseCode = connection.responseCode
                        val response = if (responseCode in 200..299) {
                            connection.inputStream.bufferedReader().readText()
                        } else {
                            connection.errorStream?.bufferedReader()?.readText() ?: "no error body"
                        }

                        activity?.runOnUiThread {
                            if (responseCode == 401){
                                findNavController().navigate(R.id.action_home_to_login)
                            }

                            Log.d(TAG, "Private API response: $response")
                            showSnackBar("$responseCode: $response")
                        }

                    } catch (e: Exception) {
                        activity?.runOnUiThread {
                            Log.e(TAG, "Error calling private API", e)
                            showSnackBar("Error: ${e.message}")
                        }
                    }
                }.start()
            }

            override fun onFailure(error: CredentialsManagerException) {
                activity?.runOnUiThread {
                    showSnackBar("No valid credentials: ${error.message}")
                    findNavController().navigate(R.id.action_home_to_login)

                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        stopVolumePolling()
        mediaSession.release()
    }

    private fun showSnackBar(text: String) {
        Snackbar
            .make(
                binding.root,
                text,
                Snackbar.LENGTH_LONG
            ).show()
    }

    companion object {
        private const val TAG = "CameraXApp"
    }
}