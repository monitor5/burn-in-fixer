package com.burnin.target.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorLutTest {
    /** Scanner ColorLut.identity(size).encode()와 같은 바이트를 만든다 (와이어 형식 계약). */
    private fun identityBytes(size: Int, postOverride: ((Int, Int) -> Int)? = null): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun put(q: Int) { out.write(q shr 8 and 0xFF); out.write(q and 0xFF) }
        for (r in 0 until size) for (g in 0 until size) for (b in 0 until size)
            for (v in intArrayOf(r, g, b)) put(Math.round(v * 65535f / (size - 1)))
        for (c in 0 until 3) for (i in 0 until 256) put(postOverride?.invoke(c, i) ?: Math.round(i * 65535f / 255))
        return out.toByteArray()
    }

    @Test fun goldenIdentityMatchesScannerLayout() {
        val bytes = identityBytes(2)
        assertEquals(ColorLut.byteLength(2), bytes.size)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF), bytes.take(12).map { it.toInt() and 0xFF })
        val lut = ColorLut.decode(2, bytes)
        for (v in listOf(0, 1, 77, 128, 254, 255)) {
            val c = (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or (v / 2)
            assertEquals(c, lut.transformArgb(c))
        }
    }

    @Test fun postCurveIsAppliedAfterLutAndAlphaIsKept() {
        // 빨강 채널만 절반으로 줄이는 post 곡선
        val lut = ColorLut.decode(17, identityBytes(17) { c, i -> if (c == 0) Math.round(i * 65535f / 255 / 2) else Math.round(i * 65535f / 255) })
        assertEquals(0x80804020.toInt(), lut.transformArgb(0x80FF4020.toInt()))
    }

    @Test fun malformedProfilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ColorLut.decode(3, identityBytes(2)) }
        assertThrows(IllegalArgumentException::class.java) { ColorLut.decode(1, ByteArray(0)) }
        // 비단조 post 곡선
        assertThrows(IllegalArgumentException::class.java) { ColorLut.decode(2, identityBytes(2) { _, i -> if (i == 100) 0 else Math.round(i * 65535f / 255) }) }
        // 검정이 떠 있는 LUT
        val lifted = identityBytes(2).also { it[0] = 0x10 }
        assertThrows(IllegalArgumentException::class.java) { ColorLut.decode(2, lifted) }
    }
}
