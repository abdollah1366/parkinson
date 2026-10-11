package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.imu.ImuSitToStandSession
import com.example.parkinson.sensors.MotionSensorRepository
import com.example.parkinson.sensors.MotionSensorSource

/** Holds the sit-to-stand session (sensor-based) so a running attempt survives rotation. */
class SitToStandViewModel(
    repository: AssessmentRepository,
    source: MotionSensorSource,
) : ViewModel() {

    val session = ImuSitToStandSession(
        scope = viewModelScope,
        repository = MotionSensorRepository(source),
        onCompleted = repository::saveImuSitToStand,
    )

    fun resetFlow() {
        session.reset()
    }

    override fun onCleared() {
        session.reset()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                SitToStandViewModel(app.assessmentRepository, app.motionSensorSource)
            }
        }
    }
}
