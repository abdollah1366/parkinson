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
import com.example.parkinson.ui.screens.openclose.HandOpenCloseCameraScreen
import com.example.parkinson.ui.screens.openclose.HandOpenCloseIntroScreen
import com.example.parkinson.ui.screens.result.HandOpenCloseResultScreen
import com.example.parkinson.ui.screens.invalid.InvalidResultScreen
import com.example.parkinson.ui.screens.preparation.FingerTappingPreparationScreen
import com.example.parkinson.ui.screens.ready.FingerTappingReadyScreen
import com.example.parkinson.ui.screens.result.FingerTappingResultScreen
import com.example.parkinson.ui.screens.pronation.PronationSupinationIntroScreen
import com.example.parkinson.ui.screens.pronation.PronationSupinationTestScreen
import com.example.parkinson.ui.screens.result.HandStabilityResultScreen
import com.example.parkinson.ui.screens.result.PronationSupinationResultScreen
import com.example.parkinson.ui.screens.result.GaitResultScreen
import com.example.parkinson.ui.screens.result.RestingTremorResultScreen
import com.example.parkinson.ui.screens.gait.GaitIntroScreen
import com.example.parkinson.ui.screens.gait.GaitTestScreen
import com.example.parkinson.viewmodel.GaitViewModel
import com.example.parkinson.viewmodel.SitToStandViewModel
import com.example.parkinson.ui.screens.sts.SitToStandCameraScreen
import com.example.parkinson.ui.screens.sts.SitToStandIntroScreen
import com.example.parkinson.ui.screens.result.SitToStandResultScreen
import com.example.parkinson.ui.screens.selection.FingerTappingHandSelectionScreen
import com.example.parkinson.ui.screens.tremor.RestingTremorCameraScreen
import com.example.parkinson.ui.screens.tremor.RestingTremorIntroScreen
import com.example.parkinson.ui.screens.sensorcheck.SensorCheckScreen
import com.example.parkinson.ui.screens.splash.SplashScreen
import com.example.parkinson.ui.screens.stability.HandStabilityIntroScreen
import com.example.parkinson.ui.screens.stability.HandStabilityTestScreen
import com.example.parkinson.ui.screens.welcome.WelcomeScreen
import com.example.parkinson.viewmodel.AssessmentHistoryViewModel
import com.example.parkinson.viewmodel.FingerTappingViewModel
import com.example.parkinson.viewmodel.HandOpenCloseViewModel
import com.example.parkinson.viewmodel.HandStabilityViewModel
import com.example.parkinson.viewmodel.Loadable
import com.example.parkinson.viewmodel.PronationSupinationViewModel
import com.example.parkinson.viewmodel.RestingTremorViewModel

/** Result screen route of a stored result of any type. */
fun resultRouteFor(result: AssessmentResult): String = when (result.type) {
    AssessmentType.HAND_STABILITY -> Screen.HandStabilityResult.createRoute(result.assessmentId)
    AssessmentType.PRONATION_SUPINATION -> Screen.PronationSupinationResult.createRoute(result.assessmentId)
    AssessmentType.HAND_OPEN_CLOSE -> Screen.HandOpenCloseResult.createRoute(result.assessmentId)
    AssessmentType.RESTING_TREMOR -> Screen.RestingTremorResult.createRoute(result.assessmentId)
    AssessmentType.GAIT -> Screen.GaitResult.createRoute(result.assessmentId)
    AssessmentType.SIT_TO_STAND -> Screen.SitToStandResult.createRoute(result.assessmentId)
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
    handOpenCloseViewModel: HandOpenCloseViewModel = viewModel(factory = HandOpenCloseViewModel.Factory),
    restingTremorViewModel: RestingTremorViewModel = viewModel(factory = RestingTremorViewModel.Factory),
    gaitViewModel: GaitViewModel = viewModel(factory = GaitViewModel.Factory),
    sitToStandViewModel: SitToStandViewModel = viewModel(factory = SitToStandViewModel.Factory),
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

    /** Back to the test selection screen, with Home below it. */
    fun goToTestList() {
        if (!navController.popBackStack(Screen.AssessmentCatalog.route, inclusive = false)) {
            navController.navigate(Screen.AssessmentCatalog.route) {
                popUpTo(Screen.Home.route)
                launchSingleTop = true
            }
        }
    }

    /** Starts the next available test of the catalog (falls back to the test selection screen). */
    fun goToNextTest(current: AssessmentType) {
        fingerTappingViewModel.resetFlow()
        handStabilityViewModel.resetFlow()
        pronationViewModel.resetFlow()
        handOpenCloseViewModel.resetFlow()
        restingTremorViewModel.resetFlow()
        gaitViewModel.resetFlow()
        sitToStandViewModel.resetFlow()
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
            // Instructions + hand selection; the hand is an explicit choice, never inferred from sensors.
            val selectedHand by pronationViewModel.selectedHand.collectAsState()
            PronationSupinationIntroScreen(
                selectedHand = selectedHand,
                onHandSelected = { hand -> pronationViewModel.selectHand(hand) },
                onStartClicked = {
                    // A new run never shows a previous outcome.
                    pronationViewModel.session.reset()
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
                onExit = {
                    pronationViewModel.session.reset()
                    goToTestList()
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
                onBackToTests = {
                    pronationViewModel.resetFlow()
                    goToTestList()
                },
                onHome = {
                    pronationViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Hand Opening/Closing ---------------------------------------------------------

        composable(Screen.HandOpenCloseIntro.route) {
            HandOpenCloseIntroScreen {
                // A new run never shows a previous outcome.
                handOpenCloseViewModel.resetFlow()
                navController.navigate(Screen.HandOpenCloseHandSelection.route)
            }
        }

        composable(Screen.HandOpenCloseHandSelection.route) {
            val selectedHand by handOpenCloseViewModel.selectedHand.collectAsState()
            FingerTappingHandSelectionScreen(
                selectedHand = selectedHand,
                onHandSelected = { hand -> handOpenCloseViewModel.selectHand(hand) },
                onContinueClicked = {
                    navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.HAND_OPEN_CLOSE.id))
                }
            )
        }

        composable(Screen.HandOpenCloseTest.route) {
            val selectedHand by handOpenCloseViewModel.selectedHand.collectAsState()
            HandOpenCloseCameraScreen(
                selectedHand = selectedHand,
                session = handOpenCloseViewModel.session,
                onCompleted = { id ->
                    navController.navigate(Screen.HandOpenCloseResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onInvalid = { kind ->
                    navController.navigate(Screen.HandOpenCloseInvalid.createRoute(kind.name)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.HandOpenCloseInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // The camera screen is right below on the back stack and starts fresh (IDLE).
                onRetry = {
                    if (!navController.popBackStack(Screen.HandOpenCloseTest.route, inclusive = false)) {
                        navController.navigate(Screen.HandOpenCloseTest.route)
                    }
                },
                onHome = {
                    handOpenCloseViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.HandOpenCloseResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.handOpenClose(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            HandOpenCloseResultScreen(
                result = result,
                onRepeat = { hand ->
                    handOpenCloseViewModel.session.reset()
                    handOpenCloseViewModel.selectHand(hand)
                    navController.navigate(Screen.HandOpenCloseTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.HAND_OPEN_CLOSE) },
                onHome = {
                    handOpenCloseViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Resting Hand Tremor ----------------------------------------------------------

        composable(Screen.RestingTremorIntro.route) {
            RestingTremorIntroScreen {
                // A new run never shows a previous outcome.
                restingTremorViewModel.resetFlow()
                navController.navigate(Screen.RestingTremorHandSelection.route)
            }
        }

        composable(Screen.RestingTremorHandSelection.route) {
            val selectedHand by restingTremorViewModel.selectedHand.collectAsState()
            FingerTappingHandSelectionScreen(
                selectedHand = selectedHand,
                onHandSelected = { hand -> restingTremorViewModel.selectHand(hand) },
                onContinueClicked = {
                    navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.RESTING_TREMOR.id))
                }
            )
        }

        composable(Screen.RestingTremorTest.route) {
            val selectedHand by restingTremorViewModel.selectedHand.collectAsState()
            RestingTremorCameraScreen(
                selectedHand = selectedHand,
                session = restingTremorViewModel.session,
                onCompleted = { id ->
                    navController.navigate(Screen.RestingTremorResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onInvalid = { kind ->
                    navController.navigate(Screen.RestingTremorInvalid.createRoute(kind.name)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.RestingTremorInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // The camera screen is right below on the back stack and starts fresh (IDLE).
                onRetry = {
                    if (!navController.popBackStack(Screen.RestingTremorTest.route, inclusive = false)) {
                        navController.navigate(Screen.RestingTremorTest.route)
                    }
                },
                onHome = {
                    restingTremorViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.RestingTremorResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.restingTremor(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            RestingTremorResultScreen(
                result = result,
                onRepeat = { hand ->
                    restingTremorViewModel.session.reset()
                    restingTremorViewModel.selectHand(hand)
                    navController.navigate(Screen.RestingTremorTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.RESTING_TREMOR) },
                onHome = {
                    restingTremorViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Walking (camera pose) ---------------------------------------------------------

        composable(Screen.GaitIntro.route) {
            GaitIntroScreen {
                // A new run never shows a previous outcome.
                gaitViewModel.resetFlow()
                navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.GAIT.id))
            }
        }

        composable(Screen.GaitTest.route) {
            GaitTestScreen(
                session = gaitViewModel.session,
                onCompleted = { id ->
                    navController.navigate(Screen.GaitResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onInvalid = { kind ->
                    navController.navigate(Screen.GaitInvalid.createRoute(kind.name)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.GaitInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // The walking screen is right below on the back stack and starts fresh (IDLE).
                onRetry = {
                    if (!navController.popBackStack(Screen.GaitTest.route, inclusive = false)) {
                        navController.navigate(Screen.GaitTest.route)
                    }
                },
                onHome = {
                    gaitViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.GaitResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.gait(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            GaitResultScreen(
                result = result,
                onRepeat = {
                    gaitViewModel.resetFlow()
                    navController.navigate(Screen.GaitTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.GAIT) },
                onHome = {
                    gaitViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Five Times Sit-to-Stand (camera pose) -----------------------------------------

        composable(Screen.SitToStandIntro.route) {
            SitToStandIntroScreen {
                // A new run never shows a previous calibration or outcome.
                sitToStandViewModel.resetFlow()
                navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.SIT_TO_STAND.id))
            }
        }

        composable(Screen.SitToStandTest.route) {
            SitToStandCameraScreen(
                session = sitToStandViewModel.session,
                onCompleted = { id ->
                    navController.navigate(Screen.SitToStandResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onInvalid = { kind ->
                    navController.navigate(Screen.SitToStandInvalid.createRoute(kind.name)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.SitToStandInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // Retry returns to the camera screen with the calibration cleared: the person sits and calibrates again.
                onRetry = {
                    sitToStandViewModel.resetFlow()
                    if (!navController.popBackStack(Screen.SitToStandTest.route, inclusive = false)) {
                        navController.navigate(Screen.SitToStandTest.route)
                    }
                },
                onHome = {
                    sitToStandViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.SitToStandResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.sitToStand(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            SitToStandResultScreen(
                result = result,
                onRepeat = {
                    sitToStandViewModel.resetFlow()
                    navController.navigate(Screen.SitToStandIntro.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.SIT_TO_STAND) },
                onHome = {
                    sitToStandViewModel.resetFlow()
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
