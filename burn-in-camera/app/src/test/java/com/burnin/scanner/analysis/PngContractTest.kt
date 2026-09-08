package com.burnin.scanner.analysis

import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PngContractTest {
    @Test fun alphaPngHasExactSizeSignatureAndAttenuationCodes() {
        val bytes=Analyzer.toAlphaPng(floatArrayOf(1f,.95f,.9f),3,1,3,1,.1f)
        assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),bytes.take(8).toByteArray())
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        assertEquals(3,bitmap.width);assertEquals(1,bitmap.height)
        for ((x,expected) in listOf(0,128,255).withIndex()) {
            val pixel=bitmap.getPixel(x,0)
            assertEquals(expected,Color.red(pixel));assertEquals(expected,Color.green(pixel));assertEquals(expected,Color.blue(pixel));assertEquals(255,Color.alpha(pixel))
        }
        bitmap.recycle()
    }
    @Test fun rgbPngKeepsChannelsIndependentAndInterpolatesOutputSize() {
        val bytes=Analyzer.toRgbAttenuationPng(floatArrayOf(1f,.9f),floatArrayOf(.9f,1f),floatArrayOf(.95f,.95f),2,1,4,2,.1f)
        val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        assertEquals(4,b.width);assertEquals(2,b.height)
        val left=b.getPixel(0,0);val right=b.getPixel(3,1)
        assertEquals(0,Color.red(left));assertEquals(255,Color.green(left));assertEquals(128,Color.blue(left))
        assertEquals(255,Color.red(right));assertEquals(0,Color.green(right));assertEquals(128,Color.blue(right))
        assertEquals(64,Color.red(b.getPixel(1,0)));assertEquals(191,Color.green(b.getPixel(1,0)))
        b.recycle()
    }
    @Test fun previewUsesLinearToGammaConversion() {
        val bytes=Analyzer.grayImagePng(GrayImage(3,1,floatArrayOf(0f,.25f,1f)))
        val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        assertEquals(0,Color.red(b.getPixel(0,0)));assertEquals(136,Color.red(b.getPixel(1,0)));assertEquals(255,Color.red(b.getPixel(2,0)))
        b.recycle()
    }
    @Test fun signedHeatmapUsesRedForBrightAndBlueForDark() {
        val bytes=Analyzer.deviationHeatmapPng(floatArrayOf(.9f,1f,1.1f),3,1,.1f)
        val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size)!!
        assertEquals(12,b.width);assertEquals(4,b.height)
        assertEquals(Color.BLUE,b.getPixel(1,1));assertEquals(Color.BLACK,b.getPixel(5,1));assertEquals(Color.RED,b.getPixel(9,1))
        b.recycle()
    }

}
