package com.example.parkinson

import android.app.Application
import android.content.pm.ApplicationInfo
import com.example.parkinson.data.AssessmentDatabase
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.data.RoomAssessmentRepository
import com.example.parkinson.diagnostics.TapDiagnostics

/** Holds the app-wide singletons (a minimal manual dependency container). */
class ParkinsonApplication : Application() {

    val assessmentRepository: AssessmentRepository by lazy {
        RoomAssessmentRepository(AssessmentDatabase.build(this).assessmentDao())
    }

    override fun onCreate() {
        super.onCreate()
        // Diagnostic Logcat output only in debuggable builds, never in release builds.
        TapDiagnostics.enabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
