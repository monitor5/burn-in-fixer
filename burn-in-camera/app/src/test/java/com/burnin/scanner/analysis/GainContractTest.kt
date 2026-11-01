package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class GainContractTest {
    @Test fun displayGammaIsRecoveredFromTwoGrayLevelsAndRejectedWhenImplausible() {
        val ratio = Math.pow(179.0 / 64.0, 2.2).toFloat()
        assertEquals(2.2f, Analyzer.estimateDisplayGamma(ratio, 1f, 179, 64)!!, 1e-4f)
        assertEquals(1.9f, Analyzer.estimateDisplayGamma(Math.pow(179.0 / 64.0, 1.9).toFloat() * 3f, 3f, 179, 64)!!, 1e-4f)
        assertNull(Analyzer.estimateDisplayGamma(1.05f, 1f, 179, 64))          // γ≈0.05: 저휘도 미광 등
        assertNull(Analyzer.estimateDisplayGamma(1000f, 1f, 179, 64))          // γ≈6.7
        assertNull(Analyzer.estimateDisplayGamma(Float.NaN, 1f, 179, 64))
        assertNull(Analyzer.estimateDisplayGamma(1f, 0f, 179, 64))
        assertNull(Analyzer.estimateDisplayGamma(2f, 1f, 64, 179))
    }

    @Test fun untrustedCellsRemainUncorrectedAfterBothBlurPasses() {
        val signal = FloatArray(25) { if (it < 3) .5f else 1f }
        val confidence = FloatArray(25) { 1f }.also { it[12] = 0f }
        val gain = Analyzer.gainGrid(signal, 5, 5, .1f, confidence)
        assertEquals(1f, gain[12], 0f)
        assertTrue(gain[11] < .99f)
        assertTrue(gain.all { it in .9f..1f })
    }

    @Test fun refinementPreservesPriorGainAtExcludedCells() {
        val old = FloatArray(25) { 1f }.also { it[12] = .96f }
        val confidence = FloatArray(25) { 1f }.also { it[12] = 0f }
        val next = Analyzer.refineGain(old, FloatArray(25) { 1f }, .5f, .3f, 5, 5, .1f, confidence)
        assertEquals(.96f, next[12], 0f)
        assertTrue(next[11] < .99f)
        assertEquals(1f, old[11], 0f)
    }

    @Test fun exactUnsmoothedGainAndDampedRefinement() {
        val signal = floatArrayOf(.5f, .55f, 1f)
        assertArrayEquals(floatArrayOf(1f, .5f/.55f, .9f), Analyzer.gainGrid(signal, 3, 1, .1f, smoothRadius=0), 1e-6f)
        val next = Analyzer.refineGain(floatArrayOf(.95f), floatArrayOf(.8f), .6f, .3f, 1, 1, .2f, smoothRadius=0)
        assertEquals((.95 * Math.pow(.6/.8, .3)).toFloat(), next[0], 1e-6f)
    }

    @Test fun zeroAttenuationAndZeroSignalOutlierAreFinite() {
        val signal = floatArrayOf(0f, 1f, 1f)
        assertArrayEquals(FloatArray(3) { 1f }, Analyzer.gainGrid(signal, 3, 1, 0f), 0f)
        assertTrue(Analyzer.gainGrid(signal, 3, 1, .1f).all { it.isFinite() && it in .9f..1f })
    }

    @Test fun mixingClampsWeightsAndDoesNotMutateInputs() {
        val a = floatArrayOf(1f, .9f); val b = floatArrayOf(.9f, 1f)
        assertArrayEquals(floatArrayOf(.975f, .925f), Analyzer.mixGainGrids(a,b,.25f,.1f), 1e-6f)
        assertArrayEquals(a, Analyzer.mixGainGrids(a,b,-1f,.1f), 0f)
        assertArrayEquals(b, Analyzer.mixGainGrids(a,b,2f,.1f), 0f)
        assertArrayEquals(floatArrayOf(1f,.9f), a, 0f)
        assertArrayEquals(floatArrayOf(.95f,.95f), Analyzer.averageGainGrids(listOf(a,b),.1f), 1e-6f)
        assertNull(Analyzer.averageGainGrids(emptyList(),.1f))
    }

    @Test fun malformedShapesAndNumericParametersFailBeforeProducingAMap() {
        fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }
        rejects { Analyzer.gainGrid(FloatArray(5) { 1f },2,2,.1f) }
        rejects { Analyzer.gainGrid(floatArrayOf(1f),0,1,.1f) }
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            rejects { Analyzer.gainGrid(floatArrayOf(1f),1,1,v) }
            rejects { Analyzer.gainGrid(floatArrayOf(1f),1,1,.1f,floatArrayOf(v)) }
        }
        rejects { Analyzer.refineGain(floatArrayOf(1f),floatArrayOf(),1f,.3f,1,1,.1f) }
        rejects { Analyzer.refineGain(floatArrayOf(1f),floatArrayOf(1f),Float.NaN,.3f,1,1,.1f) }
        rejects { Analyzer.mixGainGrids(floatArrayOf(1f),floatArrayOf(1f),Float.NaN,.1f) }
        rejects { Analyzer.averageGainGrids(listOf(floatArrayOf(1f),floatArrayOf()),.1f) }
    }
    @Test fun persistentMaskCannotBeBypassedByBrightnessOrRgbMixing() {
        val confidence=floatArrayOf(0f,.5f,1f)
        val mixed=Analyzer.mixGainGrids(FloatArray(3){1f},FloatArray(3){.9f},.8f,.1f)
        val limited=Analyzer.limitGainByConfidence(mixed,confidence,.1f)
        // 신뢰도 0은 영구 마스크(감쇠 금지), 0.3 이상은 상한을 다 쓴다
        assertArrayEquals(floatArrayOf(1f,.92f,.92f),limited,1e-6f)
        assertEquals(1f,Analyzer.limitGainByConfidence(FloatArray(1){.92f},floatArrayOf(.05f),.1f)[0],0f)
        val next=Analyzer.refineGain(limited,FloatArray(3){1f},.5f,.3f,3,1,.1f)
        assertEquals(1f,Analyzer.limitGainByConfidence(next,confidence,.1f)[0],0f)
        assertArrayEquals(floatArrayOf(.92f,.92f,.92f),mixed,1e-6f)
    }

}
