package com.example.parkinson.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class CameraAnalyzer(
    private val frameAnalyzer: FrameAnalyzer? = null
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            frameAnalyzer?.analyze(imageProxy)
        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Error analyzing frame", e)
        } finally {
            imageProxy.close()
        }
    }
}
