package com.example.lockrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns the camera. It binds Preview + VideoCapture
 * together to its OWN lifecycle (not the Activity's), so recording keeps
 * going even after the Activity is stopped by a screen lock. The Activity
 * only attaches/detaches its PreviewView surface to show a live feed while
 * it is visible.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.orsa.lockrecorder.action.START"
        const val ACTION_CLOSE = "com.orsa.lockrecorder.action.CLOSE"

        @Volatile
        var isRecording = false
            private set

        @Volatile
        var isCameraReady = false
            private set
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // The Activity may ask to show the preview before the camera has finished
    // opening. We remember the request and apply it as soon as Preview exists.
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CLOSE) {
            fullyStopAndClose()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        acquireWakeLock()
        bindCameraUseCases()
        // Not sticky: Android 14+ does not allow a camera foreground service to
        // be silently restarted from the background, so a restart would crash.
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background status"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val closeIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_CLOSE
        }
        val closePendingIntent = PendingIntent.getService(
            this, 0, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App running")
            .setContentText(" ")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LockRecorder::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L /* 4 hour safety timeout */)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Binds Preview + VideoCapture to THIS SERVICE's lifecycle (survives Activity stop). */
    private fun bindCameraUseCases() {
        if (isCameraReady) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val newPreview = Preview.Builder().build()
            preview = newPreview
            pendingSurfaceProvider?.let { newPreview.setSurfaceProvider(it) }

            val newRecorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            recorder = newRecorder
            videoCapture = VideoCapture.withOutput(newRecorder)

            rebindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        val provider = cameraProvider ?: return
        val previewUseCase = preview ?: return
        val videoUseCase = videoCapture ?: return
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, previewUseCase, videoUseCase)
            isCameraReady = true
        } catch (e: Exception) {
            Log.e("RecordingService", "Camera bind failed", e)
            isCameraReady = false
        }
    }

    /** Called by the Activity when it becomes visible, to show the live feed. */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        pendingSurfaceProvider = surfaceProvider
        preview?.setSurfaceProvider(surfaceProvider)
    }

    /** Called by the Activity when it is no longer visible (e.g. screen locked). */
    fun detachPreview() {
        pendingSurfaceProvider = null
        preview?.setSurfaceProvider(null)
    }

    fun canSwitchCamera(): Boolean = !isRecording

    /** Switches between front and back camera. Not allowed while recording. */
    fun switchCamera() {
        if (isRecording) return
        val previous = lensFacing
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        rebindCamera()
        if (!isCameraReady) {
            // The other camera does not exist or failed to open: go back.
            lensFacing = previous
            rebindCamera()
        }
    }

    fun startRecordingNow() {
        if (isRecording) return
        val rec = recorder ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "REC_$name")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/LockRecorder"
                )
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        activeRecording = rec.prepareRecording(this, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        this@RecordingService,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        Log.d("RecordingService", "Recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        if (!event.hasError()) {
                            Log.d("RecordingService", "Recording saved: ${event.outputResults.outputUri}")
                        } else {
                            Log.e("RecordingService", "Recording error: ${event.error}")
                        }
                    }
                    else -> {}
                }
            }
    }

    fun stopRecordingNow() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun fullyStopAndClose() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        isCameraReady = false
        isRecording = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        releaseWakeLock()
        isRecording = false
        isCameraReady = false
        super.onDestroy()
    }
}
