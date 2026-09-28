package com.example.lockrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.lockrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var recordingService: RecordingService? = null
    private var isBound = false
    private var isUnlocked = false
    private var authInProgress = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            updateUiState()
            uiHandler.postDelayed(this, 500)
        }
    }

    // Permissions without which the app cannot work at all.
    private val mustHavePermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return perms.toTypedArray()
        }

    // Everything we ask for in the dialog (notifications are optional).
    private val permissionsToRequest: Array<String>
        get() {
            val perms = mustHavePermissions.toMutableList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            attachPreviewIfReady()
            updateUiState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMustHavePermissions()) {
            startAndBindService()
            if (!isUnlocked && !authInProgress) {
                showBiometricPrompt()
            }
        } else {
            binding.statusText.text = "Camera and microphone permissions are required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recordButton.setOnClickListener {
            val service = recordingService ?: return@setOnClickListener
            if (RecordingService.isRecording) {
                service.stopRecordingNow()
            } else {
                service.startRecordingNow()
            }
            updateUiState()
        }

        binding.switchCameraButton.setOnClickListener {
            recordingService?.switchCamera()
        }

        binding.unlockButton.setOnClickListener {
            if (!authInProgress) {
                showBiometricPrompt()
            }
        }

        if (hasMustHavePermissions()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isBound) {
            attachPreviewIfReady()
        }
        if (isUnlocked) {
            binding.lockOverlay.visibility = View.GONE
        } else if (!authInProgress && hasMustHavePermissions()) {
            showBiometricPrompt()
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiTicker)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Screen locked or app backgrounded: release the preview surface so
        // the camera does not try to draw to a dead surface. Recording (if
        // active) keeps going because it is owned by the Service, not us.
        recordingService?.detachPreview()
        // Require re-authentication next time, unless we are only stopping
        // because the fingerprint/PIN system dialog took the foreground.
        if (!authInProgress) {
            isUnlocked = false
        }
        binding.lockOverlay.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun hasMustHavePermissions(): Boolean =
        mustHavePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startAndBindService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun attachPreviewIfReady() {
        if (isUnlocked) {
            recordingService?.attachPreview(binding.previewView.surfaceProvider)
        }
    }

    private fun unlockNow() {
        authInProgress = false
        isUnlocked = true
        binding.lockOverlay.visibility = View.GONE
        attachPreviewIfReady()
    }

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val canAuthenticate = biometricManager.canAuthenticate(allowedAuthenticators)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // No fingerprint/PIN set up on this phone at all: do not lock the
            // user out of their own app.
            unlockNow()
            return
        }

        authInProgress = true

        val executor = ContextCompat.getMainExecutor(this)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Lock Recorder")
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()

        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockNow()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Stay locked; the user can tap "Unlock" again.
                    authInProgress = false
                }

                override fun onAuthenticationFailed() {
                    // Stay locked; BiometricPrompt shows its own retry UI.
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    private fun updateUiState() {
        val recording = RecordingService.isRecording
        binding.statusText.text = when {
            !hasMustHavePermissions() ->
                "Camera and microphone permissions are required.\nAllow them in phone Settings > Apps > Lock Recorder."
            recording ->
                "Recording... you can lock the screen now.\nOpen the app again and press Stop to finish."
            else -> "Ready. Press Start Recording."
        }
        binding.recordButton.text = if (recording) "Stop Recording" else "Start Recording"
        binding.switchCameraButton.isEnabled = !recording
    }
}
