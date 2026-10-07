package com.example.parkinson.ui.screens.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.parkinson.R
import com.example.parkinson.camera.CameraController
import com.example.parkinson.camera.CameraLens
import com.example.parkinson.camera.CameraPreview
import com.example.parkinson.camera.CameraState
import com.example.parkinson.mediapipe.HandLandmarkerManager
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.ui.components.PrimaryButton

@Composable
fun FingerTappingCameraScreen(
    selectedHand: SelectedHand?,
    onNextClicked: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val cameraController = remember { CameraController(context) }
    val cameraState by cameraController.cameraState.collectAsState()
    val cameraLens by cameraController.cameraLens.collectAsState()

    val handLandmarkerManager = remember {
        HandLandmarkerManager(context)
    }
    val trackingState by handLandmarkerManager.result.collectAsState()

    var previewUseCase by remember { mutableStateOf<Preview?>(null) }

    // Hand label text
    val handText = when (selectedHand) {
        SelectedHand.RIGHT -> stringResource(R.string.hand_right)
        SelectedHand.LEFT -> stringResource(R.string.hand_left)
        null -> stringResource(R.string.hand_right)
    }

    // Permission state
    var isPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isPermanentlyDenied by remember { mutableStateOf(value = false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isPermissionGranted = granted
        if (granted) {
            cameraController.updateCameraState(CameraState.PermissionGranted)
        } else {
            val activity = context as? Activity
            val showRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } ?: true

            if (!showRationale) {
                isPermanentlyDenied = true
                cameraController.updateCameraState(CameraState.PermissionPermanentlyDenied)
            } else {
                cameraController.updateCameraState(CameraState.PermissionDenied)
            }
        }
    }

    LaunchedEffect(selectedHand) {
        handLandmarkerManager.expectedHand = when (selectedHand) {
            SelectedHand.LEFT -> HandSide.LEFT
            else -> HandSide.RIGHT
        }
    }

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            cameraController.bindCamera(
                lifecycleOwner = lifecycleOwner,
                frameAnalyzer = handLandmarkerManager,
                onPreviewReady = { preview -> previewUseCase = preview },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController.releaseResources()
            handLandmarkerManager.close()
        }
    }

    // Status / Guide message evaluation
    val (guideMessage, patientStatusText, qualityText, isHandValid) = when (val state = trackingState) {
        is HandTrackingResult.HandDetected -> {
            val quality = confidenceToQualityText(state.confidence)
            Quadruple(
                "عالی! دست شما در موقعیت مناسب قرار دارد.",
                "$handText شناسایی شد ✓",
                "کیفیت تصویر: $quality",
                true
            )
        }

        is HandTrackingResult.WrongHandDetected -> {
            Quadruple(
                "لطفاً $handText خود را مقابل دوربین قرار دهید.",
                "دست اشتباه شناسایی شد ⚠️",
                "کیفیت تصویر: نامناسب",
                false
            )
        }

        is HandTrackingResult.TrackingLost, is HandTrackingResult.LowConfidence -> {
            Quadruple(
                "لطفاً دست خود را کمی ثابت‌تر نگه دارید.",
                "در حال لرزش‌گیری...",
                "کیفیت تصویر: متوسط",
                false
            )
        }

        is HandTrackingResult.MultipleHandsDetected -> {
            Quadruple(
                "فقط یک دست باید مقابل دوربین باشد.",
                "بیش از یک دست دیده می‌شود ⚠️",
                "کیفیت تصویر: نامناسب",
                false
            )
        }

        is HandTrackingResult.Error -> {
            Quadruple(
                "امکان آماده‌سازی تشخیص دست وجود ندارد.",
                "خطا در تشخیص",
                "کیفیت تصویر: نامشخص",
                false
            )
        }

        else -> {
            Quadruple(
                stringResource(R.string.guide_place_hand),
                "در انتظار شناسایی دست...",
                "کیفیت تصویر: نامشخص",
                false
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Title + Selected Hand
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.finger_tapping_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✋", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = handText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Camera Status Badge
                CameraStatusPill(cameraState = cameraState, isHandValid = isHandValid)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Camera Area / Permission Handling
            if (!isPermissionGranted) {
                CameraPermissionCard(
                    isPermanentlyDenied = isPermanentlyDenied,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppSettings(context) }
                )
            } else {
                // Live Camera Preview Card with MediaPipe Landmark Overlay & Guide Overlay
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // 1. Live Camera View
                        CameraPreview(
                            preview = previewUseCase,
                            modifier = Modifier.fillMaxSize()
                        )

                        // 2. MediaPipe Hand Landmark Overlay
                        // Front camera preview is mirrored, so the overlay must be mirrored too.
                        HandLandmarkOverlay(
                            result = trackingState,
                            mirror = cameraLens == CameraLens.FRONT,
                            modifier = Modifier.fillMaxSize()
                        )

                        // 3. Subtle Guide Frame Overlay
                        val guideBorderColor = if (isHandValid) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.82f)
                                .border(
                                    border = BorderStroke(
                                        width = if (isHandValid) 3.dp else 2.5.dp,
                                        color = guideBorderColor
                                    ),
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = guideMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isHandValid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.85f
                                        )
                                    )
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        // Patient Status Bar below frame
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        ) {
                            Text(
                                text = patientStatusText,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isHandValid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Technical Quality Indicator Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📊 $qualityText",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (isHandValid) {
                        Text(
                            text = "دست آماده است ✓",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Privacy Note
            Text(
                text = "🔒 " + stringResource(R.string.privacy_camera_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Primary Action Button
        PrimaryButton(
            text = stringResource(R.string.btn_prepare_test),
            onClick = onNextClicked,
            enabled = (isPermissionGranted && cameraState is CameraState.CameraReady && isHandValid),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun confidenceToQualityText(confidence: Float): String = when {
    confidence >= 0.85f -> "عالی"
    confidence >= 0.70f -> "خوب"
    else -> "متوسط"
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

@Composable
private fun CameraStatusPill(
    cameraState: CameraState,
    isHandValid: Boolean,
) {
    val (textRes, color, showProgress) = when {
        cameraState is CameraState.CameraReady && isHandValid -> Triple(
            R.string.camera_status_ready,
            MaterialTheme.colorScheme.secondary,
            false
        )

        cameraState is CameraState.CameraError -> Triple(
            R.string.camera_status_unavailable,
            MaterialTheme.colorScheme.error,
            false
        )

        else -> Triple(
            R.string.camera_status_initializing,
            MaterialTheme.colorScheme.primary,
            true
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = color
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CameraPermissionCard(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📷",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isPermanentlyDenied) {
                    stringResource(R.string.camera_denied_title)
                } else {
                    stringResource(R.string.camera_permission_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.camera_permission_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isPermanentlyDenied) {
                PrimaryButton(
                    text = stringResource(R.string.btn_open_settings),
                    onClick = onOpenSettings
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.btn_enable_camera),
                    onClick = onRequestPermission
                )
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}
