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
) {
    val gain = gain.copyOf()
    private val channels = channelGains.mapValues { it.value.copyOf() }
    private val detail = detail?.copyOf()

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

    fun alphaPng(gw: Int, gh: Int, width: Int, height: Int, maxAttenuation: Float): ByteArray =
        Analyzer.toAlphaPng(effectiveGain, gw, gh, width, height, maxAttenuation)

    fun rgbPng(gw: Int, gh: Int, width: Int, height: Int, maxAttenuation: Float): ByteArray? {
        if (channels.isEmpty() || channelWeight <= 0f) return null
        fun channel(index: Int) = withDetail(channels[index]?.let {
            Analyzer.mixGainGrids(gain, it, channelWeight, maxAttenuation)
        } ?: gain)
        return Analyzer.toRgbAttenuationPng(channel(0), channel(1), channel(2),
            gw, gh, width, height, maxAttenuation)
    }
}
