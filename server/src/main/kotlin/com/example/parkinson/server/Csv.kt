package com.example.parkinson.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Results as CSV for Excel or statistics software: one row per test, the measurements as JSON in the last column. */
object Csv {
    private val HEADER = listOf(
        "assessment_id", "type", "taken_at_utc", "hand", "quality_status", "quality_score", "performance_index",
        "medication_state", "algorithm_version", "scoring_version", "metrics_json",
    )

    fun results(results: List<ResultDto>): String = buildString {
        append('﻿') // Lets Excel read the file as UTF-8.
        appendRow(HEADER)
        for (r in results) {
            appendRow(
                listOf(
                    r.assessmentId, r.type, Instant.ofEpochMilli(r.timestampEpochMs).toString(), r.hand.orEmpty(),
                    r.qualityStatus, r.qualityScore?.toString().orEmpty(), r.performanceIndex?.toString().orEmpty(),
                    r.medicationState.orEmpty(), r.algorithmVersion, r.scoringVersion,
                    Json.encodeToString(JsonObject.serializer(), r.metrics),
                ),
            )
        }
    }

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.joinTo(this, ",") { cell ->
            // Quote everything that needs it; a leading =, +, - or @ is prefixed so Excel never runs it as a formula.
            val safe = if (cell.firstOrNull() in setOf('=', '+', '-', '@') && cell.toDoubleOrNull() == null) "'$cell" else cell
            if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
        append("\r\n")
    }
}
