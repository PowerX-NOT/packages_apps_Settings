/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

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

    /**
     * Finds a per-package backup directory containing [PACKAGE_RESTORE_CONFIG] using the same
     * scan logic as the system service, without binder (fallback when service is unavailable).
     */
    @JvmStatic
    fun findBackupPackageDirLocal(basePath: String?, packageName: String): File? {
        if (basePath.isNullOrBlank() || packageName.isEmpty()) return null
        for (appsDir in candidateAppsDirs(basePath)) {
            findInAppsDir(appsDir, packageName)?.let { return it }
        }
        return null
    }

    /** Tries [resolveAppsDir] plus explicit `apps` and `backup/apps` under [basePath]. */
    private fun candidateAppsDirs(basePath: String): List<File> {
        val base = File(basePath)
        val out = LinkedHashSet<File>()
        resolveAppsDir(basePath)?.let { out.add(it) }
        val directApps = File(base, "apps")
        if (directApps.isDirectory) out.add(directApps)
        val underBackup = File(File(base, "backup"), "apps")
        if (underBackup.isDirectory) out.add(underBackup)
        return out.toList()
    }

    private fun findInAppsDir(appsDir: File, packageName: String): File? {
        val direct = File(appsDir, packageName)
        if (direct.isDirectory && File(direct, PACKAGE_RESTORE_CONFIG).isFile()) {
            return direct
        }
        appsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val cfg = File(dir, PACKAGE_RESTORE_CONFIG)
            if (!cfg.isFile) return@forEach
            try {
                val json = String(readFully(cfg), StandardCharsets.UTF_8)
                val root = JSONObject(json)
                val pi = root.optJSONObject("packageInfo")
                val p = pi?.optString("packageName", null)?.takeIf { it.isNotEmpty() } ?: dir.name
                if (p == packageName) return dir
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Best-effort: [dir] is under the configured backup tree (for UI paths that differ slightly). */
    @JvmStatic
    fun isUnderBackupBasePath(basePath: String, dir: File): Boolean {
        return try {
            val base = File(basePath).canonicalFile
            val leaf = dir.canonicalFile
            val basePathStr = base.path
            leaf.path == basePathStr ||
                leaf.path.startsWith(basePathStr + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ensures [packageDir] is a real directory under [appsRoot] (prevents deleting arbitrary paths).
     */
    @JvmStatic
    fun isBackupPackageDirUnderAppsRoot(appsRoot: File, packageDir: File): Boolean {
        return try {
            val root = appsRoot.canonicalFile
            val pkg = packageDir.canonicalFile
            pkg.isDirectory && (pkg.path == root.path || pkg.path.startsWith(root.path + File.separator))
        } catch (_: Exception) {
            false
        }
    }
}
