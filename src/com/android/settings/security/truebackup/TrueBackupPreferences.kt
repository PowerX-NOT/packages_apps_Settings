/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
import android.net.Uri

object TrueBackupPreferences {
    const val PREF_NAME = "TrueBackupPrefs"
    const val KEY_BACKUP_PATH = "backup_path"
    const val KEY_SELECTED_PACKAGE = "selected_package"
    const val KEY_SELECTED_PACKAGES = "selected_packages"

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

    /** Legacy single selection; prefer [getSelectedPackages]. */
    @JvmStatic
    fun getSelectedPackage(context: Context): String? {
        val set = getSelectedPackages(context)
        return set.singleOrNull()
    }

    @JvmStatic
    fun setSelectedPackage(context: Context, packageName: String?) {
        if (packageName.isNullOrBlank()) {
            setSelectedPackages(context, emptySet())
        } else {
            setSelectedPackages(context, setOf(packageName))
        }
    }

    /**
     * Packages checked in backup / restore app lists. Migrates from legacy [KEY_SELECTED_PACKAGE]
     * when the set is empty.
     */
    @JvmStatic
    fun getSelectedPackages(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val fromSet = prefs.getStringSet(KEY_SELECTED_PACKAGES, null)
        val result = fromSet?.toMutableSet() ?: mutableSetOf()
        if (result.isEmpty()) {
            val legacy = prefs.getString(KEY_SELECTED_PACKAGE, null)?.takeIf { it.isNotBlank() }
            if (legacy != null) {
                result.add(legacy)
            }
        }
        return result
    }

    @JvmStatic
    fun setSelectedPackages(context: Context, packages: Collection<String>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove(KEY_SELECTED_PACKAGE)
        if (packages.isEmpty()) {
            prefs.remove(KEY_SELECTED_PACKAGES)
        } else {
            prefs.putStringSet(KEY_SELECTED_PACKAGES, HashSet(packages))
        }
        prefs.apply()
    }
}
