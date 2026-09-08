package com.burnin.target.correction

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w4dp-h3dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhiteBalanceStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun setup() { File(context.filesDir,"profiles").deleteRecursively() }
    private fun apply(gain:Double=.9,checksum:String=""):String? {
        val size=CorrectionStore.realScreenSize(context)
        val b=Bitmap.createBitmap(size.x,size.y,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED)
        val out=ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle()
        return WhiteBalanceStore.applyFromBase64(context,size.x,size.y,.1,checksum,Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP),"test",gain,1.0,1.0)
    }
    @Test fun validMapRoundTripsWithoutRewritingCreationTime() {
        assertNull(apply());val before=File(context.filesDir,"profiles/white_balance/metadata.json").readBytes()
        assertTrue(WhiteBalanceStore.loadFromDisk(context));assertEquals(.9,WhiteBalanceStore.meta!!.redGain,0.0)
        assertEquals(Color.RED,WhiteBalanceStore.rgbAttenuationBitmap!!.getPixel(0,0))
        assertArrayEquals(before,File(context.filesDir,"profiles/white_balance/metadata.json").readBytes())
    }
    @Test fun invalidGainAndChecksumLeaveCurrentStateUntouched() {
        assertNull(apply());val old=WhiteBalanceStore.rgbAttenuationBitmap
        for(g in listOf(Double.NaN,Double.POSITIVE_INFINITY,-.1,1.1)) assertNotNull(apply(gain=g))
        assertNotNull(apply(checksum="bad"));assertSame(old,WhiteBalanceStore.rgbAttenuationBitmap);assertFalse(old!!.isRecycled)
    }
    @Test fun tamperedDiskMapIsRejectedAndClearPreventsResurrection() {
        assertNull(apply());File(context.filesDir,"profiles/white_balance/white_balance_rgb.png").writeBytes(byteArrayOf(1))
        assertFalse(WhiteBalanceStore.loadFromDisk(context))
        WhiteBalanceStore.clear(context);assertNull(WhiteBalanceStore.meta);assertNull(WhiteBalanceStore.rgbAttenuationBitmap)
        assertFalse(WhiteBalanceStore.loadFromDisk(context))
    }
}
