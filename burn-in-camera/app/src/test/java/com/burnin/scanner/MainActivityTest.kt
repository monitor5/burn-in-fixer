package com.burnin.scanner

import android.widget.Button
import android.widget.EditText
import com.burnin.scanner.util.AppLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MainActivityTest {
    @Test fun disconnectedLaunchAndEmptyIpCannotStartMeasurement() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        try {
            a.findViewById<EditText>(R.id.editIp).setText("   ")
            a.findViewById<Button>(R.id.btnConnect).performClick()
            assertFalse(a.findViewById<Button>(R.id.btnMeasure).isEnabled)
            assertFalse(a.findViewById<Button>(R.id.btnColorCalibrate).isEnabled)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun savedAddressIsRestoredAndDestroyedActivityDoesNotRetainLogListener() {
        RuntimeEnvironment.getApplication().getSharedPreferences("app",0).edit().putString("last_adjust_ip","192.0.2.1").commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("192.0.2.1",controller.get().findViewById<EditText>(R.id.editIp).text.toString())
        assertNotNull(AppLog.listener);controller.pause().stop().destroy();assertNull(AppLog.listener)
    }
    @Test fun destroyingOlderActivityCannotClearNewerActivityListener() {
        val first=Robolectric.buildActivity(MainActivity::class.java).setup()
        val second=Robolectric.buildActivity(MainActivity::class.java).setup();val newest=AppLog.listener
        first.pause().stop().destroy();assertSame(newest,AppLog.listener)
        second.pause().stop().destroy();assertNull(AppLog.listener)
    }
}
