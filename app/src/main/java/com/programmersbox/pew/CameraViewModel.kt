package com.programmersbox.pew

import android.content.ContentValues
import android.content.Context
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.provider.MediaStore
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors


class CameraViewModel: ViewModel() {
    private val mediaActionSound = MediaActionSound()

    private val _surfaceRequests = MutableStateFlow<SurfaceRequest?>(null)

    val surfaceRequests: StateFlow<SurfaceRequest?>
        get() = _surfaceRequests.asStateFlow()

    private val imageCapture = ImageCapture.Builder()
        .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
        .build()

    private val videoCapture = VideoCapture.withOutput(
        Recorder.Builder()
            //.setRequiredFreeStorageBytes() //It has a default of 50MB
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()
    )

    private val cameraPreviewUseCase = Preview.Builder()
        .build()
        .apply {
            setSurfaceProvider { newSurfaceRequest ->
                _surfaceRequests.update { newSurfaceRequest }

                surfaceMeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                    newSurfaceRequest.resolution.width.toFloat(),
                    newSurfaceRequest.resolution.height.toFloat()
                )
            }
        }

    private var surfaceMeteringPointFactory: SurfaceOrientedMeteringPointFactory? = null
    private var cameraControl: CameraControl? = null
    var cameraInfo: CameraInfo? = null
    var mediaPlayer: MediaPlayer? = null

    var cameraSelector by mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA)

    private val dateFormatter = SimpleDateFormat("yyyy_MMM_dd_HH_mm_ss", Locale.US)

    private var canProgress = false
    var savingImageProgress by mutableStateOf<Float?>(null)

    init {
        mediaActionSound.load(MediaActionSound.SHUTTER_CLICK)
        mediaActionSound.load(MediaActionSound.START_VIDEO_RECORDING)
        mediaActionSound.load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    fun takePicture(context: Context) {
        val timeInMillis = System.currentTimeMillis()
        val name = dateFormatter.format(timeInMillis)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val directory = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            directory,
            contentValues,
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    println(outputFileResults.savedUri)
                    savingImageProgress = null
                }

                override fun onCaptureStarted() {
                    super.onCaptureStarted()
                    //mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
                    playSound()
                    if (canProgress) savingImageProgress = 0f
                }

                //TODO: Check on watch
                override fun onCaptureProcessProgressed(progress: Int) {
                    super.onCaptureProcessProgressed(progress)
                    if (canProgress) savingImageProgress = progress / 100f
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
            }
        )
    }

    fun tapToFocus(tapCoords: Offset) {
        val point = surfaceMeteringPointFactory?.createPoint(tapCoords.x, tapCoords.y)
        if (point != null) {
            val meteringAction = FocusMeteringAction.Builder(point).build()
            cameraControl?.startFocusAndMetering(meteringAction)
        }
    }

    fun setZoom(zoom: Float) {
        cameraControl?.setZoomRatio(zoom)
    }

    //This handles the entire lifecycle
    suspend fun bindToCamera(
        appContext: Context,
        lifecycleOwner: LifecycleOwner
    ) {
        val processCameraProvider = ProcessCameraProvider.awaitInstance(appContext)
        val camera = processCameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            cameraPreviewUseCase,
            imageCapture,
            videoCapture
        )

        val capabilities = ImageCapture.getImageCaptureCapabilities(camera.cameraInfo)
        canProgress = capabilities.isCaptureProcessProgressSupported

        cameraControl = camera.cameraControl
        cameraInfo = camera.cameraInfo

        mediaPlayer = MediaPlayer.create(appContext, R.raw.pew_pew_lame_sound_effect)

        // Cancellation signals we're done with the camera
        try {
            awaitCancellation()
        } finally {
            processCameraProvider.unbindAll()
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun flipCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun playSound() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
                it.prepare() // Re-prepare to play from beginning
            }
        }
        mediaPlayer?.start()
    }

    override fun onCleared() {
        super.onCleared()
        mediaActionSound.release()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
