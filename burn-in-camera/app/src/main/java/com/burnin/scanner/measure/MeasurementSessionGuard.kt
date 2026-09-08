package com.burnin.scanner.measure

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal object MeasurementSessionGuard {
    suspend fun <T> run(onFailure: suspend () -> Unit, measurement: suspend () -> T): T {
        try { return measurement() }
        catch (failure: Exception) {
            try { withContext(NonCancellable) { onFailure() } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
