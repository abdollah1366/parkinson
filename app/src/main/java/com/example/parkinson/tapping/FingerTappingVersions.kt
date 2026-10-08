package com.example.parkinson.tapping

/**
 * Versions stored with every assessment so results stay reproducible and comparable.
 *
 * Bump [ALGORITHM_VERSION] whenever signal processing, event detection, metric formulas or
 * quality rules change. Bump [SCORING_VERSION] whenever the performance score changes.
 * See docs/finger-tapping-algorithm.md.
 *
 * History: ft-algo-1.0.0 (legacy handedness flip), 1.0.1 (handedness fixed), 1.0.2 (landmark
 * rotation fixed), 1.1.0 (layered quality: handedness no longer discards landmarks, time-based
 * tracking, adaptive frame-rate requirement; one tap pipeline). Scoring: ft-score-0.1.0-preliminary
 * (VALID only), 1.0 (Motor Performance Index, VALID + LOW_QUALITY with limited reliability).
 */
object FingerTappingVersions {
    const val ALGORITHM_VERSION = "ft-algo-1.1.0"

    /** Engineering weights and an internal software reference; NOT clinically validated. */
    const val SCORING_VERSION = "1.0"
}
