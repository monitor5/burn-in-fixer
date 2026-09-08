package com.burnin.scanner.report

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReportStoreTest {
    private fun dir()=ReportStore.newSessionDir(RuntimeEnvironment.getApplication())
    @Test fun sessionsCreatedInSameSecondNeverCollide() {
        val dirs=(0..20).map{dir()};assertEquals(21,dirs.toSet().size);assertTrue(dirs.all{it.isDirectory})
    }
    @Test fun successfulSavePreservesBytesAndCommitsJsonLast() {
        val dir=dir();val bytes=byteArrayOf(0,1,-1)
        ReportStore.save(dir,JSONObject().put("device","측정"),mapOf("map.png" to bytes))
        assertArrayEquals(bytes,File(dir,"map.png").readBytes())
        assertEquals("측정",JSONObject(File(dir,"report.json").readText()).getString("device"))
        assertFalse(File(dir,".pending").exists())
        assertThrows(IllegalArgumentException::class.java) { ReportStore.save(dir,JSONObject(),emptyMap()) }
    }
    @Test fun failedPublishLeavesNoSuccessReportOrPartialNewFiles() {
        val dir=dir();File(dir,"blocked.png").mkdir()
        assertThrows(IllegalStateException::class.java) {
            ReportStore.save(dir,JSONObject(),linkedMapOf("ok.png" to byteArrayOf(1),"blocked.png" to byteArrayOf(2)))
        }
        assertFalse(File(dir,"report.json").exists());assertFalse(File(dir,"ok.png").exists());assertTrue(File(dir,"blocked.png").isDirectory)
    }
    @Test fun traversalAndReservedNamesAreRejectedBeforeWriting() {
        for(name in listOf("../escape","/escape","report.json",".","..",".pending")) {
            val dir=dir();assertThrows(IllegalArgumentException::class.java) { ReportStore.save(dir,JSONObject(),mapOf(name to byteArrayOf(1))) }
            assertTrue(dir.listFiles()!!.isEmpty())
        }
    }
}
