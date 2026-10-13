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
import com.example.parkinson.ui.screens.history.TrendScreen
import com.example.parkinson.ui.screens.medication.MedicationCheckScreen
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
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.dualtask.DualTaskResult
import com.example.parkinson.ui.screens.dualtask.DualTaskIntroScreen
import com.example.parkinson.ui.screens.dualtask.DualTaskTestScreen
import com.example.parkinson.ui.screens.result.DualTaskResultScreen
import com.example.parkinson.viewmodel.DualTaskViewModel
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.ui.screens.result.RestingTremorImuResultScreen
import com.example.parkinson.ui.screens.result.RestingTremorResultScreen
import com.example.parkinson.tremor.RestingTremorImuResult
import com.example.parkinson.ui.screens.gait.GaitIntroScreen
import com.example.parkinson.ui.screens.imu.ImuGaitScreen
import com.example.parkinson.ui.screens.imu.ImuSitToStandScreen
import com.example.parkinson.ui.screens.result.ImuGaitResultScreen
import com.example.parkinson.ui.screens.result.ImuSitToStandResultScreen
import com.example.parkinson.viewmodel.GaitViewModel
import com.example.parkinson.viewmodel.SitToStandViewModel
import com.example.parkinson.viewmodel.SpeechViewModel
import com.example.parkinson.ui.screens.speech.SpeechIntroScreen
import com.example.parkinson.ui.screens.speech.SpeechTaskSelectionScreen
import com.example.parkinson.ui.screens.speech.SpeechTestScreen
import com.example.parkinson.ui.screens.result.SpeechResultScreen
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
import com.example.parkinson.viewmodel.ProfileViewModel
import com.example.parkinson.viewmodel.ServerLinkViewModel
import com.example.parkinson.viewmodel.ChatViewModel
import com.example.parkinson.ui.screens.chat.ChatScreen
import com.example.parkinson.ui.screens.link.ServerLinkScreen
import com.example.parkinson.ui.screens.patients.PatientsScreen
import com.example.parkinson.ui.screens.profile.RegistrationScreen
import com.example.parkinson.ui.screens.result.CenteredMessage
import com.example.parkinson.R
import androidx.compose.ui.res.stringResource
import com.example.parkinson.norms.toNormPerson
import com.example.parkinson.viewmodel.PronationSupinationViewModel
import com.example.parkinson.viewmodel.RestingTremorViewModel

/** Result screen route of a stored result of any type. */
fun resultRouteFor(result: AssessmentResult): String = when {
    result is ImuSitToStandResult -> Screen.SitToStandResult.createRoute(result.assessmentId)
    result is ImuGaitResult -> Screen.GaitResult.createRoute(result.assessmentId)
    result is SitToStandResult -> Screen.SitToStandLegacyResult.createRoute(result.assessmentId)
    result is GaitResult -> Screen.GaitLegacyResult.createRoute(result.assessmentId)
    result is RestingTremorImuResult -> Screen.RestingTremorImuResult.createRoute(result.assessmentId)
    result is DualTaskResult -> Screen.DualTaskResult.createRoute(result.assessmentId)
    else -> resultRouteByType(result)
}

private fun resultRouteByType(result: AssessmentResult): String = when (result.type) {
    AssessmentType.HAND_STABILITY -> Screen.HandStabilityResult.createRoute(result.assessmentId)
    AssessmentType.PRONATION_SUPINATION -> Screen.PronationSupinationResult.createRoute(result.assessmentId)
    AssessmentType.HAND_OPEN_CLOSE -> Screen.HandOpenCloseResult.createRoute(result.assessmentId)
    AssessmentType.RESTING_TREMOR -> Screen.RestingTremorResult.createRoute(result.assessmentId)
    AssessmentType.SPEECH -> Screen.SpeechResult.createRoute(result.assessmentId)
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
    speechViewModel: SpeechViewModel = viewModel(factory = SpeechViewModel.Factory),
    dualTaskViewModel: DualTaskViewModel = viewModel(factory = DualTaskViewModel.Factory),
    historyViewModel: AssessmentHistoryViewModel = viewModel(factory = AssessmentHistoryViewModel.Factory),
    profileViewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
    serverLinkViewModel: ServerLinkViewModel = viewModel(factory = ServerLinkViewModel.Factory),
    chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory),
) {
    val context = LocalContext.current
    val capabilities: DeviceCapabilities = remember {
        (context.applicationContext as? ParkinsonApplication)?.deviceCapabilities ?: AndroidDeviceCapabilities(context)
    }
    // Published reference values, compared on the result screens with the registered age, sex and diagnosis.
    val normComparator = remember { runCatching { (context.applicationContext as? ParkinsonApplication)?.normComparator }.getOrNull() }
    val registeredProfile by profileViewModel.profile.collectAsState()
    val normPerson = (registeredProfile as? Loadable.Loaded)?.value.toNormPerson()

    /** Back to Home, keeping Home itself (creates it, with nothing below it, if it is not on the back stack). */
    fun goHome() {
        if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
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
        speechViewModel.resetFlow()
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
                // Nobody registered yet: the registration comes first. Several people and none chosen: the patient
                // list. While the profiles are still loading, Home opens and its profile card offers the next step.
                val people = (profileViewModel.profiles.value as? Loadable.Loaded)?.value
                val active = (profileViewModel.profile.value as? Loadable.Loaded)?.value
                val next = when {
                    people == null -> Screen.Home.route
                    people.isEmpty() -> Screen.Registration.route
                    active == null -> Screen.Patients.route
                    else -> Screen.Home.route
                }
                navController.navigate(next) {
                    popUpTo(Screen.Welcome.route) { inclusive = true }
                }
            }
        }

        composable(Screen.Registration.route) {
            val write by profileViewModel.write.collectAsState()
            RegistrationScreen(
                existing = null,
                write = write,
                // The new person becomes the person the tests are for.
                onSave = { form -> profileViewModel.save(form, existing = null) { goHome() } }
            )
        }

        composable(Screen.ProfileEdit.route) {
            val profile by profileViewModel.profile.collectAsState()
            val write by profileViewModel.write.collectAsState()
            when (val p = profile) {
                Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
                is Loadable.Loaded -> RegistrationScreen(
                    existing = p.value,
                    write = write,
                    onSave = { form -> profileViewModel.save(form, existing = p.value) { navController.popBackStack() } },
                    onDelete = p.value?.let { person -> { profileViewModel.delete(person) { goHome() } } },
                )
            }
        }

        composable(Screen.Patients.route) {
            val people by profileViewModel.profiles.collectAsState()
            val active by profileViewModel.profile.collectAsState()
            when (val p = people) {
                Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
                is Loadable.Loaded -> PatientsScreen(
                    profiles = p.value,
                    activeId = (active as? Loadable.Loaded)?.value?.id,
                    onSelect = { person ->
                        profileViewModel.select(person.id)
                        goHome()
                    },
                    onEdit = { person ->
                        profileViewModel.select(person.id)
                        profileViewModel.clearError()
                        navController.navigate(Screen.ProfileEdit.route) { launchSingleTop = true }
                    },
                    onAdd = {
                        profileViewModel.clearError()
                        navController.navigate(Screen.Registration.route) { launchSingleTop = true }
                    },
                )
            }
        }

        composable(Screen.Home.route) {
            val latest by historyViewModel.latest.collectAsState()
            val profile by profileViewModel.profile.collectAsState()
            val people by profileViewModel.profiles.collectAsState()
            val registeredCount = (people as? Loadable.Loaded)?.value?.size ?: 0
            val medication by profileViewModel.medication.collectAsState()
            val doctor by serverLinkViewModel.state.collectAsState()
            val unreadFromDoctor by chatViewModel.unread.collectAsState()
            // Checks for new messages from the doctor while Home is shown (only when this person is connected).
            LaunchedEffect(doctor?.profile?.id, doctor?.link) { chatViewModel.watchUnread() }
            HomeScreen(
                latestAssessment = latest,
                onOpenAssessment = { result -> navController.navigate(resultRouteFor(result)) },
                onOpenHistory = { navController.navigate(Screen.History.route) },
                profile = profile,
                registeredCount = registeredCount,
                onOpenProfile = {
                    profileViewModel.clearError()
                    navController.navigate(Screen.ProfileEdit.route) { launchSingleTop = true }
                },
                onSwitchPatient = { navController.navigate(Screen.Patients.route) { launchSingleTop = true } },
                medication = medication,
                onChangeMedication = {
                    navController.navigate(Screen.MedicationCheckChange.route) { launchSingleTop = true }
                },
                doctorName = doctor?.link?.doctorName,
                pendingToDoctor = doctor?.pending ?: 0,
                onOpenServerLink = {
                    serverLinkViewModel.clearAction()
                    navController.navigate(Screen.ServerLink.route) { launchSingleTop = true }
                },
                unreadFromDoctor = unreadFromDoctor,
                onOpenMessages = { navController.navigate(Screen.Chat.route) { launchSingleTop = true } },
                onStartAssessmentClicked = {
                    // Results must belong to someone: with several people and none chosen, choose first. Someone who
                    // takes medication is asked how it is working, unless they answered in the last two hours.
                    val chosen = (profile as? Loadable.Loaded)?.value != null
                    when {
                        !chosen && registeredCount > 0 ->
                            navController.navigate(Screen.Patients.route) { launchSingleTop = true }
                        profileViewModel.needsMedicationCheck() ->
                            navController.navigate(Screen.MedicationCheckBeforeTests.route) { launchSingleTop = true }
                        else -> navController.navigate(Screen.AssessmentCatalog.route)
                    }
                }
            )
        }

        composable(Screen.Chat.route) {
            val state by chatViewModel.state.collectAsState()
            ChatScreen(chatViewModel, state)
        }

        composable(Screen.ServerLink.route) {
            val state by serverLinkViewModel.state.collectAsState()
            val action by serverLinkViewModel.action.collectAsState()
            when (val s = state) {
                null -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
                else -> ServerLinkScreen(
                    state = s,
                    action = action,
                    lastServerUrl = serverLinkViewModel.lastServerUrl,
                    onLink = serverLinkViewModel::link,
                    onSendNow = serverLinkViewModel::sendNow,
                    onUnlink = serverLinkViewModel::unlink,
                    onSetWebPassword = serverLinkViewModel::setWebPassword,
                )
            }
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
                norms = normComparator,
                person = normPerson,
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
                norms = normComparator,
                person = normPerson,
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
                onCompletedImu = { id ->
                    navController.navigate(Screen.RestingTremorImuResult.createRoute(id)) {
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
                norms = normComparator,
                person = normPerson,
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

        composable(Screen.RestingTremorImuResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.restingTremorImu(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            RestingTremorImuResultScreen(
                norms = normComparator,
                person = normPerson,
                result = result,
                onRepeat = { hand ->
                    restingTremorViewModel.session.reset()
                    restingTremorViewModel.selectHand(hand)
                    navController.navigate(Screen.RestingTremorTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onHome = {
                    restingTremorViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Walking (phone sensors: accelerometer and gyroscope) --------------------------

        composable(Screen.GaitIntro.route) {
            GaitIntroScreen {
                // A new run never shows a previous outcome.
                gaitViewModel.resetFlow()
                navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.GAIT.id))
            }
        }

        composable(Screen.GaitTest.route) {
            ImuGaitScreen(
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
                    gaitViewModel.resetFlow()
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
            val resultFlow = remember(id) { historyViewModel.imuGait(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            ImuGaitResultScreen(
                norms = normComparator,
                person = normPerson,
                result = result,
                onRepeat = {
                    gaitViewModel.resetFlow()
                    navController.navigate(Screen.GaitIntro.route) { popUpTo(Screen.Home.route) }
                },
                onNextTest = { goToNextTest(AssessmentType.GAIT) },
                onHome = {
                    gaitViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // Results stored by the earlier camera-based walking test: read-only, kept so no record is lost.
        composable(Screen.GaitLegacyResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.gait(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            GaitResultScreen(
                result = result,
                onRepeat = {
                    navController.navigate(Screen.GaitIntro.route) { popUpTo(Screen.Home.route) }
                },
                onNextTest = { goToNextTest(AssessmentType.GAIT) },
                onHome = { goHome() },
            )
        }

        // --- Five Times Sit-to-Stand (phone sensors: accelerometer and gyroscope) ----------

        composable(Screen.SitToStandIntro.route) {
            SitToStandIntroScreen {
                // A new run never shows a previous calibration or outcome.
                sitToStandViewModel.resetFlow()
                navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.SIT_TO_STAND.id))
            }
        }

        composable(Screen.SitToStandTest.route) {
            ImuSitToStandScreen(
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
                // Retry returns to the sensor screen with the calibration cleared: the person sits still and calibrates again.
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
            val resultFlow = remember(id) { historyViewModel.imuSitToStand(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            ImuSitToStandResultScreen(
                norms = normComparator,
                person = normPerson,
                result = result,
                onRepeat = {
                    sitToStandViewModel.resetFlow()
                    navController.navigate(Screen.SitToStandIntro.route) { popUpTo(Screen.Home.route) }
                },
                onHome = {
                    sitToStandViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // Results stored by the earlier camera-based sit-to-stand test: read-only.
        composable(Screen.SitToStandLegacyResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.sitToStand(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            SitToStandResultScreen(
                result = result,
                onRepeat = {
                    navController.navigate(Screen.SitToStandIntro.route) { popUpTo(Screen.Home.route) }
                },
                onNextTest = { goToNextTest(AssessmentType.SIT_TO_STAND) },
                onHome = { goHome() },
            )
        }

        // --- Speech (acoustic, on-device) ------------------------------------------------

        composable(Screen.SpeechIntro.route) {
            // Each visit starts without consent or a selected task; consent is given again for every visit.
            LaunchedEffect(Unit) { speechViewModel.resetFlow() }
            val consent by speechViewModel.consent.collectAsState()
            SpeechIntroScreen(
                consent = consent,
                onConsentChanged = { speechViewModel.setConsent(it) },
                onContinueClicked = {
                    navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.SPEECH.id))
                }
            )
        }

        composable(Screen.SpeechTaskSelection.route) {
            val consent by speechViewModel.consent.collectAsState()
            LaunchedEffect(consent) {
                // Without consent no task can be started: return to the privacy notice.
                if (!consent && !navController.popBackStack(Screen.SpeechIntro.route, inclusive = false)) {
                    navController.navigate(Screen.SpeechIntro.route) { launchSingleTop = true }
                }
            }
            SpeechTaskSelectionScreen(onTaskSelected = { task ->
                speechViewModel.selectTask(task)
                navController.navigate(Screen.SpeechTest.route)
            })
        }

        composable(Screen.SpeechTest.route) {
            val task by speechViewModel.task.collectAsState()
            val consent by speechViewModel.consent.collectAsState()
            val input by speechViewModel.input.collectAsState()
            val checking by speechViewModel.inputChecking.collectAsState()
            val inputError by speechViewModel.inputError.collectAsState()
            val selected = task
            LaunchedEffect(selected) {
                if (selected == null) navController.navigate(Screen.SpeechTaskSelection.route) { launchSingleTop = true }
            }
            if (selected != null) {
                SpeechTestScreen(
                    task = selected,
                    consent = consent,
                    session = speechViewModel.session,
                    input = input,
                    inputChecking = checking,
                    inputError = inputError,
                    onCheckInput = { speechViewModel.checkInput() },
                    onCompleted = { id ->
                        navController.navigate(Screen.SpeechResult.createRoute(id)) {
                            popUpTo(Screen.Home.route)
                            launchSingleTop = true
                        }
                    },
                    onInvalid = { kind ->
                        navController.navigate(Screen.SpeechInvalid.createRoute(kind.name)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(Screen.SpeechInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // Retry returns to the task screen; the recording starts again only after a new press of Start.
                onRetry = {
                    speechViewModel.session.reset()
                    if (!navController.popBackStack(Screen.SpeechTest.route, inclusive = false)) {
                        navController.navigate(Screen.SpeechTest.route)
                    }
                },
                onHome = {
                    speechViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.SpeechResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.speech(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            SpeechResultScreen(
                result = result,
                onRepeat = {
                    // Same task, same visit: the consent given for this visit still applies.
                    speechViewModel.session.reset()
                    navController.navigate(Screen.SpeechTest.route) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onNextTest = { goToNextTest(AssessmentType.SPEECH) },
                onHome = {
                    speechViewModel.resetFlow()
                    goHome()
                }
            )
        }

        // --- Motor-cognitive (dual-task): a usual walk, then a walk while subtracting threes ----

        composable(Screen.DualTaskIntro.route) {
            DualTaskIntroScreen { useMicrophone ->
                // A new run never shows a previous outcome.
                dualTaskViewModel.resetFlow()
                dualTaskViewModel.controller.useMicrophone = useMicrophone
                navController.navigate(Screen.SensorCheck.createRoute(AssessmentType.DUAL_TASK.id))
            }
        }

        composable(Screen.DualTaskTest.route) {
            DualTaskTestScreen(
                controller = dualTaskViewModel.controller,
                onCompleted = { id ->
                    navController.navigate(Screen.DualTaskResult.createRoute(id)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onInvalid = { kind ->
                    navController.navigate(Screen.DualTaskInvalid.createRoute(kind.name)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.DualTaskInvalid.route) { entry ->
            val kind = InvalidResultKind.fromName(entry.arguments?.getString(Screen.ARG_INVALID_KIND))
            InvalidResultScreen(
                kind = kind,
                // The whole test starts again from the first walk.
                onRetry = {
                    dualTaskViewModel.resetFlow()
                    if (!navController.popBackStack(Screen.DualTaskTest.route, inclusive = false)) {
                        navController.navigate(Screen.DualTaskTest.route)
                    }
                },
                onHome = {
                    dualTaskViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.DualTaskResult.route) { entry ->
            val id = entry.arguments?.getString(Screen.ARG_ASSESSMENT_ID).orEmpty()
            val resultFlow = remember(id) { historyViewModel.dualTask(id) }
            val result by resultFlow.collectAsState(initial = Loadable.Loading)
            DualTaskResultScreen(
                result = result,
                onRepeat = {
                    dualTaskViewModel.resetFlow()
                    navController.navigate(Screen.DualTaskIntro.route) { popUpTo(Screen.Home.route) }
                },
                onNextTest = { goToNextTest(AssessmentType.DUAL_TASK) },
                onHome = {
                    dualTaskViewModel.resetFlow()
                    goHome()
                }
            )
        }

        composable(Screen.History.route) {
            val history by historyViewModel.all.collectAsState()
            AssessmentHistoryScreen(
                history = history,
                onOpen = { result -> navController.navigate(resultRouteFor(result)) },
                onOpenTrends = { navController.navigate(Screen.Trends.route) { launchSingleTop = true } },
            )
        }

        composable(Screen.Trends.route) {
            val stored by historyViewModel.stored.collectAsState()
            TrendScreen(history = stored)
        }

        composable(Screen.MedicationCheckBeforeTests.route) {
            MedicationCheckScreen { state ->
                profileViewModel.recordMedication(state)
                navController.navigate(Screen.AssessmentCatalog.route) {
                    popUpTo(Screen.MedicationCheckBeforeTests.route) { inclusive = true }
                }
            }
        }

        composable(Screen.MedicationCheckChange.route) {
            MedicationCheckScreen { state ->
                profileViewModel.recordMedication(state)
                navController.popBackStack()
            }
        }
    }
}
