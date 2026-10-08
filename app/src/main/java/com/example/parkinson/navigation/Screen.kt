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

    companion object {
        const val ARG_ASSESSMENT_ID = "assessmentId"
        const val ARG_INVALID_KIND = "kind"
        const val ARG_ASSESSMENT_TYPE = "type"
    }
}
