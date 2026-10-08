package com.example.parkinson.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentCatalog
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.SensorCheckResult
import com.example.parkinson.assessment.SensorRequirement
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.MotorPerformanceBand
import com.example.parkinson.pronation.PronationError
import com.example.parkinson.pronation.PronationQualityIssue
import com.example.parkinson.pronation.PronationQualityReport
import com.example.parkinson.pronation.PronationState
import com.example.parkinson.pronation.PronationSupinationEngine
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.pronation.SyntheticRotation
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.screens.history.AssessmentHistoryScreen
import com.example.parkinson.ui.screens.pronation.PronationSupinationIntroScreen
import com.example.parkinson.ui.screens.pronation.pronationProblem
import com.example.parkinson.ui.screens.result.PronationSupinationResultScreen
import com.example.parkinson.ui.screens.sensorcheck.SensorCheckScreen
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
class PronationSupinationUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) = rule.setContent { ParkinsonTheme(darkTheme = false) { content() } }

    private fun result(id: String, s: SyntheticRotation, hand: SelectedHand = SelectedHand.RIGHT) = PronationSupinationResult.from(
        PronationSupinationEngine().analyze(s.recording(hand)), id, "session", 1_760_000_000_000L, hand, 10_000L
    )

    private val valid = result("ps-valid", SyntheticRotation().sinusoid(2.2, 90.0))
    private val lowQuality = result("ps-low", SyntheticRotation().sinusoid(1.5, 90.0).gap(5_000, 5_300))

    @Test
    fun introRequiresAHandBeforeStarting() {
        var hand by mutableStateOf<SelectedHand?>(null)
        var started = 0
        setContent { PronationSupinationIntroScreen(selectedHand = hand, onHandSelected = { hand = it }, onStartClicked = { started++ }) }
        rule.onNodeWithText("آزمون چرخش دست").assertIsDisplayed()
        rule.onNodeWithText("Pronation / Supination").assertIsDisplayed()
        rule.onNodeWithText("گوشی را مطابق راهنمای برنامه در دست بگیرید و کف دست را به‌آرامی به سمت بالا و پایین بچرخانید.").assertIsDisplayed()
        rule.onNodeWithText("این آزمون عملکرد حرکتی را بررسی می‌کند و به‌تنهایی برای تشخیص بیماری کافی نیست.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("شروع آزمون").assertIsNotEnabled()

        rule.onNodeWithContentDescription("دست چپ، انتخاب نشده").performScrollTo().performClick()
        assertEquals(SelectedHand.LEFT, hand)
        rule.onNodeWithContentDescription("دست چپ، انتخاب شده").assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("شروع آزمون").assertIsEnabled().performClick()
        assertEquals(1, started)
    }

    @Test
    fun sensorCheckUsesTheGyroscopeMessageAndAllowsMissingAccelerometer() {
        val ps = AssessmentCatalog[AssessmentType.PRONATION_SUPINATION]
        setContent { SensorCheckScreen(ps, SensorCheckResult(emptyList(), listOf(SensorRequirement.GYROSCOPE)), {}, {}) }
        rule.onNodeWithText("سنسور ژیروسکوپ در این دستگاه در دسترس نیست.").assertIsDisplayed()
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
    }

    @Test
    fun missingAccelerometerIsAWarningNotABlock() {
        val ps = AssessmentCatalog[AssessmentType.PRONATION_SUPINATION]
        setContent {
            SensorCheckScreen(ps, SensorCheckResult(listOf(SensorRequirement.GYROSCOPE), emptyList(), listOf(SensorRequirement.ACCELEROMETER)), {}, {})
        }
        rule.onNodeWithText("آزمون فقط با ژیروسکوپ انجام می‌شود", substring = true).assertIsDisplayed()
        rule.onNodeWithText("ادامه").assertIsEnabled()
    }

    @Test
    fun resultShowsIndexBandSummaryAndDisclaimers() {
        val score = valid.performanceScore!!
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        rule.onNodeWithText("نتیجه آزمون چرخش دست").assertIsDisplayed()
        val band = when (MotorPerformanceBand.forScore(score)) {
            MotorPerformanceBand.GOOD -> "عملکرد حرکتی خوب"
            MotorPerformanceBand.ACCEPTABLE -> "عملکرد حرکتی قابل قبول"
            MotorPerformanceBand.REDUCED -> "کاهش نسبی عملکرد حرکتی"
            MotorPerformanceBand.SIGNIFICANTLY_REDUCED -> "کاهش قابل توجه عملکرد حرکتی"
            MotorPerformanceBand.VERY_LOW -> "عملکرد حرکتی بسیار پایین"
        }
        // TalkBack reads the index as one sentence.
        rule.onNodeWithContentDescription("شاخص عملکرد حرکتی ${PersianFormat.integer(score)} از ۱۰۰، $band").assertIsDisplayed()
        listOf(
            "خلاصه نتیجه", "دست", "مدت آزمون", "تعداد چرخش", "سرعت", "نظم حرکت", "دامنه حرکت", "پایداری", "توقف‌ها",
            "کیفیت داده", "تفسیر نتیجه", "سرعت زاویه‌ای در طول آزمون", "روند عملکرد در طول آزمون"
        ).forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("این نمره یک شاخص داخلی نرم‌افزار است و هنوز بر اساس داده هنجاری جمعیت بزرگ اعتبارسنجی نشده است.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("در این آزمون، حرکات چرخشی دست با سرعت و نظم مناسب ثبت شد.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("این نتایج صرفاً برای ارزیابی و پایش عملکرد حرکتی هستند و به‌تنهایی تشخیص پزشکی محسوب نمی‌شوند.")
            .performScrollTo().assertIsDisplayed()
        // Technical details are collapsed by default.
        rule.onNodeWithText("نسخه الگوریتم").assertDoesNotExist()
    }

    @Test
    fun resultNeverUsesDiagnosticWording() {
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf("پارکینسون", "احتمال", "غیرطبیعی", "برادی", "پیشرفت بیماری").forEach {
            rule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun lowQualityResultIsShownWithLimitedReliability() {
        assertEquals(QualityStatus.LOW_QUALITY, lowQuality.qualityStatus)
        assertNotNull(lowQuality.performanceScore)
        setContent { PronationSupinationResultScreen(Loadable.Loaded(lowQuality), {}, {}, {}) }
        rule.onNodeWithText("نتیجه با اطمینان محدود قابل تفسیر است.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("برای اطمینان بیشتر، تکرار آزمون پیشنهاد می‌شود.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultActions() {
        var repeated: SelectedHand? = null
        var tests = 0
        var home = 0
        val left = result("ps-left", SyntheticRotation().sinusoid(1.5, 90.0), SelectedHand.LEFT)
        setContent { PronationSupinationResultScreen(Loadable.Loaded(left), { repeated = it }, { tests++ }, { home++ }) }
        rule.onNodeWithText("تکرار آزمون").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("بازگشت به فهرست آزمون‌ها").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("بازگشت به خانه").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(SelectedHand.LEFT, repeated)
        assertEquals(1, tests)
        assertEquals(1, home)
    }

    @Test
    fun technicalDetailsShowVersionsWhenExpanded() {
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        rule.onNodeWithText("نمایش جزئیات فنی").performScrollTo().performClick()
        rule.onNodeWithText("نسخه الگوریتم").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("نسخه امتیازدهی").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("internal-software-reference-1.0").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun historyShowsTitleHandCyclesScoreAndQuality() {
        var opened: String? = null
        setContent { AssessmentHistoryScreen(Loadable.Loaded(listOf(valid)), onOpen = { opened = it.assessmentId }) }
        rule.onNodeWithText("آزمون چرخش دست").assertIsDisplayed().performClick()
        rule.onNodeWithText("دست راست").assertIsDisplayed()
        rule.onNodeWithText("${PersianFormat.integer(valid.cycleCount)} چرخه", substring = true).assertIsDisplayed()
        rule.onNodeWithText("شاخص عملکرد: ${PersianFormat.integer(valid.performanceScore!!)} از ۱۰۰").assertIsDisplayed()
        rule.onNodeWithText("کیفیت", substring = true).assertIsDisplayed()
        assertEquals("ps-valid", opened)
    }

    @Test
    fun qualityRejectionShowsTheRequiredMessages() {
        val report = PronationQualityReport(QualityStatus.INSUFFICIENT_DATA, listOf(PronationQualityIssue.NO_MOVEMENT_DETECTED), 90, 10_000.0, null)
        val problem = pronationProblem(PronationState.Invalid(report))!!
        assertEquals(R.string.ps_invalid_quality, problem.message)
        assertEquals(R.string.ps_reason_no_movement, problem.detail)
    }

    @Test
    fun everyFailureHasAPatientMessage() {
        val states = listOf<PronationState>(PronationState.Interrupted) +
            PronationError.entries.map { PronationState.Error(it) } +
            PronationQualityIssue.entries.map {
                PronationState.Invalid(PronationQualityReport(it.severity, listOf(it), 0, 0.0, null))
            }
        states.forEach { assertNotNull(it.toString(), pronationProblem(it)) }
        assertEquals(R.string.ps_sensor_missing_gyroscope, pronationProblem(PronationState.Error(PronationError.SENSOR_UNAVAILABLE))!!.message)
        assertEquals(R.string.ps_error_rate, pronationProblem(PronationState.Error(PronationError.SAMPLING_RATE_TOO_LOW))!!.message)
        assertNull(pronationProblem(PronationState.Idle))
    }
}
