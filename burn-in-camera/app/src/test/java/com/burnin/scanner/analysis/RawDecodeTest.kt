package com.burnin.scanner.analysis

import com.burnin.scanner.camera.CaptureFrame
import com.burnin.scanner.camera.CaptureFrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawDecodeTest {
    @get:Rule val temp = TemporaryFolder()

    private val black = 64
    private val white = 1023

    /** RGGB 센서가 무채색 신호 [level]을 본 프레임: 채널별 센서 감도는 1/gain. */
    private fun neutralFrame(w: Int, h: Int, level: (Int, Int) -> Float, gains: FloatArray, ccm: List<Float>? = null): CaptureFrame {
        val bytes = ByteArray(w * h * 2)
        val ch = RawDecode.channelsFor(CaptureFrame.RawInfo.CFA_RGGB)
        for (y in 0 until h) for (x in 0 until w) {
            val c = ch[((y and 1) shl 1) or (x and 1)]
            val v = black + Math.round(level(x, y) / gains[c] * (white - black))
            bytes[(y * w + x) * 2] = (v and 0xFF).toByte()
            bytes[(y * w + x) * 2 + 1] = (v shr 8).toByte()
        }
        val info = CaptureFrame.RawInfo(CaptureFrame.RawInfo.CFA_RGGB, List(4) { black }, white,
            gains[0], gains[1], gains[2], ccm)
        return CaptureFrame(android.graphics.ImageFormat.RAW_SENSOR, w, h,
            listOf(CaptureFrame.Plane(bytes, w * 2, 2)), 1L, 100, 1000L, info)
    }

    @Test fun neutralPatchIsFlatAcrossBayerSitesAfterWhiteBalance() {
        val gains = floatArrayOf(2f, 1f, 1.5f)
        val f = neutralFrame(8, 6, { _, _ -> 0.4f }, gains)
        val g = RawDecode.gray(f, 1)
        assertEquals(8, g.w); assertEquals(6, g.h)
        // gain을 최댓값(2)으로 나눠 정규화하므로 무채색 0.4 → 0.2로 모든 사이트가 같아야 한다
        for (v in g.data) assertEquals(0.2f, v, 2e-3f)
        val half = RawDecode.gray(f, 2)
        assertEquals(4, half.w); assertEquals(3, half.h)
        for (v in half.data) assertEquals(0.2f, v, 2e-3f)
    }

    @Test fun rgbUsesQuadChannelsAndAppliesCcm() {
        val gains = floatArrayOf(1f, 1f, 1f)
        val f = neutralFrame(4, 4, { _, _ -> 0.5f }, gains)
        val rgb = RawDecode.rgb(f, 1)
        assertEquals(4, rgb.w)
        for (i in rgb.r.indices) {
            assertEquals(0.5f, rgb.r[i], 2e-3f); assertEquals(0.5f, rgb.g[i], 2e-3f); assertEquals(0.5f, rgb.b[i], 2e-3f)
        }
        // CCM이 R 행에 G를 섞으면 결과에 반영된다
        val ccm = listOf(0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val mixed = RawDecode.rgb(neutralFrame(4, 4, { _, _ -> 0.5f }, gains, ccm), 2)
        assertEquals(0.5f, mixed.r[0], 2e-3f)
    }

    @Test fun singleChannelDecodeMatchesRgbChannel() {
        val ccm = listOf(0.9f, 0.1f, 0f, 0.05f, 0.9f, 0.05f, 0f, 0.2f, 0.8f)
        val f = neutralFrame(8, 6, { x, y -> 0.1f + 0.05f * x + 0.02f * y }, floatArrayOf(2f, 1f, 1.5f), ccm)
        for (s in listOf(1, 2)) {
            val rgb = RawDecode.rgb(f, s)
            val chans = listOf(rgb.r, rgb.g, rgb.b)
            for (c in 0..2) {
                val one = RawDecode.channel(f, s, c)
                assertEquals(rgb.w, one.w)
                for (i in one.data.indices) assertEquals(chans[c][i], one.data[i], 1e-6f)
            }
        }
    }

    @Test fun lensShadingMapUndoesVignetting() {
        // 가장자리로 갈수록 센서 신호가 1/gain만큼 줄어든 균일 화면: 셰이딩 맵을 곱하면 평평해야 한다
        val w = 8
        val h = 6
        val cols = 3
        val rows = 3
        fun gainAt(c: Int, r: Int) = if (c == 1 && r == 1) 1f else 1.5f
        val gains = List(cols * rows * 4) { i -> val cell = i / 4; gainAt(cell % cols, cell / cols) }
        val shading = CaptureFrame.RawInfo.Shading(cols, rows, gains)
        val vignetted = neutralFrame(w, h, { x, y ->
            0.6f / RawDecode.shadingGain(shading, 0, x, y, w, h)
        }, floatArrayOf(1f, 1f, 1f))
        val corrected = vignetted.copy(raw = vignetted.raw!!.copy(shading = shading))
        val flat = RawDecode.gray(corrected, 1)
        for (v in flat.data) assertEquals(0.6f, v, 3e-3f)
        val uncorrected = RawDecode.gray(vignetted, 1)
        assertEquals(0.4f, uncorrected[0, 0], 3e-3f)
        val file = temp.newFile()
        CaptureFrameCodec.write(file, corrected)
        assertEquals(shading, CaptureFrameCodec.read(file).raw!!.shading)
    }

    @Test fun spatialDetailSurvivesAtFullResolution() {
        // 한 열만 밝은 무채색 줄: 원본 해상도 회색에서 그 열만 높아야 한다
        val f = neutralFrame(8, 4, { x, _ -> if (x == 3) 0.8f else 0.2f }, floatArrayOf(1f, 1f, 1f))
        val g = RawDecode.gray(f, 1)
        assertEquals(0.8f, g[3, 1], 2e-3f)
        assertEquals(0.2f, g[2, 1], 2e-3f)
    }

    @Test fun rawMetadataRoundTripsThroughCaptureCache() {
        val f = neutralFrame(4, 2, { _, _ -> 0.3f }, floatArrayOf(2f, 1f, 1.5f), List(9) { it * 0.1f })
        val file = temp.newFile()
        CaptureFrameCodec.write(file, f)
        val read = CaptureFrameCodec.read(file)
        assertEquals(f.raw, read.raw)
        assertEquals(RawDecode.gray(f, 1).data.toList(), RawDecode.gray(read, 1).data.toList())
    }

    @Test fun rejectsUnknownCfaAndMissingMetadata() {
        assertThrows(IllegalArgumentException::class.java) { RawDecode.channelsFor(4) }
        val f = neutralFrame(2, 2, { _, _ -> 0.1f }, floatArrayOf(1f, 1f, 1f)).copy(raw = null)
        assertThrows(IllegalArgumentException::class.java) { RawDecode.gray(f, 1) }
    }
}
