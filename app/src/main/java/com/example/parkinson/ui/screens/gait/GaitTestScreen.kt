package com.example.parkinson.ui.screens.gait

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.parkinson.gait.GaitError
import com.example.parkinson.gait.GaitSession
import com.example.parkinson.gait.GaitState
import com.example.parkinson.gait.LivePoseStatus
import com.example.parkinson.gait.PoseLandmarkerManager
import com.example.parkinson.gait.isActive
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.screens.camera.CameraErrorCard
import com.example.parkinson.ui.screens.camera.CameraPermissionCard
import com.example.parkinson.ui.screens.camera.CameraStatusPill
import com.example.parkinson.ui.screens.camera.openAppSettings
import com.example.parkinson.ui.screens.invalid.InvalidResultKind

/**
 * Camera + full-body pose + walking session. Navigates away as soon as the session has an outcome: to the
 * result on DONE, to the invalid-result screen on INVALID or ERROR. The back camera is used so the whole
 * body is seen at the distance the protocol asks for.
 */
@Composable
fun GaitTestScreen(
    session: GaitSession,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val cameraController = remember {
        CameraController(context).also { it.setCameraLens(CameraLens.BACK) }
    }
    val cameraState by cameraController.cameraState.collectAsState()

    val poseManager = remember { PoseLandmarkerManager(context) }
    val live = remember { LivePoseStatus() }
    val bodyPercent by live.validPercent.collectAsState()
    val sessionState by session.state.collectAsState()
    val liveStats by session.liveStats.collectAsState()

    var previewUseCase by remember { mutableStateOf<Preview?>(null) }

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

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            cameraController.bindCamera(
                lifecycleOwner = lifecycleOwner,
                frameAnalyzer = poseManager,
                onPreviewReady = { preview -> previewUseCase = preview },
            )
        }
    }

    DisposableEffect(Unit) {
        // Every pose result goes to the live guide and straight to the session (no frame-dropping StateFlow).
        poseManager.resultListener = { frame ->
            live.add(frame)
            session.onPoseFrame(frame)
        }
        onDispose {
            poseManager.resultListener = null
            // Rotation restarts the camera, so a running walk cannot continue -> INVALID.
            // Leaving the screen discards the session entirely.
            if ((context as? Activity)?.isChangingConfigurations == true) {
                session.abort()
            } else {
                session.reset()
            }
            cameraController.releaseResources()
            poseManager.close()
        }
    }

    val activityLifecycleOwner = (context as? LifecycleOwner) ?: lifecycleOwner
    DisposableEffect(activityLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // App sent to the background during a walk -> INVALID.
                Lifecycle.Event.ON_STOP -> session.abort()
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

    // A camera failure ends a running walk with an error instead of a partial result.
    LaunchedEffect(cameraState) {
        if (cameraState is CameraState.CameraError) session.fail(GaitError.CAMERA_FAILURE)
    }

    LaunchedEffect(sessionState) {
        when (val s = sessionState) {
            is GaitState.Done -> onCompleted(s.result.assessmentId)
            is GaitState.Invalid, is GaitState.Error -> InvalidResultKind.fromGait(s)?.let(onInvalid)
            else -> Unit
        }
    }

    val modelReady = poseManager.isReady
    val bodyVisible = (bodyPercent ?: 0.0) >= BODY_VISIBLE_PERCENT
    val canStart = isPermissionGranted && cameraState is CameraState.CameraReady && modelReady && bodyVisible

    val guideText = when {
        !modelReady -> stringResource(R.string.gait_guide_model_missing)
        bodyPercent == null -> stringResource(R.string.gait_guide_place_body)
        bodyVisible -> stringResource(R.string.gait_guide_body_ready)
        else -> stringResource(R.string.gait_guide_body_partial)
    }
    val bodyPercentText = PersianFormat.integer(Math.round(bodyPercent ?: 0.0).toInt())

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
                Text(
                    text = stringResource(R.string.test_gait_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                CameraStatusPill(cameraState = cameraState, isHandValid = bodyVisible)
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
                            frameAnalyzer = poseManager,
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

                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.9f)
                                .border(
                                    border = BorderStroke(
                                        width = if (bodyVisible) 3.dp else 2.5.dp,
                                        color = if (bodyVisible) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    ),
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = guideText,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (bodyVisible) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        SessionOverlay(sessionState = sessionState)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Technical status: how much of the recent frames show the whole body. Warnings only, never a score.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.gait_live_body_share, bodyPercentText),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (sessionState is GaitState.Recording || sessionState is GaitState.Countdown) {
                        Text(
                            text = stringResource(R.string.gait_live_frames, liveStats.framesReceived.toPersianDigits()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (sessionState is GaitState.Idle) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.gait_camera_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
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

        val running = sessionState.isActive
        if (sessionState == GaitState.Idle) {
            Text(
                text = stringResource(if (canStart) R.string.gait_ready_go else R.string.gait_ready_wait),
                style = MaterialTheme.typography.bodyMedium,
                color = if (canStart) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (canStart) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        PrimaryButton(
            text = stringResource(if (running) R.string.btn_test_running else R.string.btn_start_test),
            onClick = { session.start() },
            enabled = canStart && sessionState == GaitState.Idle,
            modifier = Modifier.padding(top = 8.dp)
        )
        // Stop control: returns to IDLE without a result, so no invalid screen appears for a deliberate stop.
        if (sessionState is GaitState.Countdown || sessionState is GaitState.Recording) {
            OutlinedButton(
                onClick = { session.reset() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.gait_btn_cancel))
            }
        }
    }
}

/** Share of recent frames with a complete body needed before the walk may start (engineering guide, not a measure). */
private const val BODY_VISIBLE_PERCENT = 80.0

@Composable
private fun SessionOverlay(sessionState: GaitState) {
    val text = when (sessionState) {
        is GaitState.Countdown -> stringResource(R.string.gait_session_countdown, sessionState.secondsLeft.toPersianDigits())
        is GaitState.Recording -> stringResource(R.string.gait_session_walk, sessionState.secondsLeft.toPersianDigits())
        is GaitState.Processing -> stringResource(R.string.session_processing)
        else -> return
    }
    Text(
        text = text,
        style = if (sessionState is GaitState.Countdown) {
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
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}
