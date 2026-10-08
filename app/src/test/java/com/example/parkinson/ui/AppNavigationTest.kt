package com.example.parkinson.ui

import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.example.parkinson.MainActivity
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingSession
import com.example.parkinson.tapping.SessionInvalidReason
import com.example.parkinson.tapping.SessionState
import com.example.parkinson.tapping.isActive
import com.example.parkinson.viewmodel.FingerTappingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

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

    @Before
    fun deviceHasACamera() {
        // Robolectric reports no camera by default; the sensor check would (correctly) block.
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    /** Home -> test selection -> the card titled [title]. */
    private fun openFromCatalog(title: String) {
        clickWhenShown("شروع ارزیابی")
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("مجموعه کامل آزمون‌های حرکتی", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        rule.onNodeWithText(title).performClick()
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
        openFromCatalog("ضربه زدن با انگشتان")

        // Intro -> Hand selection
        waitFor("آزمون ضربه زدن انگشت‌ها")
        clickWhenShown("ادامه")
        waitFor("انتخاب دست")
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
        rule.onNodeWithText("دست چپ").performClick()
        rule.onNodeWithText("ادامه").assertIsEnabled().performClick()

        // Sensor check: camera and hand model are available.
        waitFor("بررسی حسگرها")
        rule.onNodeWithText("همه حسگرهای لازم در دسترس هستند.").assertIsDisplayed()
        clickWhenShown("ادامه")
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

    private fun openCameraScreen() {
        waitFor("به پایش حرکتی خوش آمدید")
        clickWhenShown("شروع")
        openFromCatalog("ضربه زدن با انگشتان")
        clickWhenShown("ادامه")
        waitFor("انتخاب دست")
        rule.onNodeWithText("دست راست").performClick()
        clickWhenShown("ادامه")
        waitFor("بررسی حسگرها")
        clickWhenShown("ادامه")
        waitFor("آماده‌سازی آزمون")
        clickWhenShown("ادامه")
        clickWhenShown("آماده‌ام")
        waitFor("برای انجام آزمون حرکتی، دسترسی به دوربین لازم است.")
    }

    private fun session(): FingerTappingSession {
        lateinit var session: FingerTappingSession
        rule.activityRule.scenario.onActivity {
            session = ViewModelProvider(it, FingerTappingViewModel.Factory)[FingerTappingViewModel::class.java].session
        }
        return session
    }

    @Test
    fun rotationDuringATestEndsAsInterruptedWithoutResult() {
        openCameraScreen()
        // Start directly: under Robolectric there is no camera, so the start button stays disabled.
        rule.activityRule.scenario.onActivity { session().start(SelectedHand.RIGHT) }
        assertTrue(session().state.value.isActive)

        rule.activityRule.scenario.recreate()

        waitFor("نتیجه‌ای ثبت نشد")
        rule.onNodeWithText("آزمون پیش از پایان قطع شد", substring = true).assertIsDisplayed()
        assertTrue(session().state.value !is SessionState.Done)

        // Retry returns to a fresh camera screen.
        clickWhenShown("تلاش دوباره")
        waitFor("برای انجام آزمون حرکتی، دسترسی به دوربین لازم است.")
        assertEquals(SessionState.Idle, session().state.value)
    }

    @Test
    fun leavingDuringATestDiscardsIt() {
        openCameraScreen()
        rule.activityRule.scenario.onActivity { session().start(SelectedHand.RIGHT) }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        waitFor("آماده شروع هستید؟")
        rule.waitForIdle()
        assertEquals(SessionState.Idle, session().state.value)
    }

    @Test
    fun backgroundingDuringATestEndsAsInterrupted() {
        openCameraScreen()
        rule.activityRule.scenario.onActivity { session().start(SelectedHand.RIGHT) }
        // Home button / screen lock: the activity is stopped.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(SessionState.Invalid(SessionInvalidReason.Interrupted), session().state.value)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("نتیجه‌ای ثبت نشد")
        rule.onNodeWithText("آزمون پیش از پایان قطع شد", substring = true).assertIsDisplayed()
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

    private fun openHandStabilitySensorCheck() {
        waitFor("به پایش حرکتی خوش آمدید")
        clickWhenShown("شروع")
        openFromCatalog("نگه‌داشتن دست ثابت")
        waitFor("روش انجام")
        clickWhenShown("ادامه")
        waitFor("انتخاب دست")
        rule.onNodeWithText("دست چپ").performClick()
        clickWhenShown("ادامه")
        waitFor("بررسی حسگرها")
    }

    @Test
    fun handStabilityIsBlockedWithoutMotionSensors() {
        // Robolectric devices have no accelerometer or gyroscope by default.
        openHandStabilitySensorCheck()
        rule.onNodeWithText("حسگر ژیروسکوپ در این دستگاه در دسترس نیست.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("حسگر شتاب‌سنج در این دستگاه در دسترس نیست.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
    }

    @Test
    fun handStabilityReachesTheTestWhenSensorsExist() {
        val sensors = shadowOf(RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
        openHandStabilitySensorCheck()
        rule.onNodeWithText("همه حسگرهای لازم در دسترس هستند.").assertIsDisplayed()
        clickWhenShown("ادامه")
        waitFor("وقتی آماده بودید، گوشی را در دست چپ بگیرید و دکمه شروع را بزنید.")
        rule.onNodeWithText("شروع آزمون").assertIsEnabled()
    }

    private fun openPronationSensorCheck() {
        waitFor("به پایش حرکتی خوش آمدید")
        clickWhenShown("شروع")
        openFromCatalog("آزمون چرخش دست")
        waitFor("گوشی را مطابق راهنمای برنامه در دست بگیرید و کف دست را به‌آرامی به سمت بالا و پایین بچرخانید.")
        // The hand is chosen on the instruction screen; start is disabled until then.
        rule.onNodeWithText("شروع آزمون").assertIsNotEnabled()
        rule.onNodeWithContentDescription("دست راست، انتخاب نشده").performScrollTo().performClick()
        clickWhenShown("شروع آزمون")
        waitFor("بررسی حسگرها")
    }

    @Test
    fun pronationSupinationIsBlockedWithoutGyroscope() {
        openPronationSensorCheck()
        rule.onNodeWithText("سنسور ژیروسکوپ در این دستگاه در دسترس نیست.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
    }

    @Test
    fun pronationSupinationRunsWithGyroscopeAndAsksBeforeStopping() {
        val sensors = shadowOf(RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        // Gyroscope only: allowed, with an explicit warning about the missing accelerometer.
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
        openPronationSensorCheck()
        rule.onNodeWithText("آزمون فقط با ژیروسکوپ انجام می‌شود", substring = true).assertIsDisplayed()
        clickWhenShown("ادامه")
        waitFor("وقتی آماده بودید، گوشی را در دست راست بگیرید و دکمه شروع را بزنید.")
        clickWhenShown("شروع آزمون")
        waitFor("آماده‌سازی… ۵")
        // Cancel asks first; "continue" keeps the test running.
        rule.onNodeWithText("لغو آزمون").assertIsEnabled().performClick()
        waitFor("آیا می‌خواهید آزمون را متوقف کنید؟")
        rule.onNodeWithText("ادامه آزمون").performClick()
        rule.onNodeWithText("آیا می‌خواهید آزمون را متوقف کنید؟").assertDoesNotExist()
        rule.onNodeWithText("لغو آزمون").performClick()
        waitFor("خروج")
        rule.onNodeWithText("خروج").performClick()
        // Back on the test list (its cards are shown again); nothing was stored.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("لغو آزمون")).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(hasText("● آماده انجام")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun unimplementedTestCannotStart() {
        waitFor("به پایش حرکتی خوش آمدید")
        clickWhenShown("شروع")
        openFromCatalog("باز و بسته کردن دست")
        rule.waitForIdle()
        // Still on the test selection screen; the card is disabled and nothing was started.
        rule.onNodeWithText("باز و بسته کردن دست").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("روش انجام").assertDoesNotExist()
        rule.onNodeWithText("شروع آزمون").assertDoesNotExist()
    }
}
