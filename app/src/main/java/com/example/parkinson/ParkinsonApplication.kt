package com.example.parkinson

import android.app.Application
import android.content.pm.ApplicationInfo
import com.example.parkinson.assessment.AndroidDeviceCapabilities
import com.example.parkinson.assessment.DeviceCapabilities
import com.example.parkinson.data.AssessmentDatabase
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.data.ProfileRepository
import com.example.parkinson.data.RoomProfileRepository
import com.example.parkinson.data.RoomAssessmentRepository
import com.example.parkinson.diagnostics.SensorDiagnostics
import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.sensors.AndroidMotionSensorSource
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.gait.GaitDiagnostics
import com.example.parkinson.norms.NormComparator
import com.example.parkinson.profile.ActivePatient
import com.example.parkinson.profile.PrefsActiveProfileStore
import com.example.parkinson.norms.ReferenceNorms
import com.example.parkinson.tremor.RestingTremorDiagnostics

/** Holds the app-wide singletons (a minimal manual dependency container). */
class ParkinsonApplication : Application() {

    private val database: AssessmentDatabase by lazy { AssessmentDatabase.build(this) }

    val profileRepository: ProfileRepository by lazy { RoomProfileRepository.from(database) }

    /** Who the tests are for; several people can be registered on one phone. */
    val activePatient: ActivePatient by lazy { ActivePatient(profileRepository, PrefsActiveProfileStore(this)) }

    val assessmentRepository: AssessmentRepository by lazy { RoomAssessmentRepository.from(database, activePatient.id) }

    val motionSensorSource: MotionSensorSource by lazy { AndroidMotionSensorSource(this) }

    val deviceCapabilities: DeviceCapabilities by lazy { AndroidDeviceCapabilities(this) }

    /** Published reference values bundled in assets (no server), read once on first use. */
    val normComparator: NormComparator by lazy {
        NormComparator(ReferenceNorms.parse(assets.open(ReferenceNorms.ASSET_PATH).bufferedReader().use { it.readText() }))
    }

    override fun onCreate() {
        super.onCreate()
        // Diagnostic Logcat output only in debuggable builds, never in release builds.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        TapDiagnostics.enabled = debuggable
        SensorDiagnostics.enabled = debuggable
        RestingTremorDiagnostics.enabled = debuggable
        GaitDiagnostics.enabled = debuggable
    }
}
