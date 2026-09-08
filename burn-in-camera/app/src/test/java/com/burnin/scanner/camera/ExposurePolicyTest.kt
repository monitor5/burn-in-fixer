package com.burnin.scanner.camera

import org.junit.Assert.*
import org.junit.Test

class ExposurePolicyTest {
    @Test fun roundsUpToRefreshPeriodAndCompensatesIso() {
        assertEquals(20_000_000L to 100,ExposurePolicy.quantize(10_000_000,200,50f,null,null))
        assertEquals(40_000_000L to 150,ExposurePolicy.quantize(30_000_000,200,50f,null,null))
    }
    @Test fun usesFloorWhenCeilingExceedsExposureRange() {
        assertEquals(20_000_000L to 300,ExposurePolicy.quantize(30_000_000,200,50f,1L..30_000_000L,100..400))
    }
    @Test fun preservesExposureWhenIsoCannotPreserveBrightness() {
        assertEquals(10_000_000L to 100,ExposurePolicy.quantize(10_000_000,100,50f,null,100..800))
    }
    @Test fun alreadyQuantizedAndFractionalRefreshRemainValid() {
        assertEquals(20_000_000L to 123,ExposurePolicy.quantize(20_000_000,123,50f,null,null))
        val period=Math.round(1e9/59.94f);val result=ExposurePolicy.quantize(10_000_000,200,59.94f,null,null)
        assertEquals(0L,result.first%period);assertTrue(result.second.toDouble()*result.first/(200.0*10_000_000) in .85..1.15)
    }
    @Test fun invalidInputsAndExtremeExposureCannotOverflow() {
        for(hz in listOf(0f,Float.NaN,Float.POSITIVE_INFINITY)) assertEquals(10L to 100,ExposurePolicy.quantize(10,100,hz,null,null))
        assertEquals(0L to 100,ExposurePolicy.quantize(0,100,60f,null,null))
        val result=ExposurePolicy.quantize(Long.MAX_VALUE,Int.MAX_VALUE,60f,null,null)
        assertTrue(result.first>0);assertTrue(result.second>0)
    }
}
