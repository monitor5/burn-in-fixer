package com.burnin.target.correction

import java.io.File

/** Recoverable directory replacement: an interrupted publish leaves the previous profile readable. */
internal object ProfileFiles {
    fun recover(directory: File): File {
        val backup = File(directory.parentFile, directory.name + ".backup")
        if (!directory.exists() && backup.exists()) check(backup.renameTo(directory)) { "profile recovery failed" }
        return directory
    }

    @Synchronized fun replace(directory: File, files: Map<String, ByteArray>) {
        recover(directory)
        val parent = requireNotNull(directory.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "profile parent unavailable" }
        val staging = File(parent, directory.name + ".pending")
        val backup = File(parent, directory.name + ".backup")
        check(!staging.exists() || staging.deleteRecursively()) { "stale staging cleanup failed" }
        check(staging.mkdir()) { "profile staging unavailable" }
        try {
            files.forEach { (name, bytes) ->
                require(File(name).name == name && name != "." && name != "..")
                File(staging, name).outputStream().use { output -> output.write(bytes); output.fd.sync() }
            }
            check(!backup.exists() || backup.deleteRecursively()) { "profile backup cleanup failed" }
            if (directory.exists()) check(directory.renameTo(backup)) { "profile backup failed" }
            if (!staging.renameTo(directory)) {
                recover(directory)
                error("profile publish failed")
            }
            backup.deleteRecursively()
        } finally { staging.deleteRecursively() }
    }
}
