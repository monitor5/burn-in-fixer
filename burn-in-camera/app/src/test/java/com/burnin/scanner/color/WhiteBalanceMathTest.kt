package com.burnin.scanner.color

import org.junit.Assert.*
import org.junit.Test

class WhiteBalanceMathTest {
    @Test fun neutralAndEqualExposureScalingDoNotIntroduceTint() {
        for(scale in listOf(1.0,2.0)) {
            val g=WhiteBalanceMath.compute(doubleArrayOf(.5,.5,.5),doubleArrayOf(.5/scale,.5/scale,.5/scale),.2)
            assertEquals(1.0,g.r,0.0);assertEquals(g.r,g.g,0.0);assertEquals(g.r,g.b,0.0);assertFalse(g.clipped)
        }
    }
    @Test fun onlyExcessChannelsAreAttenuatedAndClippingIsReported() {
        val g=WhiteBalanceMath.compute(doubleArrayOf(.5,.5,.5),doubleArrayOf(.625,.5,1.0),.2)
        assertEquals(.8,g.r,1e-9);assertEquals(1.0,g.g,0.0);assertEquals(.8,g.b,1e-9);assertTrue(g.clipped)
    }
    @Test fun absentOrNonFiniteChannelsCannotProduceAProfile() {
        for(v in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { WhiteBalanceMath.compute(doubleArrayOf(.5,.5,.5),doubleArrayOf(v,.5,.5),.2) }
        assertThrows(IllegalArgumentException::class.java) { WhiteBalanceMath.compute(doubleArrayOf(.5),doubleArrayOf(.5,.5,.5),.2) }
    }
}
