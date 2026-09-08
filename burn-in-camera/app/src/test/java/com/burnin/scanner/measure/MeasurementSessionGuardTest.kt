package com.burnin.scanner.measure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MeasurementSessionGuardTest {
    @Test fun successKeepsSelectedCorrection() = runBlocking {
        var cleaned=false
        assertEquals(7,MeasurementSessionGuard.run({cleaned=true}){7});assertFalse(cleaned)
    }
    @Test fun failedMeasurementCleansUpAndPreservesOriginalError() = runBlocking {
        val original=IllegalStateException("capture failed");var cleaned=false
        try { MeasurementSessionGuard.run({cleaned=true}){throw original};fail() }
        catch(e:IllegalStateException) { assertSame(original,e) }
        assertTrue(cleaned)
    }
    @Test fun cancellationAlsoCleansUpInActiveContextAndPropagates() = runBlocking {
        var active=false
        try { MeasurementSessionGuard.run({active=currentCoroutineContext().isActive}){throw CancellationException("stop")};fail() }
        catch(_:CancellationException) { }
        assertTrue(active)
    }
    @Test fun cleanupFailureIsReportedAlongsideOriginalCause() = runBlocking {
        val original=IllegalArgumentException("capture");val cleanup=IllegalStateException("disconnect")
        try { MeasurementSessionGuard.run({throw cleanup}){throw original};fail() }
        catch(e:IllegalArgumentException) { assertSame(original,e);assertEquals(cleanup.message,e.suppressed.single().message);assertEquals(cleanup.javaClass,e.suppressed.single().javaClass) }
    }
}
