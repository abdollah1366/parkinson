package com.example.parkinson.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.R
import com.example.parkinson.ui.screens.sts.SitToStandIntroScreen
import com.example.parkinson.ui.theme.ParkinsonTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SitToStandUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun setContent(content: @Composable () -> Unit) = rule.setContent { ParkinsonTheme(darkTheme = false) { content() } }

    @Test
    fun continueStaysDisabledUntilEveryChecklistItemIsConfirmed() {
        var continued = 0
        setContent { SitToStandIntroScreen { continued++ } }
        val button = context.getString(R.string.btn_continue)
        rule.onNodeWithText(button).assertIsNotEnabled()
        val boxes = rule.onAllNodes(isToggleable())
        boxes[0].performScrollTo().performClick()
        boxes[1].performScrollTo().performClick()
        boxes[2].performScrollTo().performClick()
        rule.onNodeWithText(button).assertIsNotEnabled()
        boxes[3].performScrollTo().performClick()
        rule.onNodeWithText(button).assertIsEnabled().performClick()
        assertEquals(1, continued)
    }

    @Test
    fun safetyInstructionsAreShownBeforeAnyRepetition() {
        setContent { SitToStandIntroScreen {} }
        rule.onNodeWithText(context.getString(R.string.sts_safety_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.sts_safety_text)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.sts_checklist_title)).performScrollTo().assertIsDisplayed()
    }
}
