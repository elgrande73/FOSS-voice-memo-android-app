package io.github.elgrande73.voicememo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Renders the adaptive launcher icon layers and checks key colours of the artwork. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LauncherIconTest {

    private val size = 432 // 108 dp viewport at 4x

    private fun renderLayers(vararg drawableIds: Int): Bitmap {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (id in drawableIds) {
            ContextCompat.getDrawable(context, id)!!.apply {
                setBounds(0, 0, size, size)
                draw(canvas)
            }
        }
        return bitmap
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 40) {
        val close = abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("Expected ~#%06X but was #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF), close)
    }

    private fun at(x: Float, y: Float) = ((x / 108f) * size).toInt() to ((y / 108f) * size).toInt()

    @Test
    fun adaptiveIconLayersRenderTheHappyMicrophone() {
        val icon = renderLayers(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)
        File("build/outputs/icon-preview").apply { mkdirs() }.resolve("adaptive_icon.png").outputStream().use {
            icon.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        fun pixel(x: Float, y: Float) = at(x, y).let { (px, py) -> icon.getPixel(px, py) }
        assertColorNear(Color.parseColor("#FFD23F"), pixel(54f, 52f)) // yellow capsule
        assertColorNear(Color.parseColor("#3A1460"), pixel(49.5f, 38f)) // left eye
        assertColorNear(Color.parseColor("#2EE6C5"), pixel(34f, 52f)) // teal waveform bar
        assertColorNear(Color.parseColor("#FF3B5C"), pixel(68.5f, 27.5f)) // recording dot
        assertColorNear(Color.parseColor("#FFFFFF"), pixel(54f, 67f)) // stand
        assertColorNear(Color.parseColor("#8A40F3"), pixel(20f, 20f), tolerance = 60) // violet gradient corner
    }

    @Test
    fun monochromeLayerIsOpaqueOnShapesAndTransparentElsewhere() {
        val mono = renderLayers(R.drawable.ic_launcher_monochrome)
        fun alpha(x: Float, y: Float) = at(x, y).let { (px, py) -> Color.alpha(mono.getPixel(px, py)) }
        assertEquals(255, alpha(54f, 52f)) // capsule body
        assertEquals(0, alpha(49.5f, 38f)) // eye cut-out
        assertEquals(0, alpha(20f, 20f)) // background
    }
}
