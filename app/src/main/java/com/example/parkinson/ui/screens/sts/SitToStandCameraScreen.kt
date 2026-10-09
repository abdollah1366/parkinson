package com.example.parkinson.ui.screens.sts

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import com.example.parkinson.gait.LivePoseStatus
import com.example.parkinson.gait.PoseLandmarkerManager
import com.example.parkinson.sts.CalibrationIssue
import com.example.parkinson.sts.SitToStandError
import com.example.parkinson.sts.SitToStandPhase
import com.example.parkinson.sts.SitToStandSession
import com.example.parkinson.sts.SitToStandState
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.ui.screens.camera.CameraErrorCard
import com.example.parkinson.ui.screens.camera.CameraPermissionCard
import com.example.parkinson.ui.screens.camera.CameraStatusPill
import com.example.parkinson.ui.screens.camera.openAppSettings
import com.example.parkinson.ui.screens.invalid.InvalidResultKind

/** Share of recent frames with a usable body needed before calibration may start (engineering guide). */
private const val BODY_VISIBLE_PERCENT = 80.0

/**
 * Camera, positioning, seated calibration, countdown and the five-repetition attempt. The back camera sees the
 * person from the side. Navigates to the result on DONE and to the invalid screen on INVALID or ERROR.
 */
@Composable
fun SitToStandCameraScreen(
    session: SitToStandSession,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val cameraController = remember { CameraController(context).also { it.setCameraLens(CameraLens.BACK) } }
    val cameraState by cameraController.cameraState.collectAsState()
    val poseManager = remember { PoseLandmarkerManager(context) }
    val sessionState by session.state.collectAsState()
    val baseline by session.baseline.collectAsState()
    val calibrationIssue by session.calibrationIssue.collectAsState()
    val live by session.live.collectAsState()
    val guide by session.guideValidPercent.collectAsState()

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
        // Every pose result goes straight to the session (it extracts the side's joints itself).
        poseManager.sampleListener = { sample -> session.onPoseSample(sample) }
        onDispose {
            poseManager.sampleListener = null
            // Rotation restarts the camera, so a running attempt cannot continue: INVALID. Leaving discards it.
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
                // App sent to the background during an attempt -> INVALID.
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

    LaunchedEffect(cameraState) {
        if (cameraState is CameraState.CameraError) session.fail(SitToStandError.CAMERA_FAILURE)
    }

    LaunchedEffect(sessionState) {
        when (val s = sessionState) {
            is SitToStandState.Done -> onCompleted(s.result.assessmentId)
            is SitToStandState.Invalid, is SitToStandState.Error -> InvalidResultKind.fromSitToStand(s)?.let(onInvalid)
            else -> Unit
        }
    }

    val modelReady = poseManager.isReady
    val cameraReady = isPermissionGranted && cameraState is CameraState.CameraReady && modelReady
    val idle = sessionState == SitToStandState.Idle
    val running = sessionState is SitToStandState.Countdown || sessionState == SitToStandState.Active
    val bodyVisible = (guide ?: 0.0) >= BODY_VISIBLE_PERCENT
    val canCalibrate = cameraReady && idle && bodyVisible
    val canStart = cameraReady && idle && baseline != null

    val guideText = when {
        !modelReady -> stringResource(R.string.sts_guide_model_missing)
        sessionState == SitToStandState.Calibrating -> stringResource(R.string.sts_guide_calibrating)
        sessionState is SitToStandState.Countdown ->
            stringResource(R.string.sts_session_countdown, (sessionState as SitToStandState.Countdown).secondsLeft.toPersianDigits())
        sessionState == SitToStandState.Active -> stringResource(phaseRes(live.phase))
        sessionState == SitToStandState.Processing -> stringResource(R.string.session_processing)
        baseline != null -> stringResource(R.string.sts_guide_seated_ready)
        calibrationIssue != null -> calibrationMessage(calibrationIssue!!)
        bodyVisible -> stringResource(R.string.sts_guide_body_ready)
        else -> stringResource(R.string.sts_guide_place_body)
    }
    val bodyShareText = PersianFormat.integer(Math.round(guide ?: 0.0).toInt())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.test_sts_title),
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
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val previewDescription = stringResource(R.string.cd_camera_preview)
                        CameraPreview(
                            preview = previewUseCase,
                            modifier = Modifier.fillMaxSize().semantics { contentDescription = previewDescription }
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
                        if (sessionState == SitToStandState.Active) {
                            Text(
                                text = stringResource(
                                    R.string.sts_live_repetitions,
                                    PersianFormat.integer(live.repetitions),
                                    PersianFormat.integer(live.targetRepetitions)
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live status: body share in the recent frames, and during the attempt the time and the phase.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.sts_live_body_share, bodyShareText),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (sessionState == SitToStandState.Active) {
                        Text(
                            text = stringResource(
                                R.string.sts_live_elapsed,
                                PersianFormat.decimal(live.elapsedMs / 1000.0, 1)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (idle) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(
                            if (baseline == null) R.string.sts_camera_hint_calibrate else R.string.sts_camera_hint_start
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (running) {
                // Safety reminder during the attempt.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.sts_stop_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
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

        if (idle) {
            Text(
                text = stringResource(
                    when {
                        canStart -> R.string.sts_ready_go
                        baseline == null && canCalibrate -> R.string.sts_ready_calibrate
                        baseline == null -> R.string.sts_ready_wait
                        else -> R.string.sts_ready_wait
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (canStart || canCalibrate) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (canStart || canCalibrate) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (baseline == null) {
                PrimaryButton(
                    text = stringResource(R.string.sts_btn_calibrate),
                    onClick = { session.startCalibration() },
                    enabled = canCalibrate,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.btn_start_test),
                    onClick = { session.start() },
                    enabled = canStart,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { session.startCalibration() },
                    enabled = cameraReady,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.sts_btn_recalibrate))
                }
            }
        } else if (sessionState == SitToStandState.Calibrating) {
            Text(
                text = stringResource(R.string.sts_guide_calibrating),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        } else if (running) {
            // Pause stops the attempt as interrupted (the result screen never shows it); the person retries.
            OutlinedButton(
                onClick = { session.abort() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.sts_btn_pause))
            }
            OutlinedButton(
                onClick = { session.reset() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.sts_btn_cancel))
            }
        }
    }
}

@StringRes
private fun phaseRes(phase: SitToStandPhase): Int = when (phase) {
    SitToStandPhase.READY -> R.string.sts_phase_ready
    SitToStandPhase.SEATED -> R.string.sts_phase_seated
    SitToStandPhase.STANDING_UP -> R.string.sts_phase_standing_up
    SitToStandPhase.STANDING -> R.string.sts_phase_standing
    SitToStandPhase.SITTING_DOWN -> R.string.sts_phase_sitting_down
    SitToStandPhase.REPETITION_COMPLETED -> R.string.sts_phase_repetition_done
    SitToStandPhase.TEST_COMPLETED -> R.string.sts_phase_completed
    SitToStandPhase.PAUSED_OR_INVALID -> R.string.sts_phase_invalid
    SitToStandPhase.ERROR -> R.string.sts_phase_error
}

@Composable
private fun calibrationMessage(issue: CalibrationIssue): String = stringResource(
    when (issue) {
        CalibrationIssue.TOO_FEW_VALID_FRAMES -> R.string.sts_calib_few_frames
        CalibrationIssue.NOT_SEATED -> R.string.sts_calib_not_seated
        CalibrationIssue.UNSTABLE -> R.string.sts_calib_unstable
    }
)
