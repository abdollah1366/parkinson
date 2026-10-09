package com.example.parkinson.imu

/** A stored IMU result with the most recent earlier result of the same protocol, for the result screen. */
data class ImuComparison<T>(
    val current: T,
    val previous: T?,
)

/** The most recent earlier sit-to-stand result of the same protocol. Descriptive only; not evidence of a change. */
fun previousImuSitToStand(current: ImuSitToStandResult, all: List<ImuSitToStandResult>): ImuSitToStandResult? =
    all.filter {
        it.assessmentId != current.assessmentId && it.protocolId == current.protocolId &&
            it.timestampEpochMs < current.timestampEpochMs
    }.maxByOrNull { it.timestampEpochMs }

/** The most recent earlier walking result of the same protocol. Descriptive only; not evidence of a change. */
fun previousImuGait(current: ImuGaitResult, all: List<ImuGaitResult>): ImuGaitResult? =
    all.filter {
        it.assessmentId != current.assessmentId && it.protocolId == current.protocolId &&
            it.timestampEpochMs < current.timestampEpochMs
    }.maxByOrNull { it.timestampEpochMs }
