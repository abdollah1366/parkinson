package com.example.parkinson

import android.app.Application
import android.content.pm.ApplicationInfo
import com.example.parkinson.assessment.AndroidDeviceCapabilities
import com.example.parkinson.assessment.DeviceCapabilities
import com.example.parkinson.data.AssessmentDatabase
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.data.RoomAssessmentRepository
import com.example.parkinson.diagnostics.SensorDiagnostics
import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.sensors.AndroidMotionSensorSource
import com.example.parkinson.sensors.MotionSensorSource

/** Holds the app-wide singletons (a minimal manual dependency container). */
class ParkinsonApplication : Application() {

    val assessmentRepository: AssessmentRepository by lazy {
        RoomAssessmentRepository.from(AssessmentDatabase.build(this))
    }

    val motionSensorSource: MotionSensorSource by lazy { AndroidMotionSensorSource(this) }

    val deviceCapabilities: DeviceCapabilities by lazy { AndroidDeviceCapabilities(this) }

    override fun onCreate() {
        super.onCreate()
        // Diagnostic Logcat output only in debuggable builds, never in release builds.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        TapDiagnostics.enabled = debuggable
        SensorDiagnostics.enabled = debuggable
    }
}
