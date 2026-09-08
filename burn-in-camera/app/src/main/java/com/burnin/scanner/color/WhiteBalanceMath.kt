package com.burnin.scanner.color

internal object WhiteBalanceMath {
    data class Gains(val r: Double, val g: Double, val b: Double, val clipped: Boolean)
    fun compute(reference: DoubleArray, adjustment: DoubleArray, maxAttenuation: Double): Gains {
        require(reference.size == 3 && adjustment.size == 3)
        require(reference.all { it.isFinite() && it > 1e-6 } && adjustment.all { it.isFinite() && it > 1e-6 }) { "white balance signal missing" }
        require(maxAttenuation.isFinite() && maxAttenuation in 0.0..1.0)
        val desired=DoubleArray(3) { reference[it]/adjustment[it] }
        val scale=maxOf(1.0,desired.max())
        val raw=desired.map { it/scale };val minimum=1-maxAttenuation
        return Gains(raw[0].coerceIn(minimum,1.0),raw[1].coerceIn(minimum,1.0),raw[2].coerceIn(minimum,1.0),raw.any{it<minimum})
    }
}
