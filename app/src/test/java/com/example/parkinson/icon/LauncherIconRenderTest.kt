package com.example.parkinson.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Renders the adaptive launcher icon with real Android graphics (Robolectric NATIVE mode).
 *
 * Always checks that the icon is an adaptive icon with a monochrome layer, that the foreground
 * stays inside the 66 dp safe zone, and that every layer draws. When the environment variable
 * ICON_OUT is set, it also writes:
 *  - the legacy mipmap-*dpi webp icons (pre-Android 8 launchers): rounded square + circle;
 *  - preview sheets (launcher masks, small sizes, light and dark wallpapers) for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LauncherIconRenderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun layer(id: Int): Drawable = requireNotNull(context.getDrawable(id))

    /** Full 108-unit icon square (background + foreground), [size] px. */
    private fun renderLayers(size: Int, monochrome: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (monochrome) {
            canvas.drawColor(Color.rgb(0x2B, 0x2F, 0x33))
            layer(R.drawable.ic_launcher_monochrome).apply { setBounds(0, 0, size, size); setTint(Color.rgb(0xD8, 0xE6, 0xF3)) }.draw(canvas)
        } else {
            layer(R.drawable.ic_launcher_background).apply { setBounds(0, 0, size, size) }.draw(canvas)
            layer(R.drawable.ic_launcher_foreground).apply { setBounds(0, 0, size, size) }.draw(canvas)
        }
        return bmp
    }

    private enum class Mask { CIRCLE, SQUIRCLE, ROUNDED_SQUARE, TEARDROP }

    /**
     * What a launcher shows: the central 72/108 of the layers, clipped to [mask], [size] px.
     */
    private fun launcherIcon(size: Int, mask: Mask, monochrome: Boolean = false): Bitmap {
        val full = renderLayers(size * 108 / 72, monochrome)
        val inset = (full.width - size) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = size.toFloat()
        val path = Path()
        when (mask) {
            Mask.CIRCLE -> path.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW)
            Mask.ROUNDED_SQUARE -> path.addRoundRect(RectF(0f, 0f, s, s), s * 0.18f, s * 0.18f, Path.Direction.CW)
            Mask.SQUIRCLE -> {
                // Superellipse approximation (n = 5).
                val n = 5.0
                for (i in 0..360) {
                    val a = Math.toRadians(i.toDouble())
                    val c = Math.cos(a)
                    val si = Math.sin(a)
                    val x = s / 2 + s / 2 * Math.signum(c) * Math.pow(Math.abs(c), 2 / n)
                    val y = s / 2 + s / 2 * Math.signum(si) * Math.pow(Math.abs(si), 2 / n)
                    if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                }
                path.close()
            }
            Mask.TEARDROP -> {
                path.addRoundRect(
                    RectF(0f, 0f, s, s),
                    floatArrayOf(s / 2, s / 2, s / 2, s / 2, s * 0.1f, s * 0.1f, s / 2, s / 2),
                    Path.Direction.CW
                )
            }
        }
        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(full, -inset.toFloat(), -inset.toFloat(), paint)
        return out
    }

    private fun legacySquare(size: Int): Bitmap {
        // Legacy icons: 48 dp with ~2 dp transparent padding, rounded square.
        val pad = size / 24
        val inner = launcherIcon(size - 2 * pad, Mask.ROUNDED_SQUARE)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(inner, pad.toFloat(), pad.toFloat(), null)
        }
    }

    private fun legacyRound(size: Int): Bitmap {
        val pad = size / 24
        val inner = launcherIcon(size - 2 * pad, Mask.CIRCLE)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(inner, pad.toFloat(), pad.toFloat(), null)
        }
    }

    @Test
    fun launcherIconIsAdaptiveWithMonochromeLayer() {
        val icon = context.getDrawable(R.mipmap.ic_launcher)
        assertTrue("adaptive icon on API 26+", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull("themed icon support (Android 13+)", icon.monochrome)
        assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }

    @Test
    fun foregroundStaysInsideTheSafeZone() {
        // Draw only the foreground at 1 px per unit x 4 and look for opaque pixels outside r = 33.
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        layer(R.drawable.ic_launcher_foreground).apply { setBounds(0, 0, size, size) }.draw(Canvas(bmp))
        val scale = size / 108f
        var drawn = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(bmp.getPixel(x, y)) < 40) continue
            drawn++
            val r = hypot(x / scale - 54.0, y / scale - 54.0)
            assertTrue("pixel at ($x,$y) r=$r outside the 66 dp safe zone", r <= 33.0)
        }
        assertTrue("foreground draws something", drawn > 1_000)
    }

    @Test
    fun backgroundIsOpaqueAndMonochromeDraws() {
        val bg = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        layer(R.drawable.ic_launcher_background).apply { setBounds(0, 0, 108, 108) }.draw(Canvas(bg))
        listOf(0 to 0, 107 to 107, 54 to 54, 0 to 107).forEach { (x, y) -> assertEquals(255, Color.alpha(bg.getPixel(x, y))) }

        val mono = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        layer(R.drawable.ic_launcher_monochrome).apply { setBounds(0, 0, 108, 108) }.draw(Canvas(mono))
        var opaque = 0
        for (y in 0 until 108) for (x in 0 until 108) if (Color.alpha(mono.getPixel(x, y)) > 128) opaque++
        assertTrue("monochrome draws", opaque > 200)
    }

    @Test
    fun writeAssetsWhenRequested() {
        val out = System.getenv("ICON_OUT") ?: return
        val dir = File(out).apply { mkdirs() }
        fun save(b: Bitmap, name: String, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG) =
            File(dir, name).outputStream().use { b.compress(format, 100, it) }

        // Legacy launcher icons, 48 dp per density.
        mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192).forEach { (density, px) ->
            File(dir, "mipmap-$density").mkdirs()
            save(legacySquare(px), "mipmap-$density/ic_launcher.webp", Bitmap.CompressFormat.WEBP_LOSSLESS)
            save(legacyRound(px), "mipmap-$density/ic_launcher_round.webp", Bitmap.CompressFormat.WEBP_LOSSLESS)
        }
        // Play Store style 512 px.
        save(launcherIcon(512, Mask.ROUNDED_SQUARE), "ic_launcher_512.png")

        // Preview sheet: masks at 192 px, small sizes, light / dark wallpapers, themed icon.
        val sheet = Bitmap.createBitmap(1040, 760, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(0xF4, 0xF6, 0xF8))
        val darkPaint = Paint().apply { color = Color.rgb(0x12, 0x14, 0x18) }
        c.drawRect(0f, 380f, 1040f, 760f, darkPaint)
        val masks = Mask.entries
        for ((row, top) in listOf(40f, 420f).withIndex()) {
            masks.forEachIndexed { i, m -> c.drawBitmap(launcherIcon(192, m, monochrome = row == 1 && i == 3), 40f + i * 240f, top, null) }
            var x = 40f
            listOf(96, 72, 48, 36, 24).forEach { px ->
                c.drawBitmap(launcherIcon(px, Mask.CIRCLE), x, top + 220f, null)
                x += px + 40f
            }
            c.drawBitmap(legacySquare(96), 640f, top + 220f, null)
            c.drawBitmap(legacyRound(96), 780f, top + 220f, null)
        }
        save(sheet, "preview.png")
    }
}
