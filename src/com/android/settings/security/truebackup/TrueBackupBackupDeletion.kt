/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.os.RemoteException
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject

private const val TAG = "TrueBackupDeletion"

object TrueBackupBackupDeletion {

    /** Same rules as the restore-details screen: path must be under backup base or metadata storagePath. */
    fun mayDeleteBackup(
        appsRoot: File?,
        basePath: String,
        backupDir: File,
        root: JSONObject?,
    ): Boolean {
        if (appsRoot != null && TrueBackupPaths.isBackupPackageDirUnderAppsRoot(appsRoot, backupDir)) {
            return true
        }
        if (TrueBackupPaths.isUnderBackupBasePath(basePath, backupDir)) {
            return true
        }
        val sp = root?.optJSONObject("backupConfig")
            ?.optString("storagePath", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return try {
            backupDir.canonicalFile == File(sp).canonicalFile
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes one app’s backup folder under [basePath]; uses the system service when available. */
    fun deleteBackupForPackage(basePath: String, packageName: String): Boolean {
        val svc = TrueBackupBinder.get()
        if (svc != null) {
            try {
                if (svc.deleteBackupPackage(basePath, packageName)) return true
            } catch (e: RemoteException) {
                Log.e(TAG, "deleteBackupPackage", e)
            }
            val json = try {
                svc.readBackupMetadataJson(basePath, packageName)
            } catch (e: RemoteException) {
                Log.e(TAG, "readBackupMetadataJson", e)
                null
            }
            if (!json.isNullOrEmpty()) {
                try {
                    val root = JSONObject(json)
                    val sp = root.optJSONObject("backupConfig")
                        ?.optString("storagePath", null)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    if (sp != null) {
                        try {
                            if (svc.deleteBackupPackageAtPath(basePath, sp)) return true
                        } catch (e: RemoteException) {
                            Log.e(TAG, "deleteBackupPackageAtPath", e)
                        }
                    }
                    val backupDir = TrueBackupPaths.findBackupPackageDirLocal(basePath, packageName)
                    val appsRoot = TrueBackupPaths.resolveAppsDir(basePath)
                    if (backupDir != null && mayDeleteBackup(appsRoot, basePath, backupDir, root)) {
                        if (backupDir.deleteRecursively()) return true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "service delete fallback", e)
                }
            }
        }
        val backupDir = TrueBackupPaths.findBackupPackageDirLocal(basePath, packageName)
        if (backupDir == null || !backupDir.isDirectory) return false
        val appsRoot = TrueBackupPaths.resolveAppsDir(basePath)
        var root: JSONObject? = null
        val cf = File(backupDir, TrueBackupPaths.PACKAGE_RESTORE_CONFIG)
        if (cf.isFile) {
            try {
                root = JSONObject(String(TrueBackupPaths.readFully(cf), StandardCharsets.UTF_8))
            } catch (_: Exception) {
            }
        }
        if (!mayDeleteBackup(appsRoot, basePath, backupDir, root)) return false
        return try {
            backupDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "deleteRecursively", e)
            false
        }
    }
}
