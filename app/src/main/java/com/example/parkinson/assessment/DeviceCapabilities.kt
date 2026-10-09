package com.example.parkinson.assessment

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log

/** Answers whether this device can satisfy a [SensorRequirement]. */
fun interface DeviceCapabilities {
    fun isAvailable(requirement: SensorRequirement): Boolean
}

/** Result of checking every sensor a test needs. */
data class SensorCheckResult(
    val available: List<SensorRequirement>,
    val missing: List<SensorRequirement>,
    /** Optional sensors that are missing: the test can still run, with reduced checks. */
    val missingOptional: List<SensorRequirement> = emptyList()
) {
    val canStart: Boolean get() = missing.isEmpty()

    companion object {
        fun check(definition: AssessmentDefinition, capabilities: DeviceCapabilities): SensorCheckResult {
            val (ok, missing) = definition.sensors.partition { requirement ->
                // A failing probe must not crash the check; treat it as unavailable.
                runCatching { capabilities.isAvailable(requirement) }.getOrDefault(false)
            }
            val (okOptional, missingOptional) = definition.optionalSensors.partition { requirement ->
                runCatching { capabilities.isAvailable(requirement) }.getOrDefault(false)
            }
            return SensorCheckResult(ok + okOptional, missing, missingOptional)
        }
    }
}

class AndroidDeviceCapabilities(context: Context) : DeviceCapabilities {

    private val appContext = context.applicationContext

    override fun isAvailable(requirement: SensorRequirement): Boolean = when (requirement) {
        SensorRequirement.CAMERA ->
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        SensorRequirement.HAND_LANDMARK_MODEL -> assetExists(HAND_MODEL_ASSET)
        SensorRequirement.POSE_LANDMARK_MODEL -> assetExists(POSE_MODEL_ASSET)
        SensorRequirement.ACCELEROMETER -> hasSensor(Sensor.TYPE_ACCELEROMETER)
        SensorRequirement.GYROSCOPE -> hasSensor(Sensor.TYPE_GYROSCOPE)
        SensorRequirement.MICROPHONE ->
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    private fun hasSensor(type: Int): Boolean {
        val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return manager.getDefaultSensor(type) != null
    }

    private fun assetExists(name: String): Boolean = try {
        appContext.assets.open(name).use { it.read() >= 0 }
    } catch (e: Exception) {
        Log.w("DeviceCapabilities", "Asset $name not readable", e)
        false
    }

    companion object {
        const val HAND_MODEL_ASSET = "hand_landmarker.task"
        const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"
    }
}
