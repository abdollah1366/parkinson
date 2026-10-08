package com.example.parkinson.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.PronationError
import com.example.parkinson.pronation.PronationQualityIssue
import com.example.parkinson.pronation.PronationQualityReport
import com.example.parkinson.pronation.PronationState
import com.example.parkinson.pronation.PronationSupinationEngine
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.pronation.SyntheticRotation
import com.example.parkinson.ui.screens.history.AssessmentHistoryScreen
import com.example.parkinson.ui.screens.pronation.PronationSupinationIntroScreen
import com.example.parkinson.ui.screens.pronation.pronationProblem
import com.example.parkinson.ui.screens.result.PronationSupinationResultScreen
import com.example.parkinson.ui.theme.ParkinsonTheme
import com.example.parkinson.R
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

    private fun result(id: String, s: SyntheticRotation) = PronationSupinationResult.from(
        PronationSupinationEngine().analyze(s.recording()), id, 1_760_000_000_000L, SelectedHand.LEFT, 10_000L
    )

    private val valid = result("ps-valid", SyntheticRotation().sinusoid(1.5, 90.0))
    private val lowQuality = result("ps-low", SyntheticRotation().sinusoid(1.5, 90.0).gap(5_000, 5_300))

    @Test
    fun introShowsTitleAndInstruction() {
        var continued = 0
        setContent { PronationSupinationIntroScreen { continued++ } }
        rule.onNodeWithText("چرخش دست").assertIsDisplayed()
        rule.onNodeWithText("گوشی را مطابق دستور در دست بگیرید و کف دست را به‌آرامی به بالا و پایین بچرخانید.").assertIsDisplayed()
        rule.onNodeWithText("۵ ثانیه آمادگی + ۱۰ ثانیه ثبت").assertIsDisplayed()
        rule.onNodeWithText("ادامه").performClick()
        assertEquals(1, continued)
    }

    @Test
    fun resultShowsEverySectionTheIndexLabelAndTheDisclaimer() {
        assertNotNull(valid.performanceScore)
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf(
            "نتیجه آزمون چرخش دست", "تعداد چرخه‌ها", "سرعت حرکت", "ریتم حرکت", "دامنه حرکت",
            "پایداری حرکت", "روند عملکرد", "کیفیت ثبت", "شاخص عملکرد حرکتی", "نسخه پژوهشی / غیرتشخیصی"
        ).forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText(
            "این نتایج صرفاً برای ارزیابی و پایش عملکرد حرکتی هستند و به‌تنهایی تشخیص پزشکی محسوب نمی‌شوند."
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("دامنه: تقریباً ثابت", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultNeverUsesDiagnosticWording() {
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf("پارکینسون", "احتمال", "غیرطبیعی", "بیماری", "پیشرفت").forEach {
            rule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun lowQualityResultHasNoIndex() {
        assertEquals(QualityStatus.LOW_QUALITY, lowQuality.qualityStatus)
        assertNull(lowQuality.performanceScore)
        setContent { PronationSupinationResultScreen(Loadable.Loaded(lowQuality), {}, {}, {}) }
        rule.onNodeWithText("به دلیل کیفیت ناکافی ثبت، شاخص محاسبه نشد.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultActions() {
        var repeated: SelectedHand? = null
        var next = 0
        var home = 0
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), { repeated = it }, { next++ }, { home++ }) }
        rule.onNodeWithText("تلاش مجدد").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("آزمون بعدی").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("بازگشت به خانه").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(SelectedHand.LEFT, repeated)
        assertEquals(1, next)
        assertEquals(1, home)
    }

    @Test
    fun technicalDetailsShowVersions() {
        setContent { PronationSupinationResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        rule.onNodeWithText("نمایش جزئیات فنی (برای پزشک)").performScrollTo().performClick()
        rule.onNodeWithText(valid.algorithmVersion).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(valid.scoringVersion).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun historyShowsTitleCyclesAndIndex() {
        setContent { AssessmentHistoryScreen(Loadable.Loaded(listOf(valid)), onOpen = {}) }
        rule.onNodeWithText("آزمون چرخش دست").assertIsDisplayed()
        rule.onNodeWithText("${com.example.parkinson.ui.format.PersianFormat.integer(valid.cycleCount)} چرخه", substring = true)
            .assertIsDisplayed()
        rule.onNodeWithText("شاخص عملکرد", substring = true).assertIsDisplayed()
    }

    @Test
    fun qualityRejectionShowsTheRequiredMessage() {
        val report = PronationQualityReport(QualityStatus.INSUFFICIENT_DATA, listOf(PronationQualityIssue.NO_MOVEMENT_DETECTED), 90)
        val problem = pronationProblem(PronationState.Invalid(report))!!
        assertEquals(R.string.ps_invalid_quality, problem.message)
        assertEquals(R.string.ps_reason_no_movement, problem.detail)
    }

    @Test
    fun everyFailureHasAPatientMessage() {
        val states = listOf<PronationState>(PronationState.Interrupted) +
            PronationError.entries.map { PronationState.Error(it) } +
            PronationQualityIssue.entries.map {
                PronationState.Invalid(PronationQualityReport(it.severity, listOf(it), 0))
            }
        states.forEach { assertNotNull(it.toString(), pronationProblem(it)) }
        // A missing gyroscope uses the required sentence.
        assertEquals(
            R.string.sensor_missing_gyroscope,
            pronationProblem(PronationState.Error(PronationError.SENSOR_UNAVAILABLE))!!.message
        )
        assertNull(pronationProblem(PronationState.Idle))
    }
}
