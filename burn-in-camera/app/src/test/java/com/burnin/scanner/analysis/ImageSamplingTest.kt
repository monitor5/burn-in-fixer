package com.burnin.scanner.analysis

import org.junit.Assert.*
import org.junit.Test

class ImageSamplingTest {
    @Test fun bilinearUsesAllFourPixels() {
        val img = GrayImage(2, 2, floatArrayOf(0f, 2f, 4f, 8f))
        assertEquals(3.5f, img.bilinear(0.5f, 0.5f), 0f)
        assertEquals(1.625f, img.bilinear(0.25f, 0.25f), 0f)
    }

    @Test fun cornersAndOutsideCoordinatesClampExactly() {
        val img = GrayImage(2, 2, floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(1f, img.bilinear(-10f, -10f), 0f)
        assertEquals(2f, img.bilinear(10f, -10f), 0f)
        assertEquals(3f, img.bilinear(-10f, 10f), 0f)
        assertEquals(4f, img.bilinear(1f, 1f), 0f)
    }

    @Test fun singletonAndThinImagesAreSupported() {
        assertEquals(0.75f, GrayImage(1, 1, floatArrayOf(0.75f)).bilinear(50f, -2f), 0f)
        assertEquals(2f, GrayImage(1, 2, floatArrayOf(1f, 3f)).bilinear(0f, 0.5f), 0f)
        assertEquals(2f, GrayImage(2, 1, floatArrayOf(1f, 3f)).bilinear(0.5f, 0f), 0f)
    }

    @Test fun rgbKeepsChannelsSeparateAtBoundariesAndCenter() {
        val img = RgbImage(2, 2, floatArrayOf(0f, 1f, 2f, 3f),
            floatArrayOf(4f, 5f, 6f, 7f), floatArrayOf(8f, 9f, 10f, 11f))
        for (channel in 0..2) {
            assertEquals(1.5f + 4f * channel, img.bilinearChannel(0.5f, 0.5f, channel), 0f)
            assertEquals(3f + 4f * channel, img.bilinearChannel(1f, 1f, channel), 0f)
        }
        val single = RgbImage(1, 1, floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f))
        assertEquals(3f, single.bilinearChannel(0f, 0f, 2), 0f)
    }

    @Test fun invalidDimensionsChannelsAndCoordinatesAreRejected() {
        for ((w, h) in listOf(0 to 2, -1 to 2, Int.MAX_VALUE to 2)) {
            assertThrows(IllegalArgumentException::class.java) { GrayImage(w, h, FloatArray(4)) }
        }
        assertThrows(IllegalArgumentException::class.java) { GrayImage(2, 2, FloatArray(3)) }
        assertThrows(IllegalArgumentException::class.java) {
            RgbImage(2, 2, FloatArray(4), FloatArray(3), FloatArray(4))
        }
        val gray = GrayImage(2, 2, FloatArray(4))
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { gray.bilinear(value, 0f) }
        }
        val rgb = RgbImage(1, 1, floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0f))
        assertThrows(IllegalArgumentException::class.java) { rgb.bilinearChannel(0f, 0f, 3) }
    }

    @Test fun averagingChecksShapeAndPreservesSourceFrames() {
        val a = GrayImage(2, 1, floatArrayOf(0f, 1f))
        val b = GrayImage(2, 1, floatArrayOf(1f, 0f))
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), ImageOps.average(listOf(a, b)).data, 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), a.data, 0f)
        assertThrows(IllegalArgumentException::class.java) { ImageOps.average(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            ImageOps.average(listOf(a, GrayImage(1, 2, FloatArray(2))))
        }
        val rgb = RgbImage(1, 1, floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f))
        val mean = ImageOps.averageRgb(listOf(rgb, rgb))
        assertArrayEquals(rgb.r, mean.r, 0f)
        assertArrayEquals(rgb.g, mean.g, 0f)
        assertArrayEquals(rgb.b, mean.b, 0f)
        assertNotSame(rgb.r, mean.r)
        assertThrows(IllegalArgumentException::class.java) { ImageOps.averageRgb(emptyList()) }
    }
}
