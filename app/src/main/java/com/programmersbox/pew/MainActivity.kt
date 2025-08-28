package com.programmersbox.pew

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.pew.ui.theme.PewTheme
import kotlinx.coroutines.delay
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PewTheme {
                CameraView(viewModel)
            }
        }
    }

    @Composable
    private fun CameraView(
        viewModel: CameraViewModel,
    ) {
        var hasCameraPermission by remember { mutableStateOf(false) }
        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                if (granted.all { it.value }) {
                    hasCameraPermission = true
                }
            }
        val surfaceRequest by viewModel.surfaceRequests.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner, viewModel.cameraSelector) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
            viewModel.bindToCamera(context.applicationContext, lifecycleOwner)
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }

            var autofocusRequest by remember { mutableStateOf(UUID.randomUUID() to Offset.Unspecified) }

            val autofocusRequestId = autofocusRequest.first
            // Show the autofocus indicator if the offset is specified
            val showAutofocusIndicator = autofocusRequest.second.isSpecified
            // Cache the initial coords for each autofocus request
            val autofocusCoords = remember(autofocusRequestId) { autofocusRequest.second }

            // Queue hiding the request for each unique autofocus tap
            if (showAutofocusIndicator) {
                LaunchedEffect(autofocusRequestId) {
                    delay(1000)
                    // Clear the offset to finish the request and hide the indicator
                    autofocusRequest = autofocusRequestId to Offset.Unspecified
                }
            }

            surfaceRequest?.let { request ->
                val coordinateTransformer = remember { MutableCoordinateTransformer() }

                CameraXViewfinder(
                    surfaceRequest = request,
                    coordinateTransformer = coordinateTransformer,
                    implementationMode = ImplementationMode.EMBEDDED,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(viewModel, coordinateTransformer) {
                            detectTapGestures(
                                onPress = { tapCoords ->
                                    with(coordinateTransformer) {
                                        viewModel.tapToFocus(tapCoords.transform())
                                    }
                                    autofocusRequest = UUID.randomUUID() to tapCoords
                                }
                            )
                        }
                        .pointerInput(viewModel) {
                            detectTransformGestures { _, _, zoom, _ ->
                                currentZoomRatio *= zoom
                                // Clamp the zoom ratio to valid range (e.g., minZoomRatio to maxZoomRatio)
                                val zoomState = viewModel.cameraInfo?.zoomState?.value
                                zoomState?.let {
                                    currentZoomRatio = currentZoomRatio.coerceIn(
                                        it.minZoomRatio,
                                        it.maxZoomRatio
                                    )
                                    viewModel.setZoom(currentZoomRatio)
                                }
                            }
                        },
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    //.background(Color.Black)
                ) {
                    // Draw a black rectangle to cover the entire canvas initially
                    drawRect(Color.Black.copy(alpha = .85f))

                    // Draw the transparent circle using BlendMode.Clear
                    // This will "clear" the pixels where the circle is drawn,
                    // revealing the content underneath (which is the main Box's black background).
                    // Compute a slightly smaller radius at or near max zoom
                    val zoomState = viewModel.cameraInfo?.zoomState?.value
                    val baseRadius = size.minDimension / 2f
                    val shrinkFraction = 0.05f // ~3% shrink at max zoom
                    val adjustedRadius = if (zoomState != null) {
                        val minZ = zoomState.minZoomRatio
                        val maxZ = zoomState.maxZoomRatio
                        val curZ = currentZoomRatio.coerceIn(minZ, maxZ)
                        // Interpolate shrink based on proximity to max zoom (0..1)
                        val t = if (maxZ > minZ) ((curZ - minZ) / (maxZ - minZ)) else 0f
                        val shrink = baseRadius * shrinkFraction * t
                        baseRadius - shrink
                    } else baseRadius

                    drawCircle(
                        color = Color.Transparent, // The color doesn't matter much with BlendMode.Clear
                        radius = adjustedRadius, // Slightly smaller at max zoom
                        center = center,
                        blendMode = BlendMode.Clear
                    )

                    // Draw binocular-style scope lines from the circle edge inward
                    val lineLength = adjustedRadius * 0.18f
                    val stroke = 2.dp.toPx()
                    // Top line: from top edge toward center
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x, center.y - adjustedRadius),
                        end = Offset(center.x, center.y - adjustedRadius + lineLength),
                        strokeWidth = stroke
                    )
                    // Bottom line: from bottom edge toward center
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x, center.y + adjustedRadius),
                        end = Offset(center.x, center.y + adjustedRadius - lineLength),
                        strokeWidth = stroke
                    )
                    // Left line: from left edge toward center
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x - adjustedRadius, center.y),
                        end = Offset(center.x - adjustedRadius + lineLength, center.y),
                        strokeWidth = stroke
                    )
                    // Right line: from right edge toward center
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x + adjustedRadius, center.y),
                        end = Offset(center.x + adjustedRadius - lineLength, center.y),
                        strokeWidth = stroke
                    )
                }

                Icon(
                    Icons.Default.Add,
                    null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )

                AnimatedVisibility(
                    visible = showAutofocusIndicator,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .offset { autofocusCoords.takeOrElse { Offset.Zero }.round() }
                        .offset((-24).dp, (-24).dp)
                ) {
                    Spacer(
                        Modifier
                            .border(2.dp, Color.White, CircleShape)
                            .size(48.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .align(Alignment.TopCenter)
                    .systemBarsPadding(),
            ) {
                val zoomState = viewModel.cameraInfo?.zoomState?.value

                zoomState?.let {
                    Text(
                        "${it.minZoomRatio}x",
                        color = Color.White,
                    )
                }

                Text(
                    "${currentZoomRatio}x",
                    color = Color.White,
                )

                zoomState?.let {
                    Text(
                        "${it.maxZoomRatio}x",
                        color = Color.White,
                    )
                }
            }


            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding(),
            ) {
                OutlinedIconButton(
                    onClick = { viewModel.flipCamera() },
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        contentColor = Color.White
                    ),
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Flip Camera"
                    )
                }

                OutlinedIconButton(
                    onClick = { viewModel.takePicture(context) },
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        contentColor = Color.White
                    ),
                    border = ButtonDefaults
                        .outlinedButtonBorder(enabled = true)
                        .copy(
                            brush = SolidColor(Color.White),
                            width = 3.dp,
                        ),
                ) {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = "Take picture"
                    )
                }

                IconButton(
                    onClick = {},
                    modifier = Modifier.alpha(0f)
                ) {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = "Invisible",
                    )
                }
            }
        }
    }
}