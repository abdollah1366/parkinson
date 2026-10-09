package com.example.parkinson.ui.screens.openclose

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.parkinson.R
import com.example.parkinson.camera.CameraController
import com.example.parkinson.camera.CameraLens
import com.example.parkinson.camera.CameraPreview
import com.example.parkinson.camera.CameraState
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandLandmarkerManager
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.mediapipe.toHandSide
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.openclose.HandOpenCloseSession
import com.example.parkinson.openclose.OpenCloseError
import com.example.parkinson.openclose.OpenCloseState
import com.example.parkinson.openclose.isActive
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.ui.screens.camera.CameraErrorCard
import com.example.parkinson.ui.screens.camera.CameraPermissionCard
import com.example.parkinson.ui.screens.camera.CameraStatusPill
import com.example.parkinson.ui.screens.camera.HandLandmarkOverlay
import com.example.parkinson.ui.screens.camera.openAppSettings
import com.example.parkinson.ui.screens.invalid.InvalidResultKind

/**
 * Camera + hand tracking + Hand Opening/Closing session. Navigates away as soon as the session has
 * an outcome: to the result on DONE, to the invalid-result screen on INVALID or ERROR.
 */
@Composable
fun HandOpenCloseCameraScreen(
    selectedHand: SelectedHand?,
    session: HandOpenCloseSession,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val cameraController = remember { CameraController(context) }
    val cameraState by cameraController.cameraState.collectAsState()
    val cameraLens by cameraController.cameraLens.collectAsState()

    val handLandmarkerManager = remember { HandLandmarkerManager(context) }
    val trackingState by handLandmarkerManager.result.collectAsState()
    val vision by handLandmarkerManager.liveStatus.collectAsState()

    val sessionState by session.state.collectAsState()
    val liveCycles by session.liveCycles.collectAsState()

    var previewUseCase by remember { mutableStateOf<Preview?>(null) }

    val handText = when (selectedHand) {
        SelectedHand.LEFT -> stringResource(R.string.hand_left)
        SelectedHand.RIGHT, null -> stringResource(R.string.hand_right)
    }

    var isPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
        handLandmarkerManager.expectedHand = (selectedHand ?: SelectedHand.RIGHT).toHandSide()
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
        // Every MediaPipe result goes straight to the session (no frame-dropping StateFlow).
        handLandmarkerManager.resultListener = session::onTrackingResult
        onDispose {
            handLandmarkerManager.resultListener = null
            // Rotation restarts the camera, so a running session cannot continue -> INVALID.
            // Leaving the screen discards the session entirely.
            if ((context as? Activity)?.isChangingConfigurations == true) {
                session.abort()
            } else {
                session.reset()
            }
            cameraController.releaseResources()
            handLandmarkerManager.close()
        }
    }

    // Observe the ACTIVITY lifecycle: leaving this screen also stops the navigation entry and must reset.
    val activityLifecycleOwner = (context as? LifecycleOwner) ?: lifecycleOwner
    DisposableEffect(activityLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // App sent to the background during a session -> INVALID.
                Lifecycle.Event.ON_STOP -> session.abort()
                // Permission may have been granted in the system settings meanwhile.
                Lifecycle.Event.ON_RESUME -> if (!isPermissionGranted) {
                    isPermissionGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                }

                else -> Unit
            }
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A camera failure ends a running session with an error instead of a partial result.
    LaunchedEffect(cameraState) {
        if (cameraState is CameraState.CameraError) session.fail(OpenCloseError.CAMERA_FAILURE)
    }

    // Leave the screen as soon as the session has an outcome.
    LaunchedEffect(sessionState) {
        when (val s = sessionState) {
            is OpenCloseState.Done -> onCompleted(s.result.assessmentId)
            is OpenCloseState.Invalid, is OpenCloseState.Error -> InvalidResultKind.fromOpenClose(s)?.let(onInvalid)
            else -> Unit
        }
    }

    val liveStats by session.liveStats.collectAsState()
    val handOkText = stringResource(R.string.ft_live_hand_ok, handText)
    val (guideMessage, patientStatusText, isHandValid) = when (val state = trackingState) {
        is HandTrackingResult.HandDetected -> if (state.sideStatus == HandSideStatus.MISMATCH) {
            Triple(stringResource(R.string.ft_live_wrong_hand, handText), stringResource(R.string.ft_live_wrong_hand_status), false)
        } else {
            Triple(stringResource(R.string.ft_live_hand_ready), handOkText, true)
        }

        is HandTrackingResult.TrackingLost ->
            Triple(stringResource(R.string.ft_live_hand_lost), stringResource(R.string.ft_live_waiting), false)

        is HandTrackingResult.MultipleHandsDetected ->
            Triple(stringResource(R.string.ft_live_multiple_hands), stringResource(R.string.ft_live_multiple_hands), false)

        is HandTrackingResult.Error ->
            Triple(stringResource(R.string.ft_live_error), stringResource(R.string.ft_live_error), false)

        is HandTrackingResult.NoHandDetected ->
            Triple(stringResource(R.string.guide_place_hand), stringResource(R.string.ft_live_waiting), false)
    }
    val trackingPercent = PersianFormat.integer(Math.round(vision.trackingRate * 100).toInt())
    val qualityText = when {
        vision.cameraQuality != CameraQuality.GOOD &&
            vision.trackingQuality == HandTrackingQuality.POOR -> stringResource(R.string.ft_live_light_tracking_poor)
        vision.cameraQuality != CameraQuality.GOOD -> stringResource(R.string.ft_live_light_warning)
        vision.trackingQuality == HandTrackingQuality.GOOD -> stringResource(R.string.ft_live_tracking_good, trackingPercent)
        vision.trackingQuality == HandTrackingQuality.WARNING -> stringResource(R.string.ft_live_tracking_warning, trackingPercent)
        else -> stringResource(R.string.ft_live_tracking_poor)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.test_oc_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = handText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                CameraStatusPill(cameraState = cameraState, isHandValid = isHandValid)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isPermissionGranted) {
                CameraPermissionCard(
                    isPermanentlyDenied = isPermanentlyDenied,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppSettings(context) }
                )
            } else if (cameraState is CameraState.CameraError) {
                CameraErrorCard(
                    message = (cameraState as CameraState.CameraError).message,
                    onRetry = {
                        cameraController.bindCamera(
                            lifecycleOwner = lifecycleOwner,
                            frameAnalyzer = handLandmarkerManager,
                            onPreviewReady = { preview -> previewUseCase = preview },
                        )
                    }
                )
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val previewDescription = stringResource(R.string.cd_camera_preview)
                        CameraPreview(
                            preview = previewUseCase,
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = previewDescription }
                        )

                        // Front camera preview is mirrored, so the overlay must be mirrored too.
                        HandLandmarkOverlay(
                            result = trackingState,
                            mirror = cameraLens == CameraLens.FRONT,
                            modifier = Modifier.fillMaxSize()
                        )

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
                                        if (isHandValid) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }

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

                        SessionOverlay(sessionState = sessionState, cycles = liveCycles)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Technical status: image brightness and tracking share. Warnings only, never a score.
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

            // Demonstration of the movement while preparing and recording (drawing only, see HandOpenCloseCue).
            if (sessionState !is OpenCloseState.Processing && sessionState !is OpenCloseState.Done) {
                HandOpenCloseCue(modifier = Modifier.padding(vertical = 4.dp))
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "🔒 " + stringResource(R.string.privacy_camera_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        val canStart = isPermissionGranted && cameraState is CameraState.CameraReady && isHandValid
        val running = sessionState.isActive
        // Readiness step, shown just above the start button. It only reflects the live hand check.
        if (sessionState == OpenCloseState.Idle) {
            Text(
                text = stringResource(if (canStart) R.string.oc_ready_go else R.string.oc_ready_wait),
                style = MaterialTheme.typography.bodyMedium,
                color = if (canStart) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (canStart) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        PrimaryButton(
            text = stringResource(if (running) R.string.btn_test_running else R.string.btn_start_test),
            onClick = { session.start(selectedHand ?: SelectedHand.RIGHT) },
            enabled = canStart && sessionState == OpenCloseState.Idle,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun SessionOverlay(sessionState: OpenCloseState, cycles: Int) {
    val text = when (sessionState) {
        is OpenCloseState.Countdown -> stringResource(R.string.oc_session_countdown, sessionState.secondsLeft.toPersianDigits())
        is OpenCloseState.Recording -> stringResource(
            R.string.oc_session_recording,
            sessionState.secondsLeft.toPersianDigits(),
            PersianFormat.integer(cycles)
        )

        is OpenCloseState.Processing -> stringResource(R.string.session_processing)
        else -> return
    }
    Text(
        text = text,
        style = if (sessionState is OpenCloseState.Countdown) {
            MaterialTheme.typography.headlineLarge
        } else {
            MaterialTheme.typography.titleLarge
        },
        color = MaterialTheme.colorScheme.onPrimary,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            // Announced by screen readers as it changes.
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}
