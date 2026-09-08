package com.burnin.target.pattern

import org.junit.Assert.*
import org.junit.Test

class PatternSpecBoundaryTest {
    @Test fun allSolidPercentagesHaveExactChannelAndAlphaCodes() {
        for(base in listOf("white","gray","grey","red","green","blue","black")) for(pct in 0..100) {
            val spec=PatternSpec.parse("$base$pct")!!;val value=Math.round(255f*pct/100)
            val r=if(base in listOf("red","white","gray","grey")) value else 0
            val g=if(base in listOf("green","white","gray","grey")) value else 0
            val b=if(base in listOf("blue","white","gray","grey")) value else 0
            assertEquals("$base$pct",(255 shl 24) or (r shl 16) or (g shl 8) or b,spec.color)
        }
    }
    @Test fun aliasesNormalizationAndRangeLimitsAreExplicit() {
        assertEquals(0xffb3b3b3.toInt(),PatternSpec.parse("  GrEy70  ")!!.color)
        assertEquals(-1,PatternSpec.parse("white999")!!.color)
        assertEquals(PatternSpec.Kind.CHECKER,PatternSpec.parse("checkerboard")!!.kind)
        assertEquals(12,PatternSpec.parse("dots")!!.cells);assertEquals(10,PatternSpec.parse("checker")!!.cells)
        for(invalid in listOf("","red-1","red1000","red1.0","red 50","unknown","gray70junk")) assertNull(invalid,PatternSpec.parse(invalid))
    }
}
