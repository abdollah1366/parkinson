package com.example.parkinson.camera

import androidx.camera.core.ImageProxy

/**
 * Clean boundary interface decoupling CameraX image analysis from specific CV frameworks (e.g., MediaPipe in Phase 4).
 */
interface FrameAnalyzer {
    fun analyze(imageProxy: ImageProxy)
}
