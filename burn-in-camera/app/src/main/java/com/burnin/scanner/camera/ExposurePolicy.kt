package com.burnin.scanner.camera

internal object ExposurePolicy {
    fun quantize(exposureNs: Long, iso: Int, refreshHz: Float, exposureRange: LongRange?, isoRange: IntRange?): Pair<Long, Int> {
        if (!refreshHz.isFinite() || refreshHz < 1f || exposureNs <= 0 || iso <= 0) return exposureNs to iso
        val period = Math.round(1e9 / refreshHz)
        if (period <= 0) return exposureNs to iso
        fun candidate(cycles: Long): Pair<Long, Int>? {
            if (cycles < 1 || cycles > Long.MAX_VALUE / period) return null
            val quantized = cycles * period
            if (exposureRange != null && quantized !in exposureRange) return null
            val desired = Math.round(iso.toDouble() * exposureNs / quantized).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val compensated = if (isoRange == null) desired else desired.coerceIn(isoRange)
            val ratio = compensated.toDouble() * quantized / (iso.toDouble() * exposureNs)
            return if (ratio in .85..1.15) quantized to compensated else null
        }
        val floor = exposureNs / period
        val ceiling = floor + if (exposureNs % period == 0L) 0 else 1
        return candidate(ceiling) ?: candidate(floor) ?: (exposureNs to iso)
    }
}
