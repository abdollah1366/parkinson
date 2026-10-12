package com.example.parkinson.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.parkinson.MainActivity
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.profile.Gender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A first start without a registration: Welcome -> Registration (validated) -> Home with the name. */
@RunWith(RobolectricTestRunner::class)
class RegistrationFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String) {
        waitFor(text)
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun type(label: String, text: String) {
        val node = rule.onNodeWithText(label)
        runCatching { node.performScrollTo() }
        node.performTextInput(text)
    }

    @Test
    fun aNewPersonRegistersBeforeHome() {
        waitFor("به پایش حرکتی خوش آمدید")
        click("شروع")

        waitFor("پیش از شروع آزمون‌ها، اطلاعات زیر را وارد کنید. همه‌ی بخش‌ها لازم است.")
        click("ذخیره")
        waitFor("بخش‌های قرمز را کامل یا اصلاح کنید.")

        type("نام", "علی")
        type("نام خانوادگی", "رضایی")
        click("انتخاب سن (۱۰ تا ۱۱۰ سال)")
        click("۱۰ سال")
        click("مرد")
        type("تلفن همراه", "09121234567")
        // A made-up code with a valid check digit.
        type("کد ملی", "0013542419")
        click("خیر")
        click("راست")
        click("موافقم این اطلاعات روی این گوشی نگه داشته شود.")
        click("ذخیره")

        waitFor("علی رضایی")
        val saved = runBlocking {
            (RuntimeEnvironment.getApplication() as ParkinsonApplication).profileRepository.observeAll().first()
        }.single()
        assertEquals(10, saved.age)
        assertEquals(Gender.MALE, saved.gender)
        assertEquals("0013542419", saved.nationalId)
    }
}
