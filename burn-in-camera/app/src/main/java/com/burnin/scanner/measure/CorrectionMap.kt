package com.burnin.scanner.measure

import com.burnin.scanner.analysis.Analyzer

/** A selected candidate carries the same channel policy into transmission and reporting. */
internal class CorrectionMap(
    gain: FloatArray,
    channelGains: Map<Int, FloatArray>,
    val channelWeight: Float,
    val isBaseline: Boolean = false,
) {
    val gain = gain.copyOf()
    private val channels = channelGains.mapValues { it.value.copyOf() }

    fun alphaPng(gw: Int, gh: Int, width: Int, height: Int, maxAttenuation: Float): ByteArray =
        Analyzer.toAlphaPng(gain, gw, gh, width, height, maxAttenuation)

    fun rgbPng(gw: Int, gh: Int, width: Int, height: Int, maxAttenuation: Float): ByteArray? {
        if (channels.isEmpty() || channelWeight <= 0f) return null
        fun channel(index: Int) = channels[index]?.let {
            Analyzer.mixGainGrids(gain, it, channelWeight, maxAttenuation)
        } ?: gain
        return Analyzer.toRgbAttenuationPng(channel(0), channel(1), channel(2),
            gw, gh, width, height, maxAttenuation)
    }
}
