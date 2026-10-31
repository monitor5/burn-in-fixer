package com.burnin.target.correction

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorProfileStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        File(context.filesDir, "profiles").deleteRecursively()
        ColorProfileStore.clear(context)
    }

    private fun lutBytes(redHalf: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun put(q: Int) { out.write(q shr 8 and 0xFF); out.write(q and 0xFF) }
        for (r in 0..1) for (g in 0..1) for (b in 0..1) for (v in intArrayOf(r, g, b)) put(v * 65535)
        for (c in 0 until 3) for (i in 0 until 256) put(Math.round(i * 65535f / 255 / if (redHalf && c == 0) 2 else 1))
        return out.toByteArray()
    }

    private fun apply(bytes: ByteArray = lutBytes(), checksum: String = CorrectionStore.md5(bytes), format: String = ColorLut.FORMAT, scale: Double = 0.9) =
        ColorProfileStore.applyFromBase64(context, 2, format, checksum, Base64.encodeToString(bytes, Base64.NO_WRAP), "scanner", "Tab S2 A", scale)

    @Test fun profileRoundTripsAndToggleSurvivesReload() {
        assertNull(apply(lutBytes(redHalf = true)))
        assertEquals(0xFF80FFFF.toInt(), ColorProfileStore.activeLut!!.transformArgb(0xFFFFFFFF.toInt()))
        assertNull(ColorProfileStore.setEnabled(context, false))
        assertNull(ColorProfileStore.activeLut)
        assertTrue(ColorProfileStore.loadFromDisk(context))
        assertFalse(ColorProfileStore.meta!!.enabled)
        assertEquals("Tab S2 A", ColorProfileStore.meta!!.referenceDevice)
        assertNull(ColorProfileStore.setEnabled(context, true))
        assertNotNull(ColorProfileStore.activeLut)
    }

    @Test fun invalidProfilesLeaveCurrentStateUntouched() {
        assertNull(apply())
        val old = ColorProfileStore.lut
        assertNotNull(apply(checksum = "bad"))
        assertNotNull(apply(format = "other"))
        assertNotNull(apply(scale = 1.5))
        assertNotNull(apply(bytes = lutBytes().copyOf(10), checksum = ""))
        assertSame(old, ColorProfileStore.lut)
    }

    @Test fun clearPreventsResurrectionAndToggleNeedsProfile() {
        assertNull(apply())
        ColorProfileStore.clear(context)
        assertFalse(ColorProfileStore.loadFromDisk(context))
        assertNull(ColorProfileStore.meta)
        assertNotNull(ColorProfileStore.setEnabled(context, true))
    }
}
