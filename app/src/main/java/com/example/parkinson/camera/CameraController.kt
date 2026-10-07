package com.example.parkinson.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class CameraController(
    private val context: Context,
) {
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.CameraInitializing)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _cameraLens = MutableStateFlow(CameraLens.FRONT)
    val cameraLens: StateFlow<CameraLens> = _cameraLens.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun updateCameraState(state: CameraState) {
        _cameraState.value = state
    }

    fun setCameraLens(lens: CameraLens) {
        _cameraLens.value = lens
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        frameAnalyzer: FrameAnalyzer? = null,
        onPreviewReady: (Preview) -> Unit,
    ) {
        _cameraState.value = CameraState.CameraInitializing

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val cameraSelector = when (_cameraLens.value) {
                        CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                        CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    // Check if camera lens is available
                    if (!provider.hasCamera(cameraSelector)) {
                        _cameraState.value = CameraState.CameraError("گوشی شما دارای این دوربین نیست.")
                        return@addListener
                    }

                    // 1. Build Preview UseCase
                    val preview = Preview.Builder().build()
                    previewUseCase = preview

                    // 2. Build ImageAnalysis UseCase (Backpressure: keep latest frame)
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(
                        analysisExecutor,
                        CameraAnalyzer(frameAnalyzer),
                    )
                    imageAnalysisUseCase = imageAnalysis

                    // 3. Unbind previous use cases and bind to LifecycleOwner
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )

                    onPreviewReady(preview)
                    _cameraState.value = CameraState.CameraReady
                } catch (e: Exception) {
                    Log.e("CameraController", "Failed to bind camera use cases", e)
                    _cameraState.value = CameraState.CameraError("خطا در راه‌اندازی دوربین.")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbindCamera() {
        try {
            cameraProvider?.unbindAll()
            _cameraState.value = CameraState.CameraInitializing
        } catch (e: Exception) {
            Log.e("CameraController", "Error unbinding camera provider", e)
        }
    }

    fun releaseResources() {
        unbindCamera()
        if (!analysisExecutor.isShutdown) {
            analysisExecutor.shutdown()
        }
    }
}
