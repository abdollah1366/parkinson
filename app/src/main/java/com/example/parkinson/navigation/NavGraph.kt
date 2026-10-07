package com.example.parkinson.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.ui.screens.camera.FingerTappingCameraScreen
import com.example.parkinson.ui.screens.history.AssessmentHistoryScreen
import com.example.parkinson.ui.screens.home.HomeScreen
import com.example.parkinson.ui.screens.intro.FingerTappingIntroScreen
import com.example.parkinson.ui.screens.invalid.InvalidResultKind
import com.example.parkinson.ui.screens.invalid.InvalidResultScreen
import com.example.parkinson.ui.screens.preparation.FingerTappingPreparationScreen
import com.example.parkinson.ui.screens.ready.FingerTappingReadyScreen
import com.example.parkinson.ui.screens.result.FingerTappingResultScreen
import com.example.parkinson.ui.screens.selection.FingerTappingHandSelectionScreen
import com.example.parkinson.ui.screens.splash.SplashScreen
import com.example.parkinson.ui.screens.welcome.WelcomeScreen
import com.example.parkinson.viewmodel.AssessmentHistoryViewModel
import com.example.parkinson.viewmodel.FingerTappingViewModel
import com.example.parkinson.viewmodel.Loadable

@Composable
fun ParkinsonNavGraph(
    navController: NavHostController,
    fingerTappingViewModel: FingerTappingViewModel = viewModel(factory = FingerTappingViewModel.Factory),
    historyViewModel: AssessmentHistoryViewModel = viewModel(factory = AssessmentHistoryViewModel.Factory),
) {
    /** Back to Home, keeping Home itself (creates it if it is not on the back stack). */
    fun goHome() {
        if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
            navController.navigate(Screen.Home.route) { launchSingleTop = true }
        }
    }

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
            val latest by historyViewModel.latest.collectAsState()
            HomeScreen(
                latestAssessment = latest,
                onOpenAssessment = { id -> navController.navigate(Screen.FingerTappingResult.createRoute(id)) },
                onOpenHistory = { navController.navigate(Screen.History.route) },
                onStartAssessmentClicked = { navController.navigate(Screen.FingerTappingIntro.route) }
            )
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
                session = fingerTappingViewModel.session,
                onCompleted = { id ->
                    // Back from the result goes to Home, not into the finished test.
                    navController.navigate(Screen.FingerTappingResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onInvalid = { kind ->
                    navController.navigate(Screen.FingerTappingInvalid.createRoute(kind.name)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.FingerTappingInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // The camera screen is right below on the back stack and starts fresh (IDLE).
                onRetry = {
                    if (!navController.popBackStack(Screen.FingerTappingTest.route, inclusive = false)) {
                        navController.navigate(Screen.FingerTappingTest.route)
                    }
                },
                onHome = { goHome() }
            )
        }

        composable(Screen.FingerTappingResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val assessmentFlow = remember(id) { historyViewModel.assessment(id) }
            val assessment by assessmentFlow.collectAsState(initial = Loadable.Loading)

            FingerTappingResultScreen(
                assessment = assessment,
                onRepeat = { hand: SelectedHand ->
                    fingerTappingViewModel.selectHand(hand)
                    navController.navigate(Screen.FingerTappingReady.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNewAssessment = {
                    fingerTappingViewModel.resetFlow()
                    navController.navigate(Screen.FingerTappingHandSelection.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onHome = {
                    fingerTappingViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.History.route) {
            val history by historyViewModel.all.collectAsState()
            AssessmentHistoryScreen(
                history = history,
                onOpen = { id -> navController.navigate(Screen.FingerTappingResult.createRoute(id)) }
            )
        }
    }
}
