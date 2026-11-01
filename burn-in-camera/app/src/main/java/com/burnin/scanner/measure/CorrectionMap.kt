package com.burnin.scanner.measure

import com.burnin.scanner.analysis.Analyzer

/**
 * A selected candidate carries the same channel policy into transmission and reporting.
 *
 * [detail]은 희소 점등 측광에서 얻은 화면 픽셀 단위 고주파 감쇠 층이다(글씨 획·버튼 테두리).
 * 반복 보정 루프가 다루는 저주파 [gain]과 곱해서 전송하며, 기준선(무보정) 맵에는 적용하지 않는다.
 */
internal class CorrectionMap(
    gain: FloatArray,
    channelGains: Map<Int, FloatArray>,
    val channelWeight: Float,
    val isBaseline: Boolean = false,
    detail: FloatArray? = null,
    private val maxAttenuation: Float = 1f,
    /** true면 채널 gain을 복사하지 않고 공유한다(측정 뒤 바뀌지 않는 격자를 반복마다 넘길 때). */
    shareChannels: Boolean = false,
    /** 채널별(R/G/B) 픽셀 단위 고주파 층. 있으면 RGB 맵의 각 채널에 [detail] 대신 곱한다(공유, 복사 안 함). */
    private val detailRgb: List<FloatArray>? = null,
) {
    val gain = gain.copyOf()
    private val channels = if (shareChannels) channelGains else channelGains.mapValues { it.value.copyOf() }
    // 고주파 층은 측정 뒤 바뀌지 않는 격자라 복사하지 않고 공유한다(2048×1536 한 장 12.6MB, 힙 512MB 한도).
    private val detail = detail

    init {
        require(this.detail == null || this.detail.size == this.gain.size) { "detail layer size mismatch" }
    }

    /** 실제로 전송되는 gain: 저주파 gain × 고주파 층, 감쇠 상한으로 clamp */
    val effectiveGain: FloatArray = withDetail(this.gain)

    val hasDetail: Boolean get() = detail != null && !isBaseline

    private fun withDetail(g: FloatArray): FloatArray {
        val d = detail ?: return g.copyOf()
        if (isBaseline) return g.copyOf()
        return FloatArray(g.size) { (g[it] * d[it]).coerceIn(1f - maxAttenuation, 1f) }
    }

    /** 같은 저주파·채널 맵에 고주파 층만 바꾼 맵(픽셀 단위 재검증 결과 반영용). */
    fun replaceDetail(newDetail: FloatArray, newDetailRgb: List<FloatArray>? = detailRgb): CorrectionMap =
        CorrectionMap(gain, channels, channelWeight, isBaseline, newDetail, maxAttenuation, shareChannels = true, detailRgb = newDetailRgb)

    fun alphaPng(gw: Int, gh: Int, width: Int, height: Int, maxAttenuation: Float): ByteArray =
        Analyzer.toAlphaPng(effectiveGain, gw, gh, width, height, maxAttenuation)

    fun rgbPng(gw: Int, gh: Int, width: Int, height: Int, maxAttenuation: Float): ByteArray? {
        val rgbDetail = detailRgb?.takeIf { !isBaseline && it.size == 3 }
        if ((channels.isEmpty() || channelWeight <= 0f) && rgbDetail == null) return null
        // 채널 혼합 결과에 고주파 층을 제자리에서 곱해 채널당 격자 하나만 새로 만든다.
        fun channel(index: Int): FloatArray {
            val mixed = channels[index]?.takeIf { channelWeight > 0f }
                ?.let { Analyzer.mixGainGrids(gain, it, channelWeight, maxAttenuation) }
                ?: if (rgbDetail != null) gain.copyOf() else return effectiveGain
            val d = rgbDetail?.get(index) ?: detail
            if (d != null && !isBaseline) {
                for (i in mixed.indices) mixed[i] = (mixed[i] * d[i]).coerceIn(1f - this.maxAttenuation, 1f)
            }
            return mixed
        }
        return Analyzer.toRgbAttenuationPng({ channel(it) }, gw, gh, width, height, maxAttenuation)
    }
}
