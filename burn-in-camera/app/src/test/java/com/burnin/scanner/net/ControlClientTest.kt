package com.burnin.scanner.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ControlClientTest {
    private fun exchange(serverAction:(Socket)->Unit, action:(ControlClient)->Unit) {
        ServerSocket(0).use { server ->
            val executor=Executors.newSingleThreadExecutor()
            val future=executor.submit { server.accept().use { it.soTimeout=3000; serverAction(it) } }
            val client=ControlClient("127.0.0.1",server.localPort)
            try { client.connect();action(client);future.get(5,TimeUnit.SECONDS) }
            finally { client.close();executor.shutdownNow() }
        }
    }
    private fun reply(socket:Socket,text:String) { socket.getOutputStream().write((text+"\n").toByteArray(Charsets.UTF_8));socket.getOutputStream().flush() }
    @Test fun newlineProtocolPreservesUnicodeAndCorrelatesCommand() {
        exchange({ socket ->
            val request=JSONObject(socket.getInputStream().bufferedReader().readLine())
            assertEquals("HELLO",request.getString("cmd"));assertEquals("측정 기기",request.getString("name"))
            reply(socket,"{\"ok\":true,\"cmd\":\"HELLO\",\"name\":\"대상\"}")
        }) { client -> assertEquals("대상",client.command("HELLO","name" to "측정 기기").getString("name")) }
    }
    @Test fun wrongCommandMalformedJsonAndNegativeAckInvalidateConnection() {
        for(response in listOf("{\"ok\":true,\"cmd\":\"OTHER\"}","not json","{\"ok\":false,\"cmd\":\"HELLO\",\"error\":\"거부\"}")) {
            exchange({ s -> s.getInputStream().bufferedReader().readLine();reply(s,response) }) { client ->
                assertThrows(Exception::class.java) { client.command("HELLO") };assertFalse(client.isConnected)
                assertThrows(Exception::class.java) { client.command("HELLO") }
            }
        }
    }
    @Test fun peerEofFailsAndDisconnects() {
        exchange({ s -> s.getInputStream().bufferedReader().readLine() }) { client ->
            assertThrows(IllegalStateException::class.java) { client.command("HELLO") };assertFalse(client.isConnected)
        }
    }
    @Test fun timeoutCannotLeaveAStaleAckForNextRequest() {
        val timedOut=CountDownLatch(1)
        exchange({ s ->
            s.getInputStream().bufferedReader().readLine();assertTrue(timedOut.await(3,TimeUnit.SECONDS))
            runCatching { reply(s,"{\"ok\":true,\"cmd\":\"HELLO\"}") }
        }) { client ->
            try { assertThrows(SocketTimeoutException::class.java) { client.request(JSONObject().put("cmd","HELLO"),50) } }
            finally { timedOut.countDown() }
            assertFalse(client.isConnected);assertThrows(Exception::class.java) { client.command("HELLO") }
        }
    }
    @Test fun closeInterruptsBlockedRequestWithoutWaitingForItsTimeout() {
        val received=CountDownLatch(1);val release=CountDownLatch(1)
        exchange({ s -> s.getInputStream().bufferedReader().readLine();received.countDown();assertTrue(release.await(3,TimeUnit.SECONDS)) }) { client ->
            val executor=Executors.newSingleThreadExecutor()
            val pending=executor.submit { assertThrows(Exception::class.java) { client.request(JSONObject().put("cmd","HELLO"),10000) } }
            try { assertTrue(received.await(3,TimeUnit.SECONDS));client.close();pending.get(2,TimeUnit.SECONDS);assertFalse(client.isConnected) }
            finally { release.countDown();executor.shutdownNow() }
        }
    }
}
