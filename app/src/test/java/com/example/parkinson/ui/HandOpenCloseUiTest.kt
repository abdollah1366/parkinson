package com.example.parkinson.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.R
import com.example.parkinson.ui.screens.openclose.HandOpenCloseCue
import com.example.parkinson.ui.screens.openclose.HandOpenCloseIntroScreen
import com.example.parkinson.ui.theme.ParkinsonTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HandOpenCloseUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun setContent(content: @Composable () -> Unit) = rule.setContent { ParkinsonTheme(darkTheme = false) { content() } }

    @Test
    fun introShowsTheRepeatInstructionAndAnAccessibleDemonstration() {
        setContent { HandOpenCloseIntroScreen {} }
        rule.onNodeWithText(context.getString(R.string.oc_cue_text)).assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.oc_cue_description)).assertIsDisplayed()
    }

    @Test
    fun cueDrawsWithoutTextWhenTextIsHidden() {
        setContent { HandOpenCloseCue(showText = false) }
        rule.onNodeWithContentDescription(context.getString(R.string.oc_cue_description)).assertIsDisplayed()
    }
}
