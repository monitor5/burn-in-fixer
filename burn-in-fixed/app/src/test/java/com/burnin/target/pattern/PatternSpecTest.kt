package com.burnin.target.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatternSpecTest {

    @Test
    fun parsesGrayLevels() {
        // 255 * 0.70 = 178.5 → 179 (0xB3)
        assertEquals(0xFFB3B3B3.toInt(), PatternSpec.parse("gray70")!!.color)
        // 255 * 0.85 = 216.75 → 217 (0xD9)
        assertEquals(0xFFD9D9D9.toInt(), PatternSpec.parse("white85")!!.color)
        assertEquals(0xFF404040.toInt(), PatternSpec.parse("gray25")!!.color)
        assertEquals(0xFF4D4D4D.toInt(), PatternSpec.parse("gray30")!!.color)
        assertEquals(PatternSpec.Kind.SOLID, PatternSpec.parse("gray70")!!.kind)
    }

    @Test
    fun parsesRgbChannels() {
        assertEquals(0xFFB30000.toInt(), PatternSpec.parse("red70")!!.color)
        assertEquals(0xFF00B300.toInt(), PatternSpec.parse("green70")!!.color)
        assertEquals(0xFF0000B3.toInt(), PatternSpec.parse("blue70")!!.color)
    }

    @Test
    fun parsesBlackAndSpecials() {
        assertEquals(PatternSpec.COLOR_BLACK, PatternSpec.parse("black")!!.color)
        assertEquals(PatternSpec.Kind.MARKER, PatternSpec.parse("marker")!!.kind)
        assertEquals(PatternSpec.Kind.CHECKER, PatternSpec.parse("checkerboard")!!.kind)
        assertEquals(PatternSpec.Kind.DOTGRID, PatternSpec.parse("dotgrid")!!.kind)
        assertEquals(PatternSpec.Kind.GRID, PatternSpec.parse("grid")!!.kind)
    }

    @Test
    fun clampsAndRejects() {
        // 100 초과 퍼센트는 100으로 clamp
        assertEquals(0xFFFFFFFF.toInt(), PatternSpec.parse("gray200")!!.color)
        assertNull(PatternSpec.parse("rainbow"))
        assertNull(PatternSpec.parse(""))
    }

    @Test
    fun parsesSparsePatternsAndLitPixels() {
        val spec = PatternSpec.parse("sparse70_b1_p4_x3_y1")!!
        assertEquals(PatternSpec.Kind.SPARSE, spec.kind)
        assertEquals(0xFFB3B3B3.toInt(), spec.color)
        assertEquals(true, spec.sparseLit(3, 1))
        assertEquals(true, spec.sparseLit(7, 5))
        assertEquals(false, spec.sparseLit(4, 1))
        assertEquals(false, spec.sparseLit(2, 1))
        val blocks = PatternSpec.parse("sparse70_b2_p6_x1_y2")!!
        assertEquals(true, blocks.sparseLit(2, 4))
        assertEquals(true, blocks.sparseLit(3, 5))
        assertEquals(false, blocks.sparseLit(4, 4))
        assertEquals(true, blocks.sparseLit(8, 10))
    }

    @Test
    fun rejectsInvalidSparsePatterns() {
        assertNull(PatternSpec.parse("sparse70_b1_p4_x4_y0"))   // 위상 범위 초과
        assertNull(PatternSpec.parse("sparse70_b2_p5_x0_y0"))   // pitch가 block의 배수가 아님
        assertNull(PatternSpec.parse("sparse70_b3_p3_x0_y0"))   // 블록끼리 붙음
        assertNull(PatternSpec.parse("sparse70_b0_p4_x0_y0"))
    }

    @Test
    fun parsesArbitraryRgbPatches() {
        assertEquals(0xFFFF8000.toInt(), PatternSpec.parse("rgb-ff8000")!!.color)
        assertEquals(0xFF0A0B0C.toInt(), PatternSpec.parse("RGB-0A0B0C")!!.color)
        assertEquals(PatternSpec.Kind.SOLID, PatternSpec.parse("rgb-000000")!!.kind)
        assertNull(PatternSpec.parse("rgb-ff80"))
        assertNull(PatternSpec.parse("rgb-gg0000"))
    }
}
