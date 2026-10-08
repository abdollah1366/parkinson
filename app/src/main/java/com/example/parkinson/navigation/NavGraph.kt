package com.example.parkinson.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.assessment.AndroidDeviceCapabilities
import com.example.parkinson.assessment.AssessmentCatalog
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.DeviceCapabilities
import com.example.parkinson.assessment.SensorCheckResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.ui.screens.camera.FingerTappingCameraScreen
import com.example.parkinson.ui.screens.catalog.AssessmentCatalogScreen
import com.example.parkinson.ui.screens.history.AssessmentHistoryScreen
import com.example.parkinson.ui.screens.home.HomeScreen
import com.example.parkinson.ui.screens.intro.FingerTappingIntroScreen
import com.example.parkinson.ui.screens.invalid.InvalidResultKind
import com.example.parkinson.ui.screens.invalid.InvalidResultScreen
import com.example.parkinson.ui.screens.preparation.FingerTappingPreparationScreen
import com.example.parkinson.ui.screens.ready.FingerTappingReadyScreen
import com.example.parkinson.ui.screens.result.FingerTappingResultScreen
import com.example.parkinson.ui.screens.pronation.PronationSupinationIntroScreen
import com.example.parkinson.ui.screens.pronation.PronationSupinationTestScreen
import com.example.parkinson.ui.screens.result.HandStabilityResultScreen
import com.example.parkinson.ui.screens.result.PronationSupinationResultScreen
import com.example.parkinson.ui.screens.selection.FingerTappingHandSelectionScreen
import com.example.parkinson.ui.screens.sensorcheck.SensorCheckScreen
import com.example.parkinson.ui.screens.splash.SplashScreen
import com.example.parkinson.ui.screens.stability.HandStabilityIntroScreen
import com.example.parkinson.ui.screens.stability.HandStabilityTestScreen
import com.example.parkinson.ui.screens.welcome.WelcomeScreen
import com.example.parkinson.viewmodel.AssessmentHistoryViewModel
import com.example.parkinson.viewmodel.FingerTappingViewModel
import com.example.parkinson.viewmodel.HandStabilityViewModel
import com.example.parkinson.viewmodel.Loadable
import com.example.parkinson.viewmodel.PronationSupinationViewModel

/** Result screen route of a stored result of any type. */
fun resultRouteFor(result: AssessmentResult): String = when (result.type) {
    AssessmentType.HAND_STABILITY -> Screen.HandStabilityResult.createRoute(result.assessmentId)
    AssessmentType.PRONATION_SUPINATION -> Screen.PronationSupinationResult.createRoute(result.assessmentId)
    else -> Screen.FingerTappingResult.createRoute(result.assessmentId)
}

/**
 * Flow: Home -> Test selection (catalog) -> Instructions -> Hand selection -> Sensor check ->
 * [FT: Preparation -> Ready] -> Countdown/Recording/Processing/Quality -> Result -> History.
 * Results offer Retry, Next test and Home.
 */
@Composable
fun ParkinsonNavGraph(
    navController: NavHostController,
    fingerTappingViewModel: FingerTappingViewModel = viewModel(factory = FingerTappingViewModel.Factory),
    handStabilityViewModel: HandStabilityViewModel = viewModel(factory = HandStabilityViewModel.Factory),
    pronationViewModel: PronationSupinationViewModel = viewModel(factory = PronationSupinationViewModel.Factory),
    historyViewModel: AssessmentHistoryViewModel = viewModel(factory = AssessmentHistoryViewModel.Factory),
) {
    val context = LocalContext.current
    val capabilities: DeviceCapabilities = remember {
        (context.applicationContext as? ParkinsonApplication)?.deviceCapabilities ?: AndroidDeviceCapabilities(context)
    }

    /** Back to Home, keeping Home itself (creates it if it is not on the back stack). */
    fun goHome() {
        if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
            navController.navigate(Screen.Home.route) { launchSingleTop = true }
        }
    }

    /** Starts the next available test of the catalog (falls back to the test selection screen). */
    fun goToNextTest(current: AssessmentType) {
        fingerTappingViewModel.resetFlow()
        handStabilityViewModel.resetFlow()
        pronationViewModel.resetFlow()
        val route = AssessmentCatalog.nextAvailableAfter(current)?.startRoute ?: Screen.AssessmentCatalog.route
        navController.navigate(route) {
            popUpTo(Screen.Home.route)
            launchSingleTop = true
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
                onOpenAssessment = { result -> navController.navigate(resultRouteFor(result)) },
                onOpenHistory = { navController.navigate(Screen.History.route) },
                onStartAssessmentClicked = { navController.navigate(Screen.AssessmentCatalog.route) }
            )
        }

        composable(Screen.AssessmentCatalog.route) {
            AssessmentCatalogScreen(
                onTestSelected = { test ->
                    // Defensive: unavailable tests have no route and their cards are not clickable.
                    val route = test.startRoute
                    if (test.isAvailable && route != null) navController.navigate(route)
                }
            )
        }

        composable(Screen.SensorCheck.route) { entry ->
            val definition = AssessmentCatalog.byId(entry.arguments?.getString(Screen.ARG_ASSESSMENT_TYPE))
            if (definition == null) {
                LaunchedEffect(Unit) { goHome() }
                return@composable
            }
            val result = remember(definition) { SensorCheckResult.check(definition, capabilities) }
            SensorCheckScreen(
                definition = definition,
                result = result,
                onContinue = { definition.afterSensorCheckRoute?.let { navController.navigate(it) } },
                onBack = { navController.popBackStack() }
            )
        }

        // --- Finger Tapping ---------------------------------------------------------------

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
                    navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.FINGER_TAPPING.id))
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
                },
                onNextTest = { goToNextTest(AssessmentType.FINGER_TAPPING) }
            )
        }

        // --- Hand Stability ---------------------------------------------------------------

        composable(Screen.HandStabilityIntro.route) {
            HandStabilityIntroScreen {
                // A new run never shows a previous outcome.
                handStabilityViewModel.resetFlow()
                navController.navigate(Screen.HandStabilityHandSelection.route)
            }
        }

        composable(Screen.HandStabilityHandSelection.route) {
            val selectedHand by handStabilityViewModel.selectedHand.collectAsState()
            FingerTappingHandSelectionScreen(
                selectedHand = selectedHand,
                onHandSelected = { hand -> handStabilityViewModel.selectHand(hand) },
                onContinueClicked = {
                    navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.HAND_STABILITY.id))
                }
            )
        }

        composable(Screen.HandStabilityTest.route) {
            val selectedHand by handStabilityViewModel.selectedHand.collectAsState()
            HandStabilityTestScreen(
                selectedHand = selectedHand,
                session = handStabilityViewModel.session,
                onCompleted = { id ->
                    navController.navigate(Screen.HandStabilityResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onHome = {
                    handStabilityViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.HandStabilityResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.handStability(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            HandStabilityResultScreen(
                result = result,
                onRepeat = { hand ->
                    handStabilityViewModel.session.reset()
                    handStabilityViewModel.selectHand(hand)
                    navController.navigate(Screen.HandStabilityTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.HAND_STABILITY) },
                onHome = {
                    handStabilityViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Pronation / Supination ------------------------------------------------------

        composable(Screen.PronationSupinationIntro.route) {
            PronationSupinationIntroScreen {
                // A new run never shows a previous outcome.
                pronationViewModel.resetFlow()
                navController.navigate(Screen.PronationSupinationHandSelection.route)
            }
        }

        composable(Screen.PronationSupinationHandSelection.route) {
            // The hand comes from this explicit selection, never from the sensor data.
            val selectedHand by pronationViewModel.selectedHand.collectAsState()
            FingerTappingHandSelectionScreen(
                selectedHand = selectedHand,
                onHandSelected = { hand -> pronationViewModel.selectHand(hand) },
                onContinueClicked = {
                    navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.PRONATION_SUPINATION.id))
                }
            )
        }

        composable(Screen.PronationSupinationTest.route) {
            val selectedHand by pronationViewModel.selectedHand.collectAsState()
            PronationSupinationTestScreen(
                selectedHand = selectedHand,
                session = pronationViewModel.session,
                onCompleted = { id ->
                    navController.navigate(Screen.PronationSupinationResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onHome = {
                    pronationViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.PronationSupinationResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.pronationSupination(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            PronationSupinationResultScreen(
                result = result,
                onRepeat = { hand ->
                    pronationViewModel.session.reset()
                    pronationViewModel.selectHand(hand)
                    navController.navigate(Screen.PronationSupinationTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.PRONATION_SUPINATION) },
                onHome = {
                    pronationViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.History.route) {
            val history by historyViewModel.all.collectAsState()
            AssessmentHistoryScreen(
                history = history,
                onOpen = { result -> navController.navigate(resultRouteFor(result)) }
            )
        }
    }
}
