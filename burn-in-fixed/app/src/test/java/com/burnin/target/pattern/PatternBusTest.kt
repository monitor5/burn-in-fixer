package com.burnin.target.pattern

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class PatternBusTest {
    private class Host:PatternBus.Host {
        var spec:PatternSpec?=null;var done:Runnable?=null;var corrections=0;var closed=false
        override fun applyPattern(spec:PatternSpec,done:Runnable){this.spec=spec;this.done=done}
        override fun applyCorrectionState(){corrections++}
        override fun closeSelf(){closed=true}
    }
    @Test fun acknowledgmentWaitsForHostFrameCommit() {
        val h=Host();PatternBus.register(h);var completed=0
        try {
            PatternBus.showPattern(RuntimeEnvironment.getApplication(),PatternSpec.parse("gray25")!!,Runnable{completed++})
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("gray25",h.spec!!.name);assertEquals(0,completed)
            h.done!!.run();assertEquals(1,completed)
        } finally {PatternBus.unregister(h)}
    }
    @Test fun absentHostNeverProducesSuccessfulAck() {
        var completed=false
        PatternBus.showPattern(RuntimeEnvironment.getApplication(),PatternSpec.BLACK,Runnable{completed=true})
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertFalse(completed)
    }
    @Test fun staleUnregisterCannotRemoveNewHostAndStrengthIsClamped() {
        val old=Host();val current=Host();PatternBus.register(old);PatternBus.register(current);PatternBus.unregister(old)
        try {
            PatternBus.setCorrection(true,150);shadowOf(Looper.getMainLooper()).idle()
            assertTrue(PatternBus.correctionEnabled);assertEquals(100,PatternBus.strengthPct);assertEquals(1,current.corrections)
            PatternBus.setCorrection(false,-5);PatternBus.endSession();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(PatternBus.correctionEnabled);assertEquals(0,PatternBus.strengthPct);assertTrue(current.closed)
        } finally {PatternBus.unregister(current)}
    }
}
