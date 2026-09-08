package com.burnin.target.net

import com.burnin.target.DeviceRole
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ControlServerTest {
    @Before fun setup() {
        ControlServer::class.java.getDeclaredField("appContext").apply{isAccessible=true}.set(ControlServer,RuntimeEnvironment.getApplication())
    }
    private fun command(cmd:String,vararg values:Pair<String,Any>):JSONObject {
        val message=JSONObject().put("cmd",cmd);values.forEach{message.put(it.first,it.second)}
        return ControlServer::class.java.getDeclaredMethod("handleMessage",JSONObject::class.java).apply{isAccessible=true}.invoke(ControlServer,message) as JSONObject
    }
    @Test fun helloAndScreenInfoExposeMatchingCommandAndDeviceRole() {
        DeviceRole.set(RuntimeEnvironment.getApplication(),Protocol.ROLE_REFERENCE)
        for(cmd in listOf(Protocol.CMD_HELLO,Protocol.CMD_SCREEN_INFO)) {
            val reply=command(cmd);assertTrue(reply.getBoolean("ok"));assertEquals(cmd,reply.getString("cmd"))
            assertEquals(Protocol.ROLE_REFERENCE,reply.getString("role"));assertTrue(reply.getJSONObject("screen").getInt("width")>0)
            assertFalse(reply.getJSONObject("correction").getBoolean("loaded"))
        }
    }
    @Test fun referenceRejectsEveryCorrectionMutationBeforePayloadParsing() {
        DeviceRole.set(RuntimeEnvironment.getApplication(),Protocol.ROLE_REFERENCE)
        for(cmd in listOf(Protocol.CMD_APPLY_MAP,Protocol.CMD_APPLY_WHITE_BALANCE,Protocol.CMD_CLEAR_WHITE_BALANCE,Protocol.CMD_ENABLE_CORRECTION,Protocol.CMD_ENABLE_OVERLAY)) {
            val reply=command(cmd);assertFalse(cmd,reply.getBoolean("ok"));assertEquals(cmd,reply.getString("cmd"));assertTrue(reply.getString("error").isNotBlank())
        }
    }
    @Test fun unknownPatternUnknownCommandAndMissingMapAreRejected() {
        DeviceRole.set(RuntimeEnvironment.getApplication(),Protocol.ROLE_ADJUSTMENT)
        assertFalse(command("UNKNOWN").getBoolean("ok"))
        assertFalse(command(Protocol.CMD_SHOW_PATTERN,"pattern" to "gray-10").getBoolean("ok"))
        assertFalse(command(Protocol.CMD_ENABLE_CORRECTION).getBoolean("ok"))
    }
    @Test fun malformedStoredRoleFallsBackToAdjustmentAndDisableRemainsAllowed() {
        DeviceRole.set(RuntimeEnvironment.getApplication(),"invalid")
        assertEquals(Protocol.ROLE_ADJUSTMENT,DeviceRole.get(RuntimeEnvironment.getApplication()))
        assertTrue(command(Protocol.CMD_DISABLE_CORRECTION).getBoolean("ok"))
        assertTrue(command(Protocol.CMD_DISABLE_OVERLAY).getBoolean("ok"))
    }
}
