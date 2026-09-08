package com.burnin.scanner.measure

import com.burnin.scanner.net.ControlClient
import com.burnin.scanner.net.Protocol
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeasurementCleanupTest {
    @Test fun failedExchangeReconnectsAndDisablesBothRemoteLayers() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<List<String>> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val cmd = JSONObject(socket.getInputStream().bufferedReader().readLine()).getString("cmd")
                    assertEquals("SHOW_PATTERN", cmd)
                    socket.getOutputStream().write("{\"cmd\":\"SHOW_PATTERN\",\"ok\":false}\n".toByteArray())
                }
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    List(2) {
                        val cmd = JSONObject(reader.readLine()).getString("cmd")
                        socket.getOutputStream().write(JSONObject().put("cmd", cmd).put("ok", true)
                            .toString().plus("\n").toByteArray())
                        cmd
                    }
                }
            }
            val client = ControlClient("127.0.0.1", server.localPort)
            try {
                client.connect()
                assertThrows(IllegalStateException::class.java) { client.showPattern("gray70") }
                assertFalse(client.isConnected)
                MeasurementCleanup.disable(client, 1000)
                assertEquals(listOf(Protocol.CMD_DISABLE_CORRECTION, Protocol.CMD_DISABLE_OVERLAY),
                    received.get(5, TimeUnit.SECONDS))
            } finally { client.close(); server.close(); executor.shutdownNow() }
        }
    }

    @Test fun firstCommandFailureDoesNotSkipOverlayAndReconnectIsBounded() {
        val sent = ArrayList<String>()
        var reconnects = 0
        val failure = assertThrows(MeasurementCleanup.Incomplete::class.java) {
            MeasurementCleanup.disable({ true }, { reconnects++ }) { cmd ->
                sent += cmd
                if (cmd == Protocol.CMD_DISABLE_CORRECTION) error("rejected")
            }
        }
        assertEquals(1, reconnects)
        assertEquals(listOf(Protocol.CMD_DISABLE_CORRECTION, Protocol.CMD_DISABLE_CORRECTION,
            Protocol.CMD_DISABLE_OVERLAY), sent)
        assertEquals(MeasurementCleanup.WARNING, failure.message)
    }

    @Test fun unavailableTargetPreservesOriginalFailureAndRequiresManualCheck() = runBlocking {
        var reconnects = 0
        val original = IllegalStateException("capture failed")
        try {
            MeasurementSessionGuard.run(onFailure = {
                MeasurementCleanup.disable({ false }, { reconnects++; error("offline") }) { fail("offline send") }
            }) { throw original }
            fail("must fail")
        } catch (failure: IllegalStateException) {
            assertSame(original, failure)
            assertTrue(failure.suppressed.single() is MeasurementCleanup.Incomplete)
            assertEquals(MeasurementCleanup.WARNING, failure.suppressed.single().message)
        }
        assertEquals(1, reconnects)
    }
}
