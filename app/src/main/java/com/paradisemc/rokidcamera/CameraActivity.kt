package com.paradisemc.rokidcamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.paradisemc.rokidcamera.databinding.ActivityCameraBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val frameHandler = Handler(Looper.getMainLooper())

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED || results[Manifest.permission.CAMERA] == true
        if (cameraGranted) startCamera()
    }

    private val commandListener: (RemoteCommand) -> Unit = { cmd -> runOnUiThread { when (cmd) {
        RemoteCommand.Action -> startLocalCountdown()
        RemoteCommand.ToggleMode -> toggleMode()
        RemoteCommand.FlipLens -> flipLens()
        RemoteCommand.OpenCamera -> Unit
    } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        startRemoteService()
        setupControls()
        requestCameraPermissions()
        AppState.status.cameraOpen = true
        AppState.subscribe(commandListener)
        frameHandler.post(frameSampler)
    }

    private fun setupControls() {
        val labels = listOf("Viewfinder: Off", "Viewfinder: Always", "Viewfinder: First 5 sec of video")
        binding.viewfinderSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.viewfinderSpinner.setSelection(when (AppState.getViewfinderMode(this)) { ViewfinderMode.OFF -> 0; ViewfinderMode.ALWAYS -> 1; ViewfinderMode.VIDEO_5_SECONDS -> 2 })
        binding.viewfinderSpinner.onItemSelectedListener = SimpleItemSelectedListener { position -> AppState.setViewfinderMode(this, when (position) { 1 -> ViewfinderMode.ALWAYS; 2 -> ViewfinderMode.VIDEO_5_SECONDS; else -> ViewfinderMode.OFF }) }
        binding.shutterButton.setOnClickListener { startLocalCountdown() }
        binding.modeButton.setOnClickListener { toggleMode() }
        binding.lensButton.setOnClickListener { flipLens() }
        binding.remoteSwitch.setOnCheckedChangeListener { _, enabled -> if (enabled) startRemoteService() else stopService(Intent(this, RemoteControllerService::class.java)) }
        binding.overlayButton.setOnClickListener { requestOverlayPermission() }
        updateUi()
    }

    private fun requestCameraPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) startCamera() else permissionsLauncher.launch(needed.toTypedArray())
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable Appear on top so Rokid can open the camera.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        } else Toast.makeText(this, "Appear on top is enabled", Toast.LENGTH_SHORT).show()
    }

    private fun startRemoteService() { ContextCompat.startForegroundService(this, Intent(this, RemoteControllerService::class.java)) }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            videoCapture = VideoCapture.withOutput(Recorder.Builder().build())
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try { provider.unbindAll(); provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture) }
            catch (e: Exception) { Toast.makeText(this, "Camera start failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLocalCountdown() {
        var remaining = 3
        binding.countdownText.visibility = View.VISIBLE; binding.countdownText.text = remaining.toString()
        val h = Handler(Looper.getMainLooper())
        val task = object : Runnable { override fun run() { remaining--; if (remaining <= 0) { binding.countdownText.visibility = View.GONE; performAction() } else { binding.countdownText.text = remaining.toString(); h.postDelayed(this, 1000) } } }
        h.postDelayed(task, 1000)
    }

    private fun performAction() { if (AppState.status.mode == CameraMode.PHOTO) takePhoto() else if (AppState.status.recording) stopVideo() else startVideo() }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "Rokid_${timestamp()}"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RokidCameraRemote") }
        val options = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) { Toast.makeText(this@CameraActivity, "Photo saved", Toast.LENGTH_SHORT).show() }
            override fun onError(exc: ImageCaptureException) { Toast.makeText(this@CameraActivity, "Photo failed: ${exc.message}", Toast.LENGTH_LONG).show() }
        })
    }

    private fun startVideo() {
        val capture = videoCapture ?: return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "Rokid_${timestamp()}"); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RokidCameraRemote") }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event -> when (event) {
            is VideoRecordEvent.Start -> { AppState.status.recording = true; updateUi() }
            is VideoRecordEvent.Finalize -> { AppState.status.recording = false; recording = null; updateUi(); if (event.hasError()) Toast.makeText(this, "Video error: ${event.error}", Toast.LENGTH_LONG).show() }
        } }
    }

    private fun stopVideo() { recording?.stop() }
    private fun toggleMode() { if (!AppState.status.recording) { AppState.status.mode = if (AppState.status.mode == CameraMode.PHOTO) CameraMode.VIDEO else CameraMode.PHOTO; updateUi() } }
    private fun flipLens() { if (!AppState.status.recording) { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; startCamera() } }
    private fun updateUi() { val video = AppState.status.mode == CameraMode.VIDEO; binding.modeButton.text = if (video) "VIDEO" else "PHOTO"; binding.shutterButton.text = if (video && AppState.status.recording) "STOP" else if (video) "REC" else "SHOOT"; binding.statusText.text = when { AppState.status.recording -> "● REC • Rokid ready"; video -> "VIDEO • Rokid ready"; else -> "PHOTO • Rokid ready" } }

    private val frameSampler = object : Runnable { override fun run() { binding.previewView.bitmap?.let { bitmap -> frameExecutor.execute { val scaled = scaleForGlasses(bitmap); val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 55, out); AppState.latestFrame = out.toByteArray(); if (scaled !== bitmap) scaled.recycle() } }; frameHandler.postDelayed(this, 450) } }
    private fun scaleForGlasses(source: Bitmap): Bitmap { val maxWidth = 640; if (source.width <= maxWidth) return source; return Bitmap.createScaledBitmap(source, maxWidth, (source.height * (maxWidth.toFloat() / source.width)).toInt(), true) }
    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    override fun onDestroy() { AppState.status.cameraOpen = false; AppState.unsubscribe(commandListener); frameHandler.removeCallbacks(frameSampler); frameExecutor.shutdownNow(); super.onDestroy() }
}
