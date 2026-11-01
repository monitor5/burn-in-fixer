package com.burnin.scanner.analysis

import com.burnin.scanner.camera.CaptureFrame

/**
 * RAW_SENSOR(16비트 Bayer) → 선형 Gray/RGB.
 *
 * RAW는 센서 출력 그대로라 톤커브 추정·역변환이 필요 없다. 값은 (raw − black) / (white − black)로
 * 0~1 선형 신호가 되고, 채널은 촬영 때 고정한 AWB gain으로 맞춘다(무채색 패턴이 네 사이트에서
 * 같은 값이 되도록). RGB는 블록 안 같은 색 사이트를 평균한 뒤 HAL의 색 보정 행렬(CCM)을 곱해
 * YUV 경로와 같은 카메라 RGB 공간으로 둔다. 회색은 CCM을 쓰지 않는다(무채색에서 행 합 ≈ 1).
 *
 * 출력 좌표는 YUV 경로와 같다: 다운샘플 배율 s에서 출력 (ox, oy)는 원본 블록 [ox·s, ox·s+s).
 * s = 1이면 회색은 사이트 단위(원본 해상도), RGB는 그 픽셀이 속한 2×2 Bayer 쿼드 값이다.
 */
object RawDecode {

    /** 2×2 위치(행 우선 0..3) → 채널(0 R, 1 G, 2 B). */
    fun channelsFor(cfa: Int): IntArray = when (cfa) {
        CaptureFrame.RawInfo.CFA_RGGB -> intArrayOf(0, 1, 1, 2)
        CaptureFrame.RawInfo.CFA_GRBG -> intArrayOf(1, 0, 2, 1)
        CaptureFrame.RawInfo.CFA_GBRG -> intArrayOf(1, 2, 0, 1)
        CaptureFrame.RawInfo.CFA_BGGR -> intArrayOf(2, 1, 1, 0)
        else -> throw IllegalArgumentException("지원하지 않는 CFA 배열: $cfa")
    }

    private class Prepared(frame: CaptureFrame) {
        val info = frame.raw ?: throw IllegalArgumentException("RAW 메타데이터 없음")
        val plane = frame.planes.firstOrNull() ?: throw IllegalArgumentException("RAW plane 없음")
        val w = frame.width
        val h = frame.height
        val channel = channelsFor(info.cfa)
        /** 위치별 1 / (white − black) × gain(정규화). */
        val scale = FloatArray(4)
        val black = FloatArray(4)

        init {
            require(plane.pixelStride == 2 && plane.rowStride >= w * 2) { "RAW plane 형식 오류" }
            require((h - 1L) * plane.rowStride + w * 2L <= plane.bytes.size) { "truncated RAW plane" }
            require(info.blackLevel.size == 4 && info.whiteLevel > 0) { "RAW 레벨 정보 오류" }
            // gain을 최댓값으로 나눠 무채색 신호가 1을 넘지 않게 한다(클리핑 판정 유지).
            val gains = floatArrayOf(info.gainR, info.gainG, info.gainB).map { if (it.isFinite() && it > 0f) it else 1f }
            val maxGain = gains.max()
            for (p in 0..3) {
                black[p] = info.blackLevel[p].toFloat()
                val range = (info.whiteLevel - info.blackLevel[p]).coerceAtLeast(1)
                scale[p] = gains[channel[p]] / maxGain / range
            }
        }

        fun value(x: Int, y: Int): Float {
            val i = y * plane.rowStride + x * 2
            val raw = (plane.bytes[i].toInt() and 0xFF) or ((plane.bytes[i + 1].toInt() and 0xFF) shl 8)
            val p = ((y and 1) shl 1) or (x and 1)
            return ((raw - black[p]) * scale[p]).coerceAtLeast(0f)
        }

        fun pos(x: Int, y: Int) = ((y and 1) shl 1) or (x and 1)
    }

    fun gray(frame: CaptureFrame, sample: Int): GrayImage {
        require(sample >= 1)
        val r = Prepared(frame)
        val outW = Math.max(1, (r.w + sample - 1) / sample)
        val outH = Math.max(1, (r.h + sample - 1) / sample)
        val out = FloatArray(outW * outH)
        Analyzer.parallelRows(outH) { oy ->
            val y0 = oy * sample
            val y1 = Math.min(y0 + sample, r.h)
            var i = oy * outW
            for (ox in 0 until outW) {
                val x0 = ox * sample
                val x1 = Math.min(x0 + sample, r.w)
                var acc = 0f
                for (y in y0 until y1) for (x in x0 until x1) acc += r.value(x, y)
                out[i++] = acc / ((y1 - y0) * (x1 - x0))
            }
        }
        return GrayImage(outW, outH, out)
    }

    /** [rgb]와 같은 계산에서 한 채널(0 R, 1 G, 2 B)만 만든다. 단색 패턴 분석의 메모리를 1/3로 줄인다. */
    fun channel(frame: CaptureFrame, sample: Int, channel: Int): GrayImage {
        require(sample >= 1 && channel in 0..2)
        val r = Prepared(frame)
        val ccm = r.info.ccm?.takeIf { it.size == 9 && it.all { v -> v.isFinite() } }
        val outW = Math.max(1, (r.w + sample - 1) / sample)
        val outH = Math.max(1, (r.h + sample - 1) / sample)
        val out = FloatArray(outW * outH)
        Analyzer.parallelRows(outH) { oy ->
            val sum = FloatArray(3)
            val cnt = IntArray(3)
            var i = oy * outW
            val y0 = (oy * sample) and 1.inv()
            val y1 = Math.min(Math.max(oy * sample + sample, y0 + 2), r.h)
            for (ox in 0 until outW) {
                val x0 = (ox * sample) and 1.inv()
                val x1 = Math.min(Math.max(ox * sample + sample, x0 + 2), r.w)
                sum.fill(0f); cnt.fill(0)
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = r.channel[r.pos(x, y)]
                    sum[c] += r.value(x, y)
                    cnt[c]++
                }
                val cr = if (cnt[0] > 0) sum[0] / cnt[0] else 0f
                val cg = if (cnt[1] > 0) sum[1] / cnt[1] else 0f
                val cb = if (cnt[2] > 0) sum[2] / cnt[2] else 0f
                out[i++] = if (ccm != null) {
                    val k = channel * 3
                    (ccm[k] * cr + ccm[k + 1] * cg + ccm[k + 2] * cb).coerceAtLeast(0f)
                } else when (channel) { 0 -> cr; 1 -> cg; else -> cb }
            }
        }
        return GrayImage(outW, outH, out)
    }

    fun rgb(frame: CaptureFrame, sample: Int): RgbImage {
        require(sample >= 1)
        val r = Prepared(frame)
        val ccm = r.info.ccm?.takeIf { it.size == 9 && it.all { v -> v.isFinite() } }
        val outW = Math.max(1, (r.w + sample - 1) / sample)
        val outH = Math.max(1, (r.h + sample - 1) / sample)
        val outR = FloatArray(outW * outH)
        val outG = FloatArray(outW * outH)
        val outB = FloatArray(outW * outH)
        Analyzer.parallelRows(outH) { oy ->
            val sum = FloatArray(3)
            val cnt = IntArray(3)
            var i = oy * outW
            // 블록을 2×2 Bayer 쿼드 경계로 넓혀 세 채널이 모두 들어가게 한다.
            val y0 = (oy * sample) and 1.inv()
            val y1 = Math.min(Math.max(oy * sample + sample, y0 + 2), r.h)
            for (ox in 0 until outW) {
                val x0 = (ox * sample) and 1.inv()
                val x1 = Math.min(Math.max(ox * sample + sample, x0 + 2), r.w)
                sum.fill(0f); cnt.fill(0)
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = r.channel[r.pos(x, y)]
                    sum[c] += r.value(x, y)
                    cnt[c]++
                }
                val cr = if (cnt[0] > 0) sum[0] / cnt[0] else 0f
                val cg = if (cnt[1] > 0) sum[1] / cnt[1] else 0f
                val cb = if (cnt[2] > 0) sum[2] / cnt[2] else 0f
                if (ccm != null) {
                    outR[i] = (ccm[0] * cr + ccm[1] * cg + ccm[2] * cb).coerceAtLeast(0f)
                    outG[i] = (ccm[3] * cr + ccm[4] * cg + ccm[5] * cb).coerceAtLeast(0f)
                    outB[i] = (ccm[6] * cr + ccm[7] * cg + ccm[8] * cb).coerceAtLeast(0f)
                } else {
                    outR[i] = cr; outG[i] = cg; outB[i] = cb
                }
                i++
            }
        }
        return RgbImage(outW, outH, outR, outG, outB)
    }
}
