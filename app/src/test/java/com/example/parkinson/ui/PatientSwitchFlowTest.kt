package com.example.parkinson.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.parkinson.MainActivity
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.ProfileValidationTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Two people registered on one phone (made-up values): the app asks who is taking the test, and can switch. */
@RunWith(RobolectricTestRunner::class)
class PatientSwitchFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as ParkinsonApplication

    @Before
    fun twoPeople() {
        runBlocking {
            app.profileRepository.save(ProfileValidationTest.sample())
            app.profileRepository.save(
                ProfileValidationTest.sample().copy(
                    firstName = "مریم", lastName = "احمدی", gender = Gender.FEMALE, nationalId = "0013542427",
                )
            )
        }
    }

    private fun waitFor(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String) {
        waitFor(text)
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun choosingAPersonMakesTheTestsTheirs() {
        waitFor("به پایش حرکتی خوش آمدید")
        click("شروع")

        // With nobody chosen yet the patient list opens (or Home asks to choose while the list was loading).
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("بیماران این گوشی")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("انتخاب بیمار")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText("انتخاب بیمار")).fetchSemanticsNodes().isNotEmpty()) click("انتخاب بیمار")
        waitFor("مریم احمدی")

        // People are listed by first name: علی, then مریم.
        rule.onAllNodesWithText("انتخاب")[1].performClick()
        waitFor("تغییر بیمار")
        waitFor("مریم احمدی")
        val active = runBlocking { app.activePatient.profile.first() }
        assertEquals("مریم", active?.firstName)

        click("تغییر بیمار")
        waitFor("انتخاب‌شده")
        rule.onAllNodesWithText("انتخاب")[0].performClick()
        waitFor("علی رضایی")
        assertEquals("علی", runBlocking { app.activePatient.profile.first() }?.firstName)
    }
}
