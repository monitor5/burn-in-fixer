package com.burnin.target.pattern

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PatternViewTest {
    private fun bitmap(color:Int)=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
    private fun view()=PatternView(RuntimeEnvironment.getApplication()).apply{layout(0,0,4,4);spec=PatternSpec.parse("white")!!}
    private fun pixel(v:PatternView):Int {val out=bitmap(Color.TRANSPARENT);v.draw(Canvas(out));val p=out.getPixel(2,2);out.recycle();return p}
    @Test fun disabledAndZeroStrengthBypassAttenuation() {
        val v=view();val rgb=bitmap(Color.RED);v.correctionRgbBitmap=rgb;v.correctionMaxAttenuation=.1
        assertEquals(Color.WHITE,pixel(v));v.correctionEnabled=true;v.strengthPct=0;assertEquals(Color.WHITE,pixel(v));rgb.recycle()
    }
    @Test fun rgbAndWhiteBalanceMultiplyOnceWithoutDoubleAlpha() {
        val v=view();val rgb=bitmap(Color.RED);val wb=bitmap(Color.GREEN);val alpha=bitmap(Color.argb(26,0,0,0))
        v.correctionRgbBitmap=rgb;v.whiteBalanceRgbBitmap=wb;v.correctionBitmap=alpha
        v.correctionMaxAttenuation=.1;v.whiteBalanceMaxAttenuation=.2;v.correctionEnabled=true
        val p=pixel(v);assertEquals(230,Color.red(p));assertEquals(204,Color.green(p));assertEquals(255,Color.blue(p))
        rgb.recycle();wb.recycle();alpha.recycle()
    }
    @Test fun cacheInvalidatesOnMapStrengthPatternAndSizeChanges() {
        val v=view();val red=bitmap(Color.RED);val blue=bitmap(Color.BLUE);v.correctionEnabled=true;v.correctionMaxAttenuation=.2;v.correctionRgbBitmap=red
        assertEquals(204,Color.red(pixel(v)))
        v.correctionRgbBitmap=blue;assertEquals(255,Color.red(pixel(v)));assertEquals(204,Color.blue(pixel(v)))
        v.strengthPct=50;assertEquals(230,Color.blue(pixel(v)))
        v.spec=PatternSpec.parse("black")!!;assertEquals(Color.BLACK,pixel(v));v.layout(0,0,2,2)
        red.recycle();blue.recycle()
    }
    @Test fun alphaOnlyAndWhiteBalanceOnlyPathsHaveExpectedPixels() {
        val v=view();val alpha=bitmap(Color.argb(26,0,0,0));v.correctionBitmap=alpha;v.correctionEnabled=true
        assertEquals(229,Color.red(pixel(v)))
        val wb=bitmap(Color.BLUE);v.whiteBalanceRgbBitmap=wb;v.whiteBalanceMaxAttenuation=.2
        val p=pixel(v);assertEquals(229,Color.red(p));assertTrue(Color.blue(p) in 182..184)
        alpha.recycle();wb.recycle()
    }
}
