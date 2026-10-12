package com.example.parkinson.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.parkinson.MainActivity
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.profile.ProfileValidationTest
import com.example.parkinson.sync.FakeServerApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Connecting a person (made-up values) to a doctor with an invite code, against a fake server. */
@RunWith(RobolectricTestRunner::class)
class ServerLinkFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as ParkinsonApplication
    private val api = FakeServerApi(doctorName = "دکتر نمونه")

    @Before
    fun registered() {
        app.serverApi = api
        runBlocking { app.profileRepository.save(ProfileValidationTest.sample()) }
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String) {
        waitFor(text)
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun connectingNeedsConsentAndThenShowsTheDoctor() {
        waitFor("به پایش حرکتی خوش آمدید")
        click("شروع")
        click("اتصال به پزشک")
        waitFor("کد دعوت")

        rule.onNodeWithText("نشانی سرور پزشک").performTextInput("panel.example.ir")
        rule.onNodeWithText("کد دعوت").performTextInput("abcd-2345")
        // Not before the person agrees to what is sent.
        assertTrue(api.linked.isEmpty())
        click("موافقم که این اطلاعات برای پزشکم فرستاده و روی سرور او نگه‌داری شود.")
        click("اتصال")

        waitFor("وصل است", substring = true)
        waitFor("دکتر نمونه", substring = true)
        assertEquals("abcd-2345", api.linked.single().first)
        assertEquals("https://panel.example.ir", app.resultSync.links.value.values.single().serverUrl)

        click("قطع اتصال")
        waitFor("اتصال قطع شود؟")
        click("بله، قطع شود")
        waitFor("کد دعوت")
        assertEquals(listOf("token-1"), api.revoked)
        assertTrue(app.resultSync.links.value.isEmpty())
    }
}
