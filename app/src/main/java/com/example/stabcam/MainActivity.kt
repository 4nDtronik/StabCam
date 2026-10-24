package com.example.stabcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var recBtn: Button
    private lateinit var qualBtn: Button
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var uhd = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (hasCamera()) startCamera() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        recBtn = Button(this).apply { text = "REKAM"; setOnClickListener { toggleRecord() } }
        qualBtn = Button(this).apply { text = "Kualitas: FHD"; setOnClickListener { toggleQuality() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
            addView(qualBtn); addView(recBtn)
        }
        setContentView(FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        if (hasCamera()) startCamera()
        else permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun toggleQuality() {
        if (recording != null) return
        uhd = !uhd
        qualBtn.text = if (uhd) "Kualitas: 4K" else "Kualitas: FHD"
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Stabilisasi pratinjau (EIS) + OIS lensa
            val previewBuilder = Preview.Builder().setPreviewStabilizationEnabled(true)
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val quality = if (uhd) Quality.UHD else Quality.FHD
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .build()
            // Stabilisasi video (EIS) + OIS lensa
            val vcBuilder = VideoCapture.Builder(recorder).setVideoStabilizationEnabled(true)
            Camera2Interop.Extender(vcBuilder).setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            
            )
            videoCapture = vcBuilder.build()

            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecord() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
        val name = "STAB_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/StabCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        val audioOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        recording = vc.output.prepareRecording(this, opts)
            .apply { if (audioOk) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> recBtn.text = "STOP"
                    is VideoRecordEvent.Finalize -> {
                        recBtn.text = "REKAM"
                        recording = null
                        val msg = if (ev.hasError()) "Error rekam: ${ev.error}" else "Tersimpan di Movies/StabCam"
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
    }
}
