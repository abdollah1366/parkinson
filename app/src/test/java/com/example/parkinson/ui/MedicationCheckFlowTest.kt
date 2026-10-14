package com.example.parkinson.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.parkinson.MainActivity
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.medication.MedicationState
import com.example.parkinson.profile.ProfileValidationTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A person who takes medication (made-up values) is asked how it is working before the tests, once. */
@RunWith(RobolectricTestRunner::class)
class MedicationCheckFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as ParkinsonApplication
    private var id = 0

    @Before
    fun registeredAndTakingMedication() {
        id = runBlocking { app.profileRepository.save(ProfileValidationTest.sample()) }
    }

    private fun waitFor(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String) {
        waitFor(text)
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun theStateIsAskedBeforeTheTestsAndShownOnHome() {
        waitFor("به پایش حرکتی خوش آمدید")
        click("شروع")
        waitFor("وضعیت دارو هنوز ثبت نشده است.")

        click("شروع ارزیابی")
        waitFor("نتیجه‌ی آزمون‌ها وقتی دارو اثر کرده")
        click("اثر دارو رفته یا هنوز دارو نخورده‌ام (OFF)")
        waitFor("مجموعه کامل آزمون‌های حرکتی")
        assertEquals(MedicationState.OFF, app.medicationChecks.current(id, System.currentTimeMillis())?.state)

        // Back on Home the state is shown, and starting again does not ask a second time.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("وضعیت دارو: OFF")
        click("شروع ارزیابی")
        waitFor("مجموعه کامل آزمون‌های حرکتی")
    }
}
