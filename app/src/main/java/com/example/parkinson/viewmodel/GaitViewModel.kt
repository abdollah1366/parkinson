package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.imu.ImuGaitSession
import com.example.parkinson.sensors.MotionSensorRepository
import com.example.parkinson.sensors.MotionSensorSource

/** Holds the walking session (sensor-based) so a running trial survives rotation. */
class GaitViewModel(
    repository: AssessmentRepository,
    source: MotionSensorSource,
) : ViewModel() {

    val session = ImuGaitSession(
        scope = viewModelScope,
        repository = MotionSensorRepository(source),
        onCompleted = repository::saveImuGait,
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
                GaitViewModel(app.assessmentRepository, app.motionSensorSource)
            }
        }
    }
}
