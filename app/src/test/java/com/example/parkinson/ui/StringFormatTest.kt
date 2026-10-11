package com.example.parkinson.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every string resource that contains a format specifier must format with the arguments the screens pass. A bare
 * "%1" (no "$" and no conversion letter) is not a valid format and throws at render time, so it crashes the screen.
 */
@RunWith(RobolectricTestRunner::class)
class StringFormatTest {

    @Test
    fun everyStringWithAFormatSpecifierFormatsWithItsArguments() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failures = mutableListOf<String>()
        for (field in R.string::class.java.fields) {
            val id = field.getInt(null)
            if (!context.getString(id).contains('%')) continue
            try {
                // Integer arguments: "%d" needs them, and "%s" accepts them. Four covers the longest call sites.
                context.getString(id, 1, 2, 3, 4)
            } catch (e: Exception) {
                failures += "${field.name}: ${e.javaClass.simpleName}"
            }
        }
        assertTrue("Strings that do not format: $failures", failures.isEmpty())
    }
}
