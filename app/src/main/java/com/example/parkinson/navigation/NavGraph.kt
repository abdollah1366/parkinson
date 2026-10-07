package com.example.parkinson.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.parkinson.ui.screens.camera.FingerTappingCameraScreen
import com.example.parkinson.ui.screens.home.HomeScreen
import com.example.parkinson.ui.screens.intro.FingerTappingIntroScreen
import com.example.parkinson.ui.screens.placeholder.FingerTappingCameraPlaceholderScreen
import com.example.parkinson.ui.screens.preparation.FingerTappingPreparationScreen
import com.example.parkinson.ui.screens.ready.FingerTappingReadyScreen
import com.example.parkinson.ui.screens.selection.FingerTappingHandSelectionScreen
import com.example.parkinson.ui.screens.splash.SplashScreen
import com.example.parkinson.ui.screens.welcome.WelcomeScreen
import com.example.parkinson.viewmodel.FingerTappingViewModel

@Composable
fun ParkinsonNavGraph(
    navController: NavHostController,
    fingerTappingViewModel: FingerTappingViewModel = viewModel(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
    ) {
        composable(Screen.Splash.route) {
            SplashScreen {
                navController.navigate(Screen.Welcome.route) {
                    popUpTo(Screen.Splash.route) { inclusive = true }
                }
            }
        }

        composable(Screen.Welcome.route) {
            WelcomeScreen {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Welcome.route) { inclusive = true }
                }
            }
        }

        composable(Screen.Home.route) {
            HomeScreen {
                navController.navigate(Screen.FingerTappingIntro.route)
            }
        }

        composable(Screen.FingerTappingIntro.route) {
            FingerTappingIntroScreen {
                navController.navigate(Screen.FingerTappingHandSelection.route)
            }
        }

        composable(Screen.FingerTappingHandSelection.route) {
            val selectedHand by fingerTappingViewModel.selectedHand.collectAsState()

            FingerTappingHandSelectionScreen(
                selectedHand = selectedHand,
                onHandSelected = { hand -> fingerTappingViewModel.selectHand(hand) },
                onContinueClicked = {
                    navController.navigate(Screen.FingerTappingPreparation.route)
                }
            )
        }

        composable(Screen.FingerTappingPreparation.route) {
            FingerTappingPreparationScreen {
                navController.navigate(Screen.FingerTappingReady.route)
            }
        }

        composable(Screen.FingerTappingReady.route) {
            val selectedHand by fingerTappingViewModel.selectedHand.collectAsState()

            FingerTappingReadyScreen(
                selectedHand = selectedHand,
                onReadyClicked = {
                    navController.navigate(Screen.FingerTappingTest.route)
                },
                onBackClicked = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.FingerTappingTest.route) {
            val selectedHand by fingerTappingViewModel.selectedHand.collectAsState()

            FingerTappingCameraScreen(
                selectedHand = selectedHand,
                onNextClicked = {
                    navController.navigate(Screen.FingerTappingCameraPlaceholder.route)
                }
            )
        }

        composable(Screen.FingerTappingCameraPlaceholder.route) {
            FingerTappingCameraPlaceholderScreen {
                fingerTappingViewModel.resetFlow()
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = true }
                }
            }
        }
    }
}
