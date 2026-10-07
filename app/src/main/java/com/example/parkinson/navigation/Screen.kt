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

    companion object {
        const val ARG_ASSESSMENT_ID = "assessmentId"
        const val ARG_INVALID_KIND = "kind"
    }
}
