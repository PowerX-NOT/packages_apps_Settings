/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.truebackup

import android.os.RemoteException
import android.util.Log
import java.io.File
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

    /** Deletes one app's backup folder under [basePath] via system service only. */
    fun deleteBackupForPackage(basePath: String, packageName: String): Boolean {
        val svc = TrueBackupBinder.get()
        if (svc == null) {
            Log.w(TAG, "deleteBackupForPackage: service unavailable")
            return false
        }
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
        if (json.isNullOrEmpty()) return false
        return try {
            val root = JSONObject(json)
            val sp = root.optJSONObject("backupConfig")
                ?.optString("storagePath", null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return false
            svc.deleteBackupPackageAtPath(basePath, sp)
        } catch (e: Exception) {
            Log.e(TAG, "deleteBackupPackageAtPath", e)
            false
        }
    }
}
