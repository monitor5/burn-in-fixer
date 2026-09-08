package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    @Test fun uniformSignalHasZeroDeviation() {
        for (value in listOf(1e-5f, 0.25f, 1f)) {
            val stats = Analyzer.stats(FloatArray(20) { value })
            assertEquals(value, stats.median, 0f)
            assertEquals(value, stats.p10, 0f)
            assertEquals(value, stats.mean, 1e-7f)
            assertEquals(0f, stats.rmsDev, 0f)
            assertEquals(0f, stats.p95Dev, 0f)
            assertEquals(0f, stats.maxDev, 0f)
        }
    }

    @Test fun evenSampleUsesUpperMedianAndFloorIndexedQuantiles() {
        val stats = Analyzer.stats(floatArrayOf(4f, 1f, 3f, 2f))
        assertEquals(3f, stats.median, 0f)
        assertEquals(1f, stats.p10, 0f)
        assertEquals(2.5f, stats.mean, 0f)
        assertEquals(kotlin.math.sqrt(1f / 6f), stats.rmsDev, 1e-6f)
        assertEquals(2f / 3f, stats.p95Dev, 1e-6f)
        assertEquals(2f / 3f, stats.maxDev, 1e-6f)
    }

    @Test fun p95DetectsTailThatMedianAndRmsCanHide() {
        val data = FloatArray(100) { if (it < 6) 1.2f else 1f }
        val stats = Analyzer.stats(data)
        assertEquals(1f, stats.median, 0f)
        assertEquals(1.012f, stats.mean, 1e-6f)
        assertEquals(0.2f, stats.p95Dev, 1e-6f)
        assertEquals(kotlin.math.sqrt(0.0024f), stats.rmsDev, 1e-6f)
    }

    @Test fun p95IndexBoundaryAndMaximumAreDistinct() {
        val four = FloatArray(100) { if (it < 4) 1.5f else 1f }
        val five = FloatArray(100) { if (it < 5) 1.5f else 1f }
        assertEquals(0f, Analyzer.stats(four).p95Dev, 0f)
        assertEquals(0.5f, Analyzer.stats(five).p95Dev, 0f)
        assertEquals(0.5f, Analyzer.stats(four).maxDev, 0f)
    }

    @Test fun p10BoundaryIsIndependentOfMedian() {
        val stats = Analyzer.stats(FloatArray(10) { (it + 1).toFloat() })
        assertEquals(2f, stats.p10, 0f)
        assertEquals(6f, stats.median, 0f)
    }

    @Test fun singleValueAndZeroOutlierAreSupported() {
        assertEquals(0f, Analyzer.stats(floatArrayOf(0.5f)).rmsDev, 0f)
        val stats = Analyzer.stats(floatArrayOf(0f, 1f, 1f))
        assertEquals(1f, stats.maxDev, 0f)
        assertEquals(kotlin.math.sqrt(1f / 3f), stats.rmsDev, 1e-6f)
    }

    @Test fun statisticsDoNotMutateInputAndIgnoreOrderAndExposureScale() {
        val data = floatArrayOf(0.6f, 0.2f, 0.4f, 0.5f, 0.3f)
        val copy = data.clone()
        val stats = Analyzer.stats(data)
        assertArrayEquals(copy, data, 0f)
        assertEquals(stats, Analyzer.stats(data.reversedArray()))
        val scaled = Analyzer.stats(FloatArray(data.size) { data[it] * 4f })
        assertEquals(stats.rmsDev, scaled.rmsDev, 1e-6f)
        assertEquals(stats.p95Dev, scaled.p95Dev, 1e-6f)
        assertEquals(stats.median * 4, scaled.median, 1e-6f)
    }

    @Test fun invalidSignalsAreRejectedBeforeTheyBecomeNanStatistics() {
        for (data in listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(-1f, 1f),
            floatArrayOf(1f, Float.NaN), floatArrayOf(1f, Float.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { Analyzer.stats(data) }
        }
    }
}
