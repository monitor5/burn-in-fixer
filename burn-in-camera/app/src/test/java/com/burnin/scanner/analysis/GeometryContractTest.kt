package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class GeometryContractTest {
    private val source = arrayOf(Vec2(0f, 0f), Vec2(400f, 0f), Vec2(400f, 300f), Vec2(0f, 300f))
    private val corners = arrayOf(Vec2(10f, 20f), Vec2(210f, 20f), Vec2(210f, 120f), Vec2(10f, 120f))

    @Test fun allEightMappingsMatchEveryCornerAndAnAsymmetricInteriorPoint() {
        for (start in 0..3) for (clockwise in listOf(true, false)) {
            val direction = if (clockwise) 1 else -1
            val mapped = Array(4) { corners[Math.floorMod(start + direction * it, 4)] }
            val h = Analyzer.buildHomography(ScreenDetector.Quad(corners), 400, 300,
                Analyzer.CornerMapping(start, clockwise, "test"))!!
            val out = DoubleArray(2)
            for (i in 0..3) {
                h.map(source[i].x.toDouble(), source[i].y.toDouble(), out)
                assertEquals(mapped[i].x.toDouble(), out[0], 1e-6)
                assertEquals(mapped[i].y.toDouble(), out[1], 1e-6)
            }
            h.map(100.0, 180.0, out)
            // A rectangle is affine: an independent edge-vector oracle at u=.25, v=.60.
            assertEquals((mapped[0].x + (mapped[1].x - mapped[0].x) * .25 +
                (mapped[3].x - mapped[0].x) * .60), out[0], 1e-5)
            assertEquals((mapped[0].y + (mapped[1].y - mapped[0].y) * .25 +
                (mapped[3].y - mapped[0].y) * .60), out[1], 1e-5)
        }
    }

    @Test fun portraitTargetRotatesTowardImageTopRight() {
        val h = Analyzer.buildHomography(ScreenDetector.Quad(corners), 300, 400)!!
        val out = DoubleArray(2)
        h.map(0.0, 0.0, out)
        assertEquals(210.0, out[0], 1e-6)
        assertEquals(20.0, out[1], 1e-6)
        h.map(300.0, 400.0, out)
        assertEquals(10.0, out[0], 1e-6)
        assertEquals(120.0, out[1], 1e-6)
    }

    @Test fun rejectsDegenerateNonFiniteAndMismatchedCorrespondences() {
        assertNull(Homography.from4Points(Array(4) { Vec2(1f, 1f) }, corners))
        assertNull(Homography.fromPointPairs(List(5) { Vec2(it.toFloat(), 0f) }, List(5) { Vec2(it.toFloat(), 0f) }))
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalid = source.map { Vec2(it.x, it.y) }.toTypedArray()
            invalid[2].x = value
            assertNull(Homography.from4Points(invalid, corners))
            assertNull(Homography.fromPointPairs(source.asList(), invalid.asList()))
        }
        assertThrows(IllegalArgumentException::class.java) { Homography.from4Points(source.take(3).toTypedArray(), corners) }
        assertThrows(IllegalArgumentException::class.java) { Homography.fromPointPairs(source.asList(), corners.take(3)) }
    }

    @Test fun missingOrUniformMarkerDoesNotInventOrientation() {
        for (value in listOf(0f, .5f)) {
            assertNull(Analyzer.buildHomographyWithMarker(ScreenDetector.Quad(corners),
                GrayImage(240, 160, FloatArray(240 * 160) { value }), 400, 300))
        }
    }

    @Test fun perspectiveAndAspectDistortionIncreaseQualityScore() {
        val ideal = ScreenDetector.Quad(source)
        assertEquals(0f, Analyzer.geometryQuality(ideal, 400, 300).score, 1e-6f)
        assertTrue(Analyzer.geometryQuality(ideal, 300, 300).aspectError > .2f)
        val trapezoid = ScreenDetector.Quad(arrayOf(Vec2(60f, 0f), Vec2(340f, 0f), Vec2(400f, 300f), Vec2(0f, 300f)))
        val quality = Analyzer.geometryQuality(trapezoid, 400, 300)
        assertTrue(quality.edgeBalance > .3f)
        assertTrue(quality.parallelErrorDeg > 10f)
        assertTrue(quality.score > .3f)
    }
}
