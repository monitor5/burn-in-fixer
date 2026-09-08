package com.burnin.scanner.analysis

import android.graphics.Bitmap
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
class DiagnosticImageTest {
    @Test fun screenCropPreservesGradientDirectionAndClampsBorderRegion() {
        val image=GrayImage(100,100,FloatArray(10000){(it%100)/99f})
        val corners=arrayOf(Vec2(0f,0f),Vec2(100f,0f),Vec2(100f,100f),Vec2(0f,100f))
        val h=Homography.from4Points(corners,corners)!!
        val crop=Analyzer.screenCropBitmap(image,h,100,100,10,10,Analyzer.GridRegion(0,0,2,2,9,0f,.1f),32)
        assertTrue(crop.width in 16..32);assertTrue(crop.height in 16..32)
        assertTrue(Color.red(crop.getPixel(0,crop.height/2))<Color.red(crop.getPixel(crop.width-1,crop.height/2)))
        crop.recycle()
    }
    @Test fun contactSheetKeepsCameraColumnsAndDoesNotRecycleInputs() {
        val red=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        val blue=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val sheet=Analyzer.contactSheetBitmap(listOf("main" to red,"tele" to blue))
        assertEquals(40,sheet.width);assertEquals(50,sheet.height)
        assertEquals(Color.RED,sheet.getPixel(10,40));assertEquals(Color.BLUE,sheet.getPixel(30,40))
        assertFalse(red.isRecycled);assertFalse(blue.isRecycled)
        sheet.recycle();red.recycle();blue.recycle()
        assertThrows(IllegalArgumentException::class.java){Analyzer.contactSheetBitmap(emptyList())}
    }
}
