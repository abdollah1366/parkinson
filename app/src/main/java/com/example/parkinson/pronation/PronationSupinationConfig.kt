package com.example.parkinson.pronation


/**
 * Versions stored with every result. Bump [ALGORITHM_VERSION] for any change to preprocessing,
 * detection, metrics, trend or quality rules; bump [SCORING_VERSION] for any change to the
 * score, its normalization, weights or interpretation bands.
 * See docs/pronation-supination-algorithm.md.
 *
 * Pre-release development builds stored "ps-algo-1.0.0" / "ps-score-0.1.0-research".
 */
object PronationSupinationVersions {
    const val ALGORITHM_VERSION = "1.0"
    const val SCORING_VERSION = "1.0"
}

/** Session timing (milliseconds unless noted). */
data class PronationSessionConfig(
    val preparationMs: Long = 5_000L,
    val countdownSeconds: Int = 3,
    val recordingMs: Long = 10_000L,
    /** How long "شروع کنید" stays visible after recording starts. */
    val startCueMs: Long = 1_000L,
    val tickMs: Long = 100L,
    /** No gyroscope event for this long = the sensor stopped. */
    val sensorStarvationMs: Long = 1_500L,
    /** 100 Hz requested; the real rate is always measured. */
    val samplingPeriodUs: Int = 10_000,
    /** Preflight at the end of preparation: minimum gyroscope rate and valid timestamp share. */
    val preflightMinGyroRateHz: Double = 25.0,
    val preflightMinMonotonicPercent: Double = 90.0,
    /** Pre-sized sample buffers: (preparation + recording) x 200 Hz x 2 sensors, with margin. */
    val bufferCapacity: Int = 8_192
)

/**
 * Signal-processing and cycle-detection parameters. ENGINEERING values chosen for technical
 * robustness on synthetic data; not clinically validated (see the algorithm doc for rationale).
 */
data class PronationDetectionConfig(
    /** Uniform analysis grid: min(this, measured gyroscope rate). */
    val maxGridHz: Double = 100.0,
    /** Zero-phase 2nd-order Butterworth low-pass cut-off ... */
    val lowPassHz: Double = 10.0,
    /** ... capped at this fraction of the grid rate (safely below Nyquist). */
    val maxLowPassFractionOfRate: Double = 0.4,
    /** Minimum rotation between turning points that counts as a movement (degrees). */
    val minAmplitudeDeg: Double = 10.0,
    /** Hysteresis = max(minAmplitudeDeg, relativeHysteresis x median amplitude, noise term). */
    val relativeHysteresis: Double = 0.3,
    /** Noise term = noiseHysteresisFactor x baseline noise (deg/s) x noiseIntegrationS. */
    val noiseHysteresisFactor: Double = 3.0,
    val noiseIntegrationS: Double = 0.25,
    /** Faster reversals are implausible for forearm rotation: debounced (minimum peak distance). */
    val maxCycleFrequencyHz: Double = 6.0,
    /** A full cycle longer than this is not a valid repetitive movement (likely a pause). */
    val maxCycleDurationS: Double = 5.0,
    /** A movement is excluded when a sensor dropout covers more than this share of it. */
    val maxGapShareOfMovement: Double = 0.25,
    /** Pauses: |w| below max(floor, fraction x median peak) for >= max(minPauseMs, fraction x cycle). */
    val pauseVelocityFraction: Double = 0.15,
    val pauseVelocityFloorDegS: Double = 5.0,
    val minPauseMs: Double = 400.0,
    val pauseCycleFraction: Double = 0.3,
    /** Movement "within the person's typical movement" tolerance (amplitude and duration). */
    val consistencyTolerance: Double = 0.30,
    /** Baseline: last part of the preparation; still = gyro RMS and |mean| below these. */
    val baselineWindowMs: Long = 2_000L,
    val stillGyroRmsDegS: Double = 10.0,
    val maxBiasDegS: Double = 5.0,
    /** Gravity tracking (complementary filter) time constant. */
    val gravityTimeConstantS: Double = 1.0,
    /** Dominant frequency search (reported only when one frequency clearly dominates). */
    val minFrequencyHz: Double = 0.2,
    val maxFrequencyHz: Double = 6.0,
    val frequencyStepHz: Double = 0.05,
    val peakHalfWidthHz: Double = 0.25,
    val minPeakConcentration: Double = 0.4,
    /** Main axis within this cosine of the phone's long (y) axis = forearm axis under the standard grip. */
    val forearmAxisMinCosine: Double = 0.7,
    /** Stored angular-velocity trace for the result chart. */
    val traceHz: Double = 20.0,
    /** Per-measure early-vs-late change below this (percent) counts as "about the same". */
    val trendStableBandPercent: Double = 15.0,
    /** A third of the recording needs this many valid movements for segment metrics. */
    val minMovementsPerSegment: Int = 3
)

/**
 * ENGINEERING quality thresholds (technical reliability of the recording, never the person).
 * Not clinically validated.
 */
data class PronationQualityThresholds(
    /** MIN_VALID_SAMPLES: 10 Hz over 10 s, per stream. */
    val minValidSamples: Int = 100,
    /** Below this the turning points of up to 6 Hz movement cannot be resolved. */
    val minSamplingRateHz: Double = 25.0,
    val goodSamplingRateHz: Double = 50.0,
    val minCompletenessPercent: Double = 90.0,
    /** MIN_VALID_SAMPLE_PERCENTAGE */
    val minValidSamplePercent: Double = 90.0,
    val goodValidSamplePercent: Double = 99.0,
    /** MAX_ALLOWED_GAP and the share of time lost in gaps. */
    val maxAllowedGapMs: Double = 1_000.0,
    val maxGapShare: Double = 0.20,
    val goodLongestGapMs: Double = 250.0,
    val goodGapShare: Double = 0.02,
    val maxUnreliablePercent: Double = 10.0,
    val maxStreamOffsetMs: Double = 500.0,
    /** MIN_USABLE_DURATION: synchronized window minus gap time. */
    val minUsableDurationMs: Double = 7_000.0,
    /** MIN_VALID_CYCLES */
    val minValidCycles: Int = 2,
    val minValidCyclePercent: Double = 70.0,
    /** Share of the window between the first and last turning point. */
    val minMovementCoveragePercent: Double = 50.0,
    /** MAX_NOISE_LEVEL: high-frequency residual of the rotation signal (deg/s RMS). */
    val maxNoiseLevelDegS: Double = 40.0,
    val minAxisSharePercent: Double = 60.0,
    val minGravity: Double = 6.0,
    val maxGravity: Double = 16.0,
    /** Data-quality percentage: weights of the technical parts (sum 100) ... */
    val qualityWeightRate: Double = 30.0,
    val qualityWeightCompleteness: Double = 30.0,
    val qualityWeightValidSamples: Double = 20.0,
    val qualityWeightGaps: Double = 20.0,
    /** ... and the technical share when combined with the valid-cycle share. */
    val qualityTechnicalShare: Double = 0.8
)

/**
 * SCORING weights and normalization. ENGINEERING weights and an INTERNAL SOFTWARE REFERENCE:
 * no validated normative dataset exists, so none of these is a clinical "normal" value.
 */
data class PronationScoringConfig(
    val weightSpeed: Double = 0.25,
    val weightRhythm: Double = 0.25,
    val weightAmplitude: Double = 0.20,
    val weightConsistency: Double = 0.15,
    val weightTrend: Double = 0.15,
    /** Rhythm: cycle-duration CV mapped to 0 (0 % CV = 100). */
    val maxCycleCvPercent: Double = 40.0,
    /** Amplitude / velocity stability: CV mapped to 0. */
    val maxAmplitudeCvPercent: Double = 50.0,
    val maxVelocityCvPercent: Double = 50.0,
    /** Share of the amplitude score that depends on amplitude stability. */
    val amplitudeStabilityShare: Double = 0.3,
    /** Trend: a segment-score decline up to this is tolerated (100); [maxTrendDecline] = 0. */
    val trendToleranceDecline: Double = 5.0,
    val maxTrendDecline: Double = 40.0,
    /** Segment-score change (points) that counts as IMPROVING / DECLINING. */
    val trendChangePoints: Int = 10,
    /** A component below this is mentioned in the interpretation as lower. */
    val lowComponentScore: Int = 60
)
