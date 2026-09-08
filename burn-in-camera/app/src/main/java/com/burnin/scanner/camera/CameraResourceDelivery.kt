package com.burnin.scanner.camera

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Resource ownership transfers only if the awaiting coroutine actually receives it. */
internal object CameraResourceDelivery {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> deliver(continuation: CancellableContinuation<T>, value: T, close: (T) -> Unit) {
        if (!continuation.isActive) close(value)
        else continuation.resume(value, onCancellation = { close(value) })
    }
}
