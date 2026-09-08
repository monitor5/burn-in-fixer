package com.burnin.scanner.camera

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CameraResourceDeliveryTest {
    private class Resource { var closes=0 }
    @Test fun activeWaiterReceivesOwnershipWithoutPrematureClose() = runBlocking {
        val r=Resource();lateinit var waiter:CancellableContinuation<Resource>;var received:Resource?=null
        val job=launch(start=CoroutineStart.UNDISPATCHED){received=suspendCancellableCoroutine{waiter=it}}
        CameraResourceDelivery.deliver(waiter,r){it.closes++};job.join()
        assertSame(r,received);assertEquals(0,r.closes)
    }
    @Test fun resourceArrivingAfterCancellationIsClosedExactlyOnce() = runBlocking {
        val r=Resource();lateinit var waiter:CancellableContinuation<Resource>
        val job=launch(start=CoroutineStart.UNDISPATCHED){suspendCancellableCoroutine<Resource>{waiter=it}}
        job.cancelAndJoin();CameraResourceDelivery.deliver(waiter,r){it.closes++};assertEquals(1,r.closes)
    }
    @Test fun cancellationBetweenResumeAndDispatchStillClosesResource() = runBlocking {
        val r=Resource();lateinit var waiter:CancellableContinuation<Resource>;var received=false
        val job=launch(start=CoroutineStart.UNDISPATCHED){suspendCancellableCoroutine<Resource>{waiter=it};received=true}
        CameraResourceDelivery.deliver(waiter,r){it.closes++};job.cancelAndJoin()
        assertFalse(received);assertEquals(1,r.closes)
    }
}
