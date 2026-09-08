package com.burnin.target.correction

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileFilesTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun replacementPublishesAllFilesAndRemovesObsoleteMaps() {
        val dir=File(temp.root,"current")
        ProfileFiles.replace(dir,mapOf("alpha" to byteArrayOf(1),"rgb" to byteArrayOf(2)))
        ProfileFiles.replace(dir,mapOf("alpha" to byteArrayOf(3),"metadata" to byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(3),File(dir,"alpha").readBytes())
        assertArrayEquals(byteArrayOf(4),File(dir,"metadata").readBytes())
        assertFalse(File(dir,"rgb").exists());assertFalse(File(temp.root,"current.backup").exists())
    }
    @Test fun failedStagingPreservesPriorProfile() {
        val dir=File(temp.root,"current")
        ProfileFiles.replace(dir,mapOf("alpha" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { ProfileFiles.replace(dir,mapOf("../escape" to byteArrayOf(2))) }
        assertArrayEquals(byteArrayOf(1),File(dir,"alpha").readBytes());assertFalse(File(temp.root,"escape").exists())
    }
    @Test fun interruptedDirectorySwapRecoversBackup() {
        val backup=File(temp.root,"current.backup").apply{mkdir()};File(backup,"alpha").writeBytes(byteArrayOf(7))
        val current=ProfileFiles.recover(File(temp.root,"current"))
        assertArrayEquals(byteArrayOf(7),File(current,"alpha").readBytes());assertFalse(backup.exists())
    }
    @Test fun committedProfileWinsOverUnremovedBackup() {
        val dir=File(temp.root,"current").apply{mkdir()};File(dir,"alpha").writeBytes(byteArrayOf(2))
        File(temp.root,"current.backup").mkdir()
        assertArrayEquals(byteArrayOf(2),File(ProfileFiles.recover(dir),"alpha").readBytes())
    }
}
