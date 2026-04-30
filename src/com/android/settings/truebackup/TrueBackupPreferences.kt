/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.truebackup

import android.content.Context
import android.net.Uri

object TrueBackupPreferences {
    const val PREF_NAME = "TrueBackupPrefs"
    const val KEY_BACKUP_PATH = "backup_path"

    @JvmStatic
    fun getBackupPath(context: Context): String? {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKUP_PATH, null)
        return if (p.isNullOrBlank()) null else p
    }

    @JvmStatic
    fun setBackupPath(context: Context, path: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BACKUP_PATH, path)
            .apply()
    }

    /** Best-effort path for SAF primary storage tree URIs. */
    @JvmStatic
    fun uriTreeToDisplayPath(uri: Uri?): String? {
        if (uri == null) return null
        val path = uri.path ?: return null
        val idx = path.indexOf(':')
        return if (idx >= 0) {
            "/data/media/0/" + path.substring(idx + 1)
        } else {
            path
        }
    }
}
