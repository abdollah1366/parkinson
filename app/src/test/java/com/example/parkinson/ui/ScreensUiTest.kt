package com.example.parkinson.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.tapping.raw.FrameStatus
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.ui.screens.history.AssessmentHistoryScreen
import com.example.parkinson.ui.screens.home.HomeScreen
import com.example.parkinson.ui.screens.invalid.InvalidResultKind
import com.example.parkinson.ui.screens.invalid.InvalidResultScreen
import com.example.parkinson.ui.screens.result.FingerTappingResultScreen
import com.example.parkinson.ui.screens.selection.FingerTappingHandSelectionScreen
import com.example.parkinson.ui.theme.ParkinsonTheme
import com.example.parkinson.viewmodel.Loadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Persian RTL screens rendered on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
class ScreensUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) = rule.setContent { ParkinsonTheme(darkTheme = false) { content() } }

    private val valid: FingerTappingAssessment = FingerTappingAnalyzer.toAssessment(
        FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording()),
        "valid-1", 1_760_000_000_000L, SelectedHand.RIGHT
    )
    private val lowQuality: FingerTappingAssessment = FingerTappingAnalyzer.toAssessment(
        FingerTappingAnalyzer().analyze(
            SyntheticTapping().regular(3.0)
                .status(2_000, 2_600, FrameStatus.NO_HAND)
                .status(5_000, 5_600, FrameStatus.NO_HAND)
                .status(8_000, 8_600, FrameStatus.NO_HAND)
                .recording()
        ),
        "low-1", 1_760_000_100_000L, SelectedHand.LEFT
    )

    @Test
    fun themeIsRightToLeft() {
        var direction: LayoutDirection? = null
        setContent { direction = LocalLayoutDirection.current }
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun resultScreenShowsEveryMeasureAndTheDisclaimer() {
        setContent { FingerTappingResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf(
            "نتیجه آزمون ضربه با انگشت", "تعداد ضربه", "سرعت ضربه", "نظم ضربه‌ها",
            "دامنه حرکت", "پایداری حرکت", "کیفیت داده", "تفسیر نتیجه", "روند عملکرد"
        ).forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        // The index card is one TalkBack sentence: "شاخص عملکرد حرکتی X از ۱۰۰، <band>".
        rule.onNodeWithContentDescription("شاخص عملکرد حرکتی ${com.example.parkinson.ui.format.PersianFormat.integer(valid.performanceScore!!.total)} از ۱۰۰", substring = true)
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "این نتایج صرفاً برای ارزیابی و پایش عملکرد حرکتی هستند و به‌تنهایی تشخیص پزشکی محسوب نمی‌شوند."
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("هنوز بر اساس داده هنجاری جمعیت بزرگ اعتبارسنجی نشده است", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultScreenNeverUsesDiagnosticWording() {
        setContent { FingerTappingResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        listOf("پارکینسون", "احتمال", "غیرطبیعی").forEach {
            rule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun lowQualityResultIsScoredWithLimitedReliability() {
        assertEquals(com.example.parkinson.assessment.ReliabilityLevel.LIMITED, lowQuality.performanceScore!!.reliability)
        setContent { FingerTappingResultScreen(Loadable.Loaded(lowQuality), {}, {}, {}) }
        rule.onNodeWithText("نتیجه با اطمینان محدود قابل تفسیر است.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("کیفیت داده محدود بود.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultActionsInvokeCallbacks() {
        var repeated: SelectedHand? = null
        var newCount = 0
        var homeCount = 0
        setContent {
            FingerTappingResultScreen(Loadable.Loaded(valid), { repeated = it }, { newCount++ }, { homeCount++ })
        }
        rule.onNodeWithText("ارزیابی مجدد").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("ارزیابی جدید").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("بازگشت به خانه").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(SelectedHand.RIGHT, repeated)
        assertEquals(1, newCount)
        assertEquals(1, homeCount)
    }

    @Test
    fun technicalDetailsAreHiddenUntilRequested() {
        setContent { FingerTappingResultScreen(Loadable.Loaded(valid), {}, {}, {}) }
        rule.onNodeWithText("نسخه الگوریتم").assertDoesNotExist()
        rule.onNodeWithText("نمایش جزئیات فنی (برای پزشک)").performScrollTo().performClick()
        rule.onNodeWithText("نسخه الگوریتم").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(valid.algorithmVersion).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun missingResultShowsNotFound() {
        setContent { FingerTappingResultScreen(Loadable.Loaded(null), {}, {}, {}) }
        rule.onNodeWithText("این ارزیابی پیدا نشد.").assertIsDisplayed()
    }

    @Test
    fun invalidScreenExplainsAndOffersRetry() {
        var retries = 0
        setContent { InvalidResultScreen(InvalidResultKind.NO_HAND, onRetry = { retries++ }, onHome = {}) }
        // NO_HAND is insufficient motor data: the specific headline, not a generic or lighting message.
        rule.onNodeWithText("داده کافی برای محاسبه نتیجه وجود ندارد.").assertIsDisplayed()
        rule.onNodeWithText("لطفاً دست را در محدوده دوربین نگه دارید و دوباره تلاش کنید.").assertIsDisplayed()
        rule.onNodeWithText("تلاش دوباره").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun everyInvalidKindRenders() {
        var kind by mutableStateOf(InvalidResultKind.entries.first())
        setContent { InvalidResultScreen(kind, {}, {}) }
        InvalidResultKind.entries.forEach {
            kind = it
            rule.waitForIdle()
            rule.onNodeWithText("نتیجه‌ای ثبت نشد").assertIsDisplayed()
        }
    }

    @Test
    fun historyShowsItemsAndOpensOne() {
        var opened: String? = null
        setContent { AssessmentHistoryScreen(Loadable.Loaded(listOf(valid, lowQuality)), onOpen = { opened = it.assessmentId }) }
        rule.onNodeWithText("تاریخچه ارزیابی‌ها").assertIsDisplayed()
        // Both results are scored now (the LOW_QUALITY one with limited reliability).
        rule.onNodeWithText("شاخص عملکرد: ${com.example.parkinson.ui.format.PersianFormat.integer(lowQuality.performanceScore!!.total)} از ۱۰۰").assertIsDisplayed()
        rule.onNodeWithText("دست چپ").performClick()
        assertEquals("low-1", opened)
    }

    @Test
    fun emptyHistory() {
        setContent { AssessmentHistoryScreen(Loadable.Loaded(emptyList()), onOpen = {}) }
        rule.onNodeWithText("هنوز ارزیابی‌ای ذخیره نشده است.").assertIsDisplayed()
    }

    @Test
    fun homeShowsLatestAssessment() {
        var opened: String? = null
        var history = 0
        setContent {
            HomeScreen(
                latestAssessment = Loadable.Loaded(valid),
                onOpenAssessment = { opened = it.assessmentId },
                onOpenHistory = { history++ },
                onStartAssessmentClicked = {}
            )
        }
        rule.onNodeWithText("آخرین ارزیابی").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("دست راست").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("مشاهده نتیجه").performScrollTo().performClick()
        rule.onNodeWithText("مشاهده تاریخچه").performScrollTo().performClick()
        assertEquals("valid-1", opened)
        assertEquals(1, history)
    }

    @Test
    fun homeWithoutAssessments() {
        setContent { HomeScreen(latestAssessment = Loadable.Loaded(null), onStartAssessmentClicked = {}) }
        rule.onNodeWithText("هنوز ارزیابی‌ای انجام نشده است.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("شروع ارزیابی").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun handSelectionExposesSelectedState() {
        var selected by mutableStateOf<SelectedHand?>(null)
        setContent { FingerTappingHandSelectionScreen(selected, onHandSelected = { selected = it }, onContinueClicked = {}) }
        rule.onNodeWithText("دست چپ").performClick()
        rule.waitForIdle()
        assertEquals(SelectedHand.LEFT, selected)
        rule.onNodeWithText("دست چپ", useUnmergedTree = false).assertIsSelected()
        rule.onNodeWithText("دست راست").assertIsNotSelected()
    }
}
