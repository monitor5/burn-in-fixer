package com.burnin.scanner.measure

import com.burnin.scanner.analysis.Analyzer
import org.junit.Assert.*
import org.junit.Test

class MeasurementPolicyTest {
    private fun stats()=Analyzer.stats(floatArrayOf(1f,1f,1f))
    @Test fun passRequiresBothRmsAndTailAtInclusiveBoundaries() {
        val boundary=stats().copy(rmsDev=.015f,p95Dev=.035f)
        assertEquals(1f,MeasurementPolicy.score(boundary,.015f,.035f),0f)
        assertTrue(MeasurementPolicy.score(boundary.copy(rmsDev=.01501f),.015f,.035f)>1f)
        assertTrue(MeasurementPolicy.score(boundary.copy(p95Dev=.03501f),.015f,.035f)>1f)
    }
    @Test fun darknessAndInvalidStatisticsCannotPassAsUniform() {
        for(s in listOf(stats().copy(median=1e-5f),stats().copy(rmsDev=Float.NaN),stats().copy(p95Dev=Float.POSITIVE_INFINITY)))
            assertEquals(Float.POSITIVE_INFINITY,MeasurementPolicy.score(s,.015f,.035f),0f)
    }
    @Test fun improvementIsFiniteAtPerfectBaselineAndPreservesRegressionSign() {
        assertEquals(0.0,MeasurementPolicy.improvement(0f,0f),0.0)
        assertEquals(0.0,MeasurementPolicy.improvement(0f,.1f),0.0)
        assertEquals(.5,MeasurementPolicy.improvement(.2f,.1f),1e-6)
        assertEquals(-1.0,MeasurementPolicy.improvement(.1f,.2f),1e-6)
    }
    @Test fun baselineWinsWhenEveryCandidateIsWorseOrInvalid() {
        val selection=MeasurementPolicy.Selection(floatArrayOf(1f),1f)
        for(score in listOf(2f,1f,Float.NaN,Float.POSITIVE_INFINITY)) assertFalse(selection.consider(floatArrayOf(.9f),score))
        assertTrue(selection.isBaseline);assertEquals(1f,selection.gain[0],0f)
    }
    @Test fun bestCandidateIsCopiedAndNotReplacedByLaterDivergence() {
        val selection=MeasurementPolicy.Selection(floatArrayOf(1f),2f);val candidate=floatArrayOf(.95f)
        assertTrue(selection.consider(candidate,1f));candidate[0]=.5f
        assertFalse(selection.consider(floatArrayOf(.9f),1.5f));assertFalse(selection.isBaseline)
        assertEquals(.95f,selection.gain[0],0f)
    }
    @Test fun movementCheckIncludesEveryCornerAndEuclideanDistance() {
        fun quad(dx:Float,dy:Float)=com.burnin.scanner.analysis.ScreenDetector.Quad(arrayOf(
            com.burnin.scanner.analysis.Vec2(0f,0f),com.burnin.scanner.analysis.Vec2(100f,0f),
            com.burnin.scanner.analysis.Vec2(100f+dx,100f+dy),com.burnin.scanner.analysis.Vec2(0f,100f)))
        assertTrue(MeasurementPolicy.geometryStable(quad(0f,0f),quad(4f,0f)))
        assertFalse(MeasurementPolicy.geometryStable(quad(0f,0f),quad(3f,3f)))
        assertFalse(MeasurementPolicy.geometryStable(quad(0f,0f),quad(Float.NaN,0f)))
    }

}
