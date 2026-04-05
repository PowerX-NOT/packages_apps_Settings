/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

object TrueBackupPaths {
    const val PACKAGE_RESTORE_CONFIG = "package_restore_config.json"

    @JvmStatic
    fun readFully(f: File): ByteArray {
        FileInputStream(f).use { input ->
            ByteArrayOutputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                return output.toByteArray()
            }
        }
    }

    @JvmStatic
    fun resolveAppsDir(basePath: String?): File? {
        if (basePath == null) return null
        val base = File(basePath)

        if (base.isDirectory && base.name == "apps") {
            return base
        }

        if (base.isDirectory && base.name == "backup") {
            val candidate = File(base, "apps")
            if (candidate.isDirectory) return candidate
        }

        val candidate1 = File(File(base, "backup"), "apps")
        if (candidate1.isDirectory) return candidate1

        val candidate2 = File(base, "apps")
        if (candidate2.isDirectory) return candidate2

        return null
    }
}
