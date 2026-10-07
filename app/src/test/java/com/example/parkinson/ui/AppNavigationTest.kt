package com.example.parkinson.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.parkinson.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Walks the real app (MainActivity, nav graph, ViewModels, Room) from the splash screen to the
 * camera screen and back. The camera permission is not granted under Robolectric, so the camera
 * screen shows its permission card; MediaPipe cannot load natively on the JVM and must fail
 * gracefully instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
class AppNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clickWhenShown(text: String) {
        waitFor(text)
        val node = rule.onNodeWithText(text)
        // Only some screens scroll; scroll first when possible.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun walksFromSplashToCameraAndBack() {
        // Splash -> Welcome
        waitFor("به پایش حرکتی خوش آمدید")
        clickWhenShown("شروع")

        // Home with no stored assessment
        waitFor("هنوز ارزیابی‌ای انجام نشده است.")
        clickWhenShown("شروع ارزیابی")

        // Intro -> Hand selection
        waitFor("آزمون ضربه زدن انگشت‌ها")
        clickWhenShown("ادامه")
        waitFor("انتخاب دست")
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
        rule.onNodeWithText("دست چپ").performClick()
        rule.onNodeWithText("ادامه").assertIsEnabled().performClick()

        // Preparation -> Ready
        waitFor("آماده‌سازی آزمون")
        clickWhenShown("ادامه")
        waitFor("آماده شروع هستید؟")
        rule.onNodeWithText("دست چپ").performScrollTo().assertIsDisplayed()
        clickWhenShown("آماده‌ام")

        // Camera screen: permission card, start disabled, no crash from MediaPipe on the JVM.
        waitFor("برای انجام آزمون حرکتی، دسترسی به دوربین لازم است.")
        rule.onNodeWithText("فعال کردن دوربین").assertIsDisplayed()
        rule.onNodeWithText("شروع آزمون").assertIsNotEnabled()

        // System back returns to the ready screen.
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("آماده شروع هستید؟")
    }

    @Test
    fun homeSurvivesRecreation() {
        waitFor("به پایش حرکتی خوش آمدید")
        clickWhenShown("شروع")
        waitFor("هنوز ارزیابی‌ای انجام نشده است.")
        rule.activityRule.scenario.recreate()
        waitFor("هنوز ارزیابی‌ای انجام نشده است.")
        rule.onNodeWithText("شروع ارزیابی").assertIsDisplayed()
    }
}
