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
    private fun view()=PatternView(RuntimeEnvironment.getApplication()).apply{gpuEnabled=false;layout(0,0,4,4);spec=PatternSpec.parse("white")!!}
    private fun pixel(v:PatternView):Int {val out=bitmap(Color.TRANSPARENT);v.draw(Canvas(out));val p=out.getPixel(2,2);out.recycle();return p}
    /** 단색 보정 경로는 dither되므로 픽셀 하나가 아니라 4×4 평균을 본다. */
    private fun mean(v:PatternView,shift:Int):Double {
        val out=bitmap(Color.TRANSPARENT);v.draw(Canvas(out))
        var s=0.0;for(y in 0 until 4) for(x in 0 until 4) s+=(out.getPixel(x,y) shr shift) and 0xFF
        out.recycle();return s/16
    }
    private val R=16;private val G=8;private val B=0
    /** 휘도 gain을 γ 2.2 패널 코드로 바꾼 기대값 */
    private fun code(base:Int,lumGain:Double)=base*Math.pow(lumGain,1/2.2)
    @Test fun disabledAndZeroStrengthBypassAttenuation() {
        val v=view();val rgb=bitmap(Color.RED);v.correctionRgbBitmap=rgb;v.correctionMaxAttenuation=.1
        assertEquals(Color.WHITE,pixel(v));v.correctionEnabled=true;v.strengthPct=0;assertEquals(Color.WHITE,pixel(v));rgb.recycle()
    }
    @Test fun rgbAndWhiteBalanceMultiplyOnceWithoutDoubleAlpha() {
        val v=view();val rgb=bitmap(Color.RED);val wb=bitmap(Color.GREEN);val alpha=bitmap(Color.argb(26,0,0,0))
        v.correctionRgbBitmap=rgb;v.whiteBalanceRgbBitmap=wb;v.correctionBitmap=alpha
        v.correctionMaxAttenuation=.1;v.whiteBalanceMaxAttenuation=.2;v.correctionEnabled=true
        assertEquals(code(255,.9),mean(v,R),.5);assertEquals(code(255,.8),mean(v,G),.5);assertEquals(255.0,mean(v,B),0.0)
        rgb.recycle();wb.recycle();alpha.recycle()
    }
    @Test fun cacheInvalidatesOnMapStrengthPatternAndSizeChanges() {
        val v=view();val red=bitmap(Color.RED);val blue=bitmap(Color.BLUE);v.correctionEnabled=true;v.correctionMaxAttenuation=.2;v.correctionRgbBitmap=red
        assertEquals(code(255,.8),mean(v,R),.5)
        v.correctionRgbBitmap=blue;assertEquals(255.0,mean(v,R),0.0);assertEquals(code(255,.8),mean(v,B),.5)
        v.strengthPct=50;assertEquals(code(255,.9),mean(v,B),.5)
        v.outputGamma=2.0;assertEquals(255*Math.sqrt(.9),mean(v,B),.5)
        v.spec=PatternSpec.parse("black")!!;assertEquals(Color.BLACK,pixel(v));v.layout(0,0,2,2)
        red.recycle();blue.recycle()
    }
    @Test fun alphaOnlyAndWhiteBalanceOnlyPathsHaveExpectedPixels() {
        val v=view();val alpha=bitmap(Color.argb(26,0,0,0));v.correctionBitmap=alpha;v.correctionEnabled=true
        assertEquals(229,Color.red(pixel(v)))
        val wb=bitmap(Color.BLUE);v.whiteBalanceRgbBitmap=wb;v.whiteBalanceMaxAttenuation=.2
        assertEquals(229.0,mean(v,R),.5);assertEquals(code(255,.8)*(1-26/255.0),mean(v,B),1.0)
        alpha.recycle();wb.recycle()
    }
    @Test fun subStepAttenuationAtLowGraySurvivesAsDither() {
        // gray25 코드 64에서 휘도 1.5% 감쇠는 코드 0.44단계 — 반올림만 하면 사라진다
        val v=view();v.spec=PatternSpec.parse("gray25")!!;val red=bitmap(Color.rgb(96,0,0))
        v.correctionRgbBitmap=red;v.correctionMaxAttenuation=.04;v.correctionEnabled=true
        val expected=code(64,1-96/255.0*.04);assertTrue(expected<63.6)
        assertEquals(expected,mean(v,R),.3);red.recycle()
    }
    @Test fun sparsePatternIsPixelExactWithoutAntialiasing() {
        val v=PatternView(RuntimeEnvironment.getApplication()).apply{gpuEnabled=false;layout(0,0,8,8);spec=PatternSpec.parse("sparse70_b1_p4_x1_y2")!!}
        val out=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);v.draw(Canvas(out))
        for (y in 0 until 8) for (x in 0 until 8) {
            val lit=(x-1)>=0&&(y-2)>=0&&(x-1)%4==0&&(y-2)%4==0
            assertEquals("($x,$y)", if (lit) 0xFFB3B3B3.toInt() else Color.BLACK, out.getPixel(x,y))
        }
        out.recycle()
    }
}
