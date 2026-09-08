package com.burnin.scanner.measure

import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CorrectionMapTest {
    @Test fun worseRgbCandidatesLeaveBaselineWithoutRgbArtifact() {
        val selection = MeasurementPolicy.Selection(floatArrayOf(1f, 1f), 1f)
        assertFalse(selection.consider(floatArrayOf(.95f, .95f), 2f))
        val selected = selection.correctionMap(mapOf(0 to floatArrayOf(.9f, .9f)), .5f)
        assertTrue(selected.isBaseline)
        assertEquals(0f, selected.channelWeight, 0f)
        assertNull(selected.rgbPng(2, 1, 2, 1, .1f))
        val bytes = selected.alphaPng(2, 1, 2, 1, .1f)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(Color.BLACK, bitmap.getPixel(0, 0))
        assertEquals(Color.BLACK, bitmap.getPixel(1, 0))
        bitmap.recycle()
    }

    @Test fun selectedChannelsAreFrozenAndEncodeTheEvaluatedMixture() {
        val selection = MeasurementPolicy.Selection(floatArrayOf(1f), 2f)
        assertTrue(selection.consider(floatArrayOf(.95f), 1f))
        val red = floatArrayOf(.9f)
        val selected = selection.correctionMap(mapOf(0 to red), .5f)
        red[0] = 1f
        selection.consider(floatArrayOf(.99f), .5f)
        assertFalse(selected.isBaseline)
        assertEquals(.5f, selected.channelWeight, 0f)
        val bytes = selected.rgbPng(1, 1, 1, 1, .1f)!!
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        val pixel = bitmap.getPixel(0, 0)
        assertEquals(191, Color.red(pixel)) // gain .925, attenuation .075 / .1
        assertEquals(128, Color.green(pixel))
        assertEquals(128, Color.blue(pixel))
        bitmap.recycle()
    }
    @Test fun actualBaselineTransmissionMatchesArtifactAndKeepsCorrectionDisabled() = kotlinx.coroutines.runBlocking {
        val selected = MeasurementPolicy.Selection(floatArrayOf(1f), 1f)
            .correctionMap(mapOf(0 to floatArrayOf(.95f)), .5f)
        java.net.ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val received = executor.submit<List<org.json.JSONObject>> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    List(3) {
                        val request = org.json.JSONObject(reader.readLine())
                        socket.getOutputStream().write(org.json.JSONObject().put("ok", true)
                            .put("cmd", request.getString("cmd")).toString().plus("\n").toByteArray())
                        request
                    }
                }
            }
            val client = com.burnin.scanner.net.ControlClient("127.0.0.1", server.localPort)
            try {
                client.connect()
                val activity = org.robolectric.Robolectric.buildActivity(MeasurementActivity::class.java).get()
                val method = MeasurementActivity::class.java.declaredMethods.single { it.name == "applyGainMap" }
                    .apply { isAccessible = true }
                kotlin.coroutines.suspendCoroutine<Unit> { continuation ->
                    try {
                        val result = method.invoke(activity, client, selected, 1, 1, 1, 1, continuation)
                        if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED)
                            continuation.resumeWith(Result.success(Unit))
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        continuation.resumeWith(Result.failure(e.cause!!))
                    }
                }
                val requests = received.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(listOf("APPLY_CORRECTION_MAP", "DISABLE_CORRECTION", "SHOW_PATTERN"),
                    requests.map { it.getString("cmd") })
                assertFalse(requests[0].has("rgbData"))
                assertArrayEquals(selected.alphaPng(1, 1, 1, 1, .1f),
                    android.util.Base64.decode(requests[0].getString("data"), android.util.Base64.DEFAULT))
                assertNull(selected.rgbPng(1, 1, 1, 1, .1f))
            } finally { client.close(); server.close(); executor.shutdownNow() }
        }
    }

}
