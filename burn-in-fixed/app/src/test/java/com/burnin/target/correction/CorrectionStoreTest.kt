package com.burnin.target.correction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.json.JSONObject
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
class CorrectionStoreTest {
    private lateinit var context:Context
    private var w=0;private var h=0
    @Before fun setup() { context=RuntimeEnvironment.getApplication();File(context.filesDir,"profiles").deleteRecursively();val size=CorrectionStore.realScreenSize(context);w=size.x;h=size.y }
    private fun png(color:Int,width:Int=w,height:Int=h):ByteArray {
        val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);b.eraseColor(color)
        val bytes=ByteArrayOutputStream();check(b.compress(Bitmap.CompressFormat.PNG,100,bytes));b.recycle();return bytes.toByteArray()
    }
    private fun apply(bytes:ByteArray=png(Color.WHITE),checksum:String="",att:Double=.1,strength:Int=100,rgb:ByteArray?=null):String? =
        CorrectionStore.applyFromBase64(context,w,h,att,strength,checksum,Base64.encodeToString(bytes,Base64.NO_WRAP),"test",
            rgb?.let{CorrectionStore.md5(it)}.orEmpty(),rgb?.let{Base64.encodeToString(it,Base64.NO_WRAP)})
    @Test fun bakesAlphaAndRoundTripsBothMapsWithComputedChecksums() {
        val alpha=png(Color.WHITE);val rgb=png(Color.RED)
        assertNull(apply(alpha,rgb=rgb))
        assertEquals(26,Color.alpha(CorrectionStore.bakedBitmap!!.getPixel(0,0)))
        assertEquals(0,Color.red(CorrectionStore.bakedBitmap!!.getPixel(0,0)))
        assertEquals(Color.RED,CorrectionStore.rgbAttenuationBitmap!!.getPixel(0,0))
        assertEquals(CorrectionStore.md5(alpha),CorrectionStore.meta!!.checksumMd5)
        assertTrue(CorrectionStore.loadFromDisk(context))
        assertEquals(Color.RED,CorrectionStore.rgbAttenuationBitmap!!.getPixel(0,0))
    }
    @Test fun rejectionLeavesMemoryAndDiskUntouched() {
        assertNull(apply());val prior=CorrectionStore.bakedBitmap;val file=File(context.filesDir,"profiles/current/metadata.json");val bytes=file.readBytes()
        assertNotNull(apply(checksum="bad"));assertNotNull(apply(att=Double.NaN));assertNotNull(apply(strength=101))
        assertNotNull(apply(bytes=byteArrayOf(1,2)));assertNotNull(apply(bytes=png(Color.WHITE,w+1,h)))
        assertSame(prior,CorrectionStore.bakedBitmap);assertFalse(prior!!.isRecycled);assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun reloadRejectsTamperedChecksumAndMetadata() {
        assertNull(apply());val dir=File(context.filesDir,"profiles/current")
        File(dir,"correction_alpha.png").writeBytes(png(Color.BLACK))
        assertFalse(CorrectionStore.loadFromDisk(context))
        assertNull(apply());val m=JSONObject(File(dir,"metadata.json").readText()).put("width",w+1)
        File(dir,"metadata.json").writeText(m.toString());assertFalse(CorrectionStore.loadFromDisk(context))
    }
    @Test fun removingRgbDoesNotLeaveAnObsoleteMap() {
        assertNull(apply(rgb=png(Color.RED)));val old=CorrectionStore.rgbAttenuationBitmap!!
        assertNull(apply());assertNull(CorrectionStore.rgbAttenuationBitmap);assertFalse(old.isRecycled)
        assertFalse(File(context.filesDir,"profiles/current/correction_rgb.png").exists())
        assertTrue(CorrectionStore.loadFromDisk(context));assertNull(CorrectionStore.rgbAttenuationBitmap)
    }
    @Test fun missingRgbDoesNotReplaceActiveBitmap() {
        assertNull(apply(rgb=png(Color.RED)));val old=CorrectionStore.bakedBitmap
        File(context.filesDir,"profiles/current/correction_rgb.png").delete()
        assertFalse(CorrectionStore.loadFromDisk(context));assertSame(old,CorrectionStore.bakedBitmap)
    }
}
