package com.example.parkinson.tapping

/**
 * Versions stored with every assessment so results stay reproducible and comparable.
 *
 * Bump [ALGORITHM_VERSION] whenever signal processing, event detection, metric formulas or
 * quality rules change. Bump [SCORING_VERSION] whenever the performance score changes.
 * See docs/finger-tapping-algorithm.md.
 */
object FingerTappingVersions {
    const val ALGORITHM_VERSION = "ft-algo-1.0.2"

    /** "preliminary": engineering reference values, NOT clinically validated. */
    const val SCORING_VERSION = "ft-score-0.1.0-preliminary"
}
