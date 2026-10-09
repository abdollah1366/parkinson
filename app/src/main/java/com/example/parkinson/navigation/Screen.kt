package com.example.parkinson.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Welcome : Screen("welcome")
    object Home : Screen("home")
    object FingerTappingIntro : Screen("finger_tapping_intro")
    object FingerTappingHandSelection : Screen("finger_tapping_hand_selection")
    object FingerTappingPreparation : Screen("finger_tapping_preparation")
    object FingerTappingReady : Screen("finger_tapping_ready")
    object FingerTappingTest : Screen("finger_tapping_test")

    object FingerTappingResult : Screen("finger_tapping_result/{$ARG_ASSESSMENT_ID}") {
        fun createRoute(assessmentId: String) = "finger_tapping_result/${Uri.encode(assessmentId)}"
    }

    object FingerTappingInvalid : Screen("finger_tapping_invalid/{$ARG_INVALID_KIND}") {
        fun createRoute(kind: String) = "finger_tapping_invalid/${Uri.encode(kind)}"
    }

    object History : Screen("history")

    /** Test selection: every test in AssessmentCatalog, available or not. */
    object AssessmentCatalog : Screen("assessment_catalog")

    /** Checks the sensors a test needs, then continues to the test's next step. */
    object SensorCheck : Screen("sensor_check/{$ARG_ASSESSMENT_TYPE}") {
        fun createRoute(typeId: String) = "sensor_check/${Uri.encode(typeId)}"
    }

    object HandStabilityIntro : Screen("hand_stability_intro")
    object HandStabilityHandSelection : Screen("hand_stability_hand_selection")
    object HandStabilityTest : Screen("hand_stability_test")

    object HandStabilityResult : Screen("hand_stability_result/{$ARG_ASSESSMENT_ID}") {
        fun createRoute(assessmentId: String) = "hand_stability_result/${Uri.encode(assessmentId)}"
    }

    object PronationSupinationIntro : Screen("pronation_supination_intro")
    object PronationSupinationTest : Screen("pronation_supination_test")

    object PronationSupinationResult : Screen("pronation_supination_result/{$ARG_ASSESSMENT_ID}") {
        fun createRoute(assessmentId: String) = "pronation_supination_result/${Uri.encode(assessmentId)}"
    }

    object HandOpenCloseIntro : Screen("hand_open_close_intro")
    object HandOpenCloseHandSelection : Screen("hand_open_close_hand_selection")
    object HandOpenCloseTest : Screen("hand_open_close_test")

    object HandOpenCloseInvalid : Screen("hand_open_close_invalid/{$ARG_INVALID_KIND}") {
        fun createRoute(kind: String) = "hand_open_close_invalid/${Uri.encode(kind)}"
    }

    object HandOpenCloseResult : Screen("hand_open_close_result/{$ARG_ASSESSMENT_ID}") {
        fun createRoute(assessmentId: String) = "hand_open_close_result/${Uri.encode(assessmentId)}"
    }

    object RestingTremorIntro : Screen("resting_tremor_intro")
    object RestingTremorHandSelection : Screen("resting_tremor_hand_selection")
    object RestingTremorTest : Screen("resting_tremor_test")

    object RestingTremorInvalid : Screen("resting_tremor_invalid/{$ARG_INVALID_KIND}") {
        fun createRoute(kind: String) = "resting_tremor_invalid/${Uri.encode(kind)}"
    }

    object RestingTremorResult : Screen("resting_tremor_result/{$ARG_ASSESSMENT_ID}") {
        fun createRoute(assessmentId: String) = "resting_tremor_result/${Uri.encode(assessmentId)}"
    }

    companion object {
        const val ARG_ASSESSMENT_ID = "assessmentId"
        const val ARG_INVALID_KIND = "kind"
        const val ARG_ASSESSMENT_TYPE = "type"
    }
}
