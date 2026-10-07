package com.example.parkinson.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Welcome : Screen("welcome")
    object Home : Screen("home")
    object FingerTappingIntro : Screen("finger_tapping_intro")
    object FingerTappingHandSelection : Screen("finger_tapping_hand_selection")
    object FingerTappingPreparation : Screen("finger_tapping_preparation")
    object FingerTappingReady : Screen("finger_tapping_ready")
    object FingerTappingCameraPlaceholder : Screen("finger_tapping_camera_placeholder")
    object FingerTappingTest : Screen("finger_tapping_test")
    object FingerTappingProcessing : Screen("finger_tapping_processing")
    object FingerTappingResult : Screen("finger_tapping_result")
}
