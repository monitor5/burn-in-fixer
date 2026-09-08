package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class ConfidenceContractTest {
    @Test fun clippingAndDarkSignalAreRejectedWhileCleanSignalIsTrusted() {
        val result = Analyzer.confidenceGrid(floatArrayOf(0f,.5f,.92f,.995f,1f),null,.1f)
        assertArrayEquals(floatArrayOf(0f,1f,1f,0f,0f),result,1e-6f)
    }
    @Test fun strayLightRampHasAnIndependentMidpoint() {
        // b/s=.065 is halfway from .03 to .10; SNR confidence=(1/.065-4)/16.
        val actual = Analyzer.confidenceGrid(floatArrayOf(.5f),floatArrayOf(.0325f),.1f)[0]
        val expected = .5f * ((.5f/(.0325f+1e-5f)-4f)/16f)
        assertEquals(expected,actual,1e-6f)
        assertEquals(0f,Analyzer.confidenceGrid(floatArrayOf(.5f),floatArrayOf(.05f),.1f)[0],1e-6f)
    }
    @Test fun temporalRangeRejectsGlobalFlickerAndPreservesStableFrames() {
        fun confidence(a: Float,b: Float) = Analyzer.temporalStabilityConfidence(
            listOf(FloatArray(9){a},FloatArray(9){b}),3,3,5,2,.02f,.06f)
        assertArrayEquals(FloatArray(10){1f},confidence(1f,1f),1e-6f)
        assertArrayEquals(FloatArray(10),confidence(.9f,1.1f),1e-6f)
        assertArrayEquals(FloatArray(10){.5f},confidence(.98f,1.02f),3e-6f)
    }
    @Test fun temporalLocalizedFlickerDoesNotInvalidateDistantCells() {
        val a=FloatArray(225){1f}; val b=a.copyOf()
        for(y in 5..9) for(x in 5..9) b[y*15+x]=1.5f
        val c=Analyzer.temporalStabilityConfidence(listOf(a,b),15,15,15,15,.02f,.06f)
        assertEquals(0f,c[7*15+7],1e-6f); assertEquals(1f,c[0],1e-6f)
        assertTrue(c.all{it in 0f..1f})
    }
    @Test fun combinesConfidenceWithoutLosingAnExclusion() {
        val a=floatArrayOf(.5f,0f,1f);val b=floatArrayOf(.4f,1f,.2f)
        assertArrayEquals(floatArrayOf(.2f,0f,.2f),Analyzer.combineConfidence(a,b),1e-6f)
        assertEquals(.5f,Analyzer.meanConfidence(a),1e-6f)
        assertArrayEquals(floatArrayOf(.5f,0f,1f),a,0f)
    }
    @Test fun regionsUseFourNeighborsThresholdAndWorstFirstOrdering() {
        val c=floatArrayOf(.1f,1f,.2f, 1f,.3f,.2f, 1f,1f,1f)
        val regions=Analyzer.lowConfidenceRegions(c,3,3,threshold=.3f,minArea=1,maxRegions=2)
        assertEquals(2,regions.size);assertEquals(1,regions[0].area);assertEquals(2,regions[1].area)
        assertEquals(2,regions[1].minX);assertEquals(1,regions[1].maxY)
        assertEquals(1,Analyzer.lowConfidenceRegions(c,3,3,threshold=.3f,minArea=2).size)
        val raised=Analyzer.raiseConfidenceFloor(c,3,3,regions[0],.7f)
        assertEquals(.7f,raised[0],0f);assertEquals(.1f,c[0],0f);assertEquals(.3f,raised[4],0f)
    }
    @Test fun emptyAndSingleFrameFallbacksStillValidateShape() {
        assertArrayEquals(FloatArray(4){1f},Analyzer.temporalStabilityConfidence(emptyList(),2,2,2,2,.02f,.06f),0f)
        assertThrows(IllegalArgumentException::class.java) {
            Analyzer.temporalStabilityConfidence(listOf(floatArrayOf(1f)),2,2,2,2,.02f,.06f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Analyzer.crossAgreementConfidence(listOf(floatArrayOf(1f)),2,2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Analyzer.temporalStabilityConfidence(emptyList(),2,2,0,2,.02f,.06f)
        }
    }
    @Test fun invalidConfidenceAndThresholdsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { Analyzer.confidenceGrid(floatArrayOf(Float.NaN),null,.1f) }
        assertThrows(IllegalArgumentException::class.java) { Analyzer.combineConfidence(floatArrayOf(Float.NaN),floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { Analyzer.temporalStabilityConfidence(emptyList(),1,1,1,1,.06f,.02f) }
    }
}
