package com.example.parkinson.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.example.parkinson.assessment.AssessmentCatalog
import com.example.parkinson.assessment.AssessmentDefinition
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.SensorCheckResult
import com.example.parkinson.assessment.SensorRequirement
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.stability.HandStabilityEngine
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.stability.StabilityError
import com.example.parkinson.stability.StabilityInvalidReason
import com.example.parkinson.stability.StabilityQualityIssue
import com.example.parkinson.stability.StabilityQualityReport
import com.example.parkinson.stability.StabilityState
import com.example.parkinson.stability.SyntheticMotion
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.ui.screens.catalog.AssessmentCatalogScreen
import com.example.parkinson.ui.screens.history.AssessmentHistoryScreen
import com.example.parkinson.ui.screens.home.HomeScreen
import com.example.parkinson.ui.screens.result.HandStabilityResultScreen
import com.example.parkinson.ui.screens.sensorcheck.SensorCheckScreen
import com.example.parkinson.ui.screens.stability.stabilityProblem
import com.example.parkinson.ui.theme.ParkinsonTheme
import com.example.parkinson.viewmodel.Loadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StabilityAndCatalogUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) = rule.setContent { ParkinsonTheme(darkTheme = false) { content() } }

    private fun result(id: String, motion: SyntheticMotion, time: Long = 1_760_000_000_000L) =
        HandStabilityResult.from(HandStabilityEngine().analyze(motion.recording()), id, time, SelectedHand.LEFT, 15_000L)

    private val valid = result("hs-valid", SyntheticMotion().oscillation(5.0, 8.0, 0.2))
    private val lowQuality = result("hs-low", SyntheticMotion().gap(5_000, 5_400))

    private val titles = listOf(
        "ضربه زدن با انگشتان", "نگه‌داشتن دست ثابت", "آزمون چرخش دست", "آزمون باز و بسته کردن دست",
        "لرزش دست در حالت استراحت", "راه رفتن", "بلند شدن از صندلی",
        "ارزیابی گفتار", "آزمون حرکتی-شناختی"
    )

    @Test
    fun catalogShowsEveryTestWithItsStatus() {
        setContent { AssessmentCatalogScreen(onTestSelected = {}) }
        rule.onNodeWithText("۷ آزمون آماده انجام • ۲ آزمون در حال توسعه").assertIsDisplayed()
        titles.forEach { title ->
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            rule.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun onlyAvailableCardsAreEnabledAndStart() {
        val selected = mutableListOf<AssessmentDefinition>()
        setContent { AssessmentCatalogScreen(onTestSelected = { selected += it }) }

        rule.onNodeWithText("ضربه زدن با انگشتان").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("نگه‌داشتن دست ثابت"))
        rule.onNodeWithText("نگه‌داشتن دست ثابت").assertIsEnabled().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("آزمون چرخش دست"))
        rule.onNodeWithText("آزمون چرخش دست").assertIsEnabled().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("آزمون باز و بسته کردن دست"))
        rule.onNodeWithText("آزمون باز و بسته کردن دست").assertIsEnabled().performClick()

        listOf("ارزیابی گفتار").forEach { title ->
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            rule.onNodeWithText(title).assertIsNotEnabled().performClick()
        }
        assertEquals(
            listOf(
                AssessmentType.FINGER_TAPPING,
                AssessmentType.HAND_STABILITY,
                AssessmentType.PRONATION_SUPINATION,
                AssessmentType.HAND_OPEN_CLOSE
            ),
            selected.map { it.type }
        )
    }

    @Test
    fun unavailableCardsShowDevelopmentStatusNotMeasurements() {
        setContent { AssessmentCatalogScreen(tests = listOf(AssessmentCatalog[AssessmentType.SPEECH]), onTestSelected = {}) }
        rule.onNodeWithText("◌ در حال توسعه").assertIsDisplayed()
        rule.onNodeWithText("مدت: پس از تعیین پروتکل").assertIsDisplayed()
        rule.onNodeWithText("● آماده انجام").assertDoesNotExist()
    }

    @Test
    fun restingTremorIsShownAsAvailable() {
        setContent { AssessmentCatalogScreen(tests = listOf(AssessmentCatalog[AssessmentType.RESTING_TREMOR]), onTestSelected = {}) }
        rule.onNodeWithText("● آماده انجام").assertIsDisplayed()
    }

    @Test
    fun sensorCheckExplainsAMissingGyroscope() {
        val hs = AssessmentCatalog[AssessmentType.HAND_STABILITY]
        setContent {
            SensorCheckScreen(hs, SensorCheckResult(listOf(SensorRequirement.ACCELEROMETER), listOf(SensorRequirement.GYROSCOPE)), {}, {})
        }
        rule.onNodeWithText("حسگر ژیروسکوپ در این دستگاه در دسترس نیست.").assertIsDisplayed()
        rule.onNodeWithText("✓ شتاب‌سنج: در دسترس است").assertIsDisplayed()
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
    }

    @Test
    fun sensorCheckContinuesWhenEverythingIsAvailable() {
        var continued = 0
        val ft = AssessmentCatalog[AssessmentType.FINGER_TAPPING]
        setContent { SensorCheckScreen(ft, SensorCheckResult(ft.sensors, emptyList()), { continued++ }, {}) }
        rule.onNodeWithText("همه حسگرهای لازم در دسترس هستند.").assertIsDisplayed()
        rule.onNodeWithText("ادامه").assertIsEnabled().performClick()
        assertEquals(1, continued)
    }

    @Test
    fun stabilityResultShowsTheFourSectionsAndDisclaimer() {
        assertNotNull(valid.stabilityIndex)
        setContent { HandStabilityResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf("شاخص ثبات دست", "ثبات کلی", "میزان حرکت", "نوسان حرکتی", "کیفیت ثبت اطلاعات").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText(
            "این شاخص صرفاً ویژگی‌های حرکتی ثبت‌شده توسط حسگرهای گوشی را نشان می‌دهد و به‌تنهایی تشخیص پزشکی محسوب نمی‌شود."
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("بسامد غالب نوسان: ۵٫۰ هرتز", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("از نظر بالینی اعتبارسنجی نشده است", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stabilityResultNeverUsesDiagnosticWording() {
        setContent { HandStabilityResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf("پارکینسون", "احتمال", "غیرطبیعی", "بیماری").forEach {
            rule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun lowQualityStabilityResultHasNoIndex() {
        assertNull(lowQuality.stabilityIndex)
        setContent { HandStabilityResultScreen(Loadable.Loaded(lowQuality), {}, {}, {}) }
        rule.onNodeWithText("به دلیل کیفیت ناکافی ثبت، شاخص محاسبه نشد.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stabilityResultActions() {
        var repeated: SelectedHand? = null
        var next = 0
        var home = 0
        setContent { HandStabilityResultScreen(Loadable.Loaded(valid), { repeated = it }, { next++ }, { home++ }) }
        rule.onNodeWithText("ارزیابی مجدد").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("آزمون بعدی").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("بازگشت به خانه").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(SelectedHand.LEFT, repeated)
        assertEquals(1, next)
        assertEquals(1, home)
    }

    @Test
    fun stabilityTechnicalDetailsShowVersions() {
        setContent { HandStabilityResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        rule.onNodeWithText("نمایش جزئیات فنی (برای پزشک)").performScrollTo().performClick()
        rule.onNodeWithText(valid.algorithmVersion).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(valid.scoringVersion).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun historyShowsEveryAssessmentType() {
        val ft = FingerTappingAnalyzer.toAssessment(
            FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording()), "ft-1", 1_760_000_100_000L, SelectedHand.RIGHT
        )
        var opened: String? = null
        setContent { AssessmentHistoryScreen(Loadable.Loaded(listOf(ft, valid)), onOpen = { opened = it.assessmentId }) }
        rule.onNodeWithText("ضربه زدن با انگشتان").assertIsDisplayed()
        rule.onNodeWithText("نگه‌داشتن دست ثابت").assertIsDisplayed().performClick()
        rule.onNodeWithText("نوسان چرخشی", substring = true).assertIsDisplayed()
        assertEquals("hs-valid", opened)
    }

    @Test
    fun homeShowsTheRoadmapAndLatestOfAnyType() {
        var opened: String? = null
        setContent { HomeScreen(latestAssessment = Loadable.Loaded(valid), onOpenAssessment = { opened = it.assessmentId }, onStartAssessmentClicked = {}) }
        rule.onNodeWithText("ارزیابی‌های حرکتی").assertIsDisplayed()
        rule.onNodeWithText("آزمون حرکتی-شناختی").performScrollTo().assertIsDisplayed()
        // Once in the roadmap, once as the latest result.
        assertEquals(2, rule.onAllNodesWithText("نگه‌داشتن دست ثابت").fetchSemanticsNodes().size)
        rule.onNodeWithText("مشاهده نتیجه").performScrollTo().performClick()
        assertEquals("hs-valid", opened)
    }

    @Test
    fun everyFailureHasAPatientMessage() {
        val report = StabilityQualityReport(QualityStatus.INVALID, listOf(StabilityQualityIssue.EXCESSIVE_GAPS), 10)
        val states = listOf(
            StabilityState.Invalid(StabilityInvalidReason.Interrupted),
            StabilityState.Invalid(StabilityInvalidReason.QualityRejected(report))
        ) + StabilityError.entries.map { StabilityState.Error(it) } +
            StabilityQualityIssue.entries.map {
                StabilityState.Invalid(StabilityInvalidReason.QualityRejected(StabilityQualityReport(it.severity, listOf(it), 0)))
            }
        states.forEach { assertNotNull(it.toString(), stabilityProblem(it)) }
        assertNull(stabilityProblem(StabilityState.Idle))
        assertNull(stabilityProblem(StabilityState.Recording(3)))
    }
}
