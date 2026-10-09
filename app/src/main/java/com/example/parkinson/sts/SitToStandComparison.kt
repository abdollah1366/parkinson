package com.example.parkinson.sts

/** A stored result with the most recent earlier result of the same protocol, for the result screen. */
data class SitToStandComparison(
    val current: SitToStandResult,
    val previous: SitToStandResult?,
)

/**
 * The most recent result strictly earlier than [current] of the same protocol (the attempt itself is excluded).
 * Null when there is no earlier attempt. Differences between sessions are descriptive only.
 */
fun previousSitToStand(current: SitToStandResult, all: List<SitToStandResult>): SitToStandResult? =
    all.filter {
        it.assessmentId != current.assessmentId &&
            it.protocolId == current.protocolId &&
            it.timestampEpochMs < current.timestampEpochMs
    }.maxByOrNull { it.timestampEpochMs }
