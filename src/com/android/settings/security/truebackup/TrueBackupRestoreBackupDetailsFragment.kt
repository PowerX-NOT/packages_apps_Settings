/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.RemoteException
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.applications.appinfo.AppInfoDashboardFragment
import com.android.settings.dashboard.DashboardFragment
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

private const val LOG_TAG = "TrueBackupRestoreDetails"

/**
 * Shows metadata from [TrueBackupPaths.PACKAGE_RESTORE_CONFIG] for one backup folder and allows
 * deleting that backup after confirmation.
 */
class TrueBackupRestoreBackupDetailsFragment : DashboardFragment() {

    companion object {
        const val ARG_BACKUP_DIR = "backup_dir"
        const val ARG_PACKAGE_NAME = "package_name"

        /** [TrueBackupRestoreAppListFragment] listens for this after a successful delete. */
        const val FRAGMENT_RESULT_KEY = "truebackup_restore_detail_result"
        const val EXTRA_DELETED = "deleted"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        val ctx = requireContext()
        val packageNameArg = arguments?.getString(ARG_PACKAGE_NAME)?.takeIf { it.isNotEmpty() }
        if (packageNameArg == null) {
            Toast.makeText(ctx, R.string.true_backup_toast_backup_folder_missing, Toast.LENGTH_LONG).show()
            return
        }
        val basePath = TrueBackupPreferences.getBackupPath(ctx)
        if (basePath.isNullOrEmpty()) {
            Toast.makeText(ctx, R.string.true_backup_toast_no_path, Toast.LENGTH_LONG).show()
            return
        }

        var appsRoot: File? = TrueBackupPaths.resolveAppsDir(basePath)

        // Hint from list: trust path if the config file exists (do not require resolveAppsDir first).
        var backupDir: File? = arguments?.getString(ARG_BACKUP_DIR)?.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (backupDir != null && !File(backupDir, TrueBackupPaths.PACKAGE_RESTORE_CONFIG).isFile()) {
            backupDir = null
        }
        if (appsRoot == null && backupDir != null) {
            val parent = backupDir.parentFile
            if (parent != null && parent.name == "apps" && parent.isDirectory) {
                appsRoot = parent
            }
        }

        val svc = TrueBackupBinder.get()
        if (backupDir == null && svc != null) {
            try {
                val p = svc.resolveBackupPackageDir(basePath, packageNameArg)
                if (!p.isNullOrEmpty()) {
                    val f = File(p)
                    if (f.isDirectory && File(f, TrueBackupPaths.PACKAGE_RESTORE_CONFIG).isFile()) {
                        backupDir = f
                    }
                }
            } catch (_: RemoteException) {
            }
        }
        if (backupDir == null) {
            backupDir = TrueBackupPaths.findBackupPackageDirLocal(basePath, packageNameArg)
        }
        if (appsRoot == null && backupDir != null) {
            val parent = backupDir.parentFile
            if (parent != null && parent.name == "apps" && parent.isDirectory) {
                appsRoot = parent
            }
        }

        var jsonText: String? = null
        if (backupDir != null) {
            val cf = File(backupDir, TrueBackupPaths.PACKAGE_RESTORE_CONFIG)
            if (cf.isFile()) {
                try {
                    jsonText = String(TrueBackupPaths.readFully(cf), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "read config", e)
                }
            }
        }
        if (jsonText == null && svc != null) {
            try {
                jsonText = svc.readBackupMetadataJson(basePath, packageNameArg)
            } catch (_: RemoteException) {
            }
        }

        if (jsonText.isNullOrEmpty()) {
            Log.w(
                LOG_TAG,
                "no metadata: basePath=$basePath pkg=$packageNameArg " +
                    "appsRoot=${appsRoot?.path} backupDir=${backupDir?.path}",
            )
            Toast.makeText(ctx, R.string.true_backup_toast_backup_folder_missing, Toast.LENGTH_LONG).show()
            return
        }

        if (backupDir == null && svc != null) {
            try {
                val p = svc.resolveBackupPackageDir(basePath, packageNameArg)
                if (!p.isNullOrEmpty()) {
                    val f = File(p)
                    if (f.isDirectory) backupDir = f
                }
            } catch (_: RemoteException) {
            }
        }
        if (backupDir == null) {
            backupDir = TrueBackupPaths.findBackupPackageDirLocal(basePath, packageNameArg)
        }

        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "parse json", e)
            Toast.makeText(ctx, R.string.true_backup_toast_backup_folder_missing, Toast.LENGTH_LONG).show()
            return
        }

        backupDir = resolveBackupDirFromMetadata(backupDir, basePath, packageNameArg, root)
        if (appsRoot == null && backupDir != null) {
            val parent = backupDir.parentFile
            if (parent != null && parent.name == "apps" && parent.isDirectory) {
                appsRoot = parent
            }
        }

        populateFromBackup(backupDir, appsRoot, basePath, root, packageNameArg)
    }

    private fun populateFromBackup(
        backupDir: File?,
        appsRoot: File?,
        basePath: String,
        root: JSONObject,
        packageNameFallback: String,
    ) {
        val screen = preferenceScreen ?: return
        screen.removeAllPreferences()
        val ctx = requireContext()

        var packageName = backupDir?.name ?: packageNameFallback
        var appLabel = packageName
        val pkgInfo = root.optJSONObject("packageInfo")
        if (pkgInfo != null) {
            val label = pkgInfo.optString("appLabel", "").takeIf { it.isNotEmpty() }
                ?: pkgInfo.optString("label", "").takeIf { it.isNotEmpty() }
            val pkgFromJson = pkgInfo.optString("packageName", "").takeIf { it.isNotEmpty() }
            if (pkgFromJson != null) packageName = pkgFromJson
            if (label != null) appLabel = label
        }

        val icon = try {
            ctx.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            ctx.getDrawable(android.R.drawable.sym_def_app_icon)
        }

        val header = Preference(ctx).apply {
            key = "header"
            title = appLabel
            summary = packageName
            this.icon = icon
            isSelectable = false
        }
        screen.addPreference(header)

        val installed = isPackageInstalled(ctx.packageManager, packageName)
        if (installed) {
            val openInfo = Preference(ctx).apply {
                key = "open_app_info"
                setTitle(R.string.true_backup_open_app_info)
                setOnPreferenceClickListener {
                    try {
                        val appInfo = ctx.packageManager.getApplicationInfo(
                            packageName,
                            PackageManager.GET_META_DATA,
                        )
                        AppInfoDashboardFragment.startAppInfoFragment(
                            AppInfoDashboardFragment::class.java,
                            appInfo,
                            ctx,
                            metricsCategory,
                        )
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                    true
                }
            }
            screen.addPreference(openInfo)
        }

        val storageCat = PreferenceCategory(ctx).apply {
            key = "storage_cat"
            setTitle(R.string.true_backup_backup_storage)
        }
        screen.addPreference(storageCat)
        storageCat.addPreference(
            Preference(ctx).apply {
                key = "backup_folder"
                setTitle(R.string.true_backup_backup_folder_label)
                summary = backupDir?.absolutePath
                    ?: root.optJSONObject("backupConfig")?.optString("storagePath", "")
                        ?.takeIf { it.isNotEmpty() }
                    ?: ctx.getString(R.string.true_backup_backup_folder_service_only)
                isSelectable = false
            },
        )

        val dataStates = root.optJSONObject("dataStates")
        val dataStats = root.optJSONObject("dataStats")
        val security = root.optJSONObject("security")
        val backupConfig = root.optJSONObject("backupConfig")

        val metaCat = PreferenceCategory(ctx).apply {
            key = "meta_cat"
            setTitle(R.string.true_backup_backup_json_section)
        }
        screen.addPreference(metaCat)

        val partsCat = PreferenceCategory(ctx).apply {
            key = "parts_cat"
            setTitle(R.string.true_backup_category_backup_parts)
        }
        metaCat.addPreference(partsCat)

        fun partSummary(rootKey: String, statesKey: String, bytesKey: String): String {
            val present = root.optBoolean(rootKey, false) ||
                (dataStates?.optBoolean(statesKey, false) == true)
            val bytes = dataStats?.optLong(bytesKey, 0L) ?: 0L
            return formatBackupPartSummary(present, bytes)
        }

        partsCat.addPreference(
            prefLine(ctx, "part_apk", R.string.true_backup_part_apk, partSummary("apk", "apk", "apkBytes")),
        )
        partsCat.addPreference(
            prefLine(
                ctx,
                "part_user_ce",
                R.string.true_backup_part_user_ce,
                partSummary("user_ce", "userCe", "userBytes"),
            ),
        )
        partsCat.addPreference(
            prefLine(
                ctx,
                "part_user_de",
                R.string.true_backup_part_user_de,
                partSummary("user_de", "userDe", "userDeBytes"),
            ),
        )
        partsCat.addPreference(
            prefLine(
                ctx,
                "part_ext",
                R.string.true_backup_part_ext_data,
                partSummary("ext_data", "externalData", "dataBytes"),
            ),
        )
        partsCat.addPreference(
            prefLine(ctx, "part_obb", R.string.true_backup_part_obb, partSummary("obb", "obb", "obbBytes")),
        )
        partsCat.addPreference(
            prefLine(
                ctx,
                "part_media",
                R.string.true_backup_part_media,
                partSummary("media", "media", "mediaBytes"),
            ),
        )

        val infoCat = PreferenceCategory(ctx).apply {
            key = "info_cat"
            setTitle(R.string.true_backup_category_info)
        }
        screen.addPreference(infoCat)

        val uid = when {
            pkgInfo != null && pkgInfo.has("uid") -> pkgInfo.optInt("uid", -1)
            security != null && security.has("uid") -> security.optInt("uid", -1)
            else -> -1
        }
        infoCat.addPreference(
            prefLine(
                ctx,
                "info_uid",
                R.string.true_backup_info_uid,
                if (uid >= 0) uid.toString() else ctx.getString(R.string.true_backup_value_unknown),
            ),
        )

        val versionName = pkgInfo?.optString("versionName", "")?.takeIf { it.isNotEmpty() }
        val versionCode = if (pkgInfo != null && pkgInfo.has("versionCode")) {
            pkgInfo.optLong("versionCode", 0L)
        } else {
            0L
        }
        val versionSummary = when {
            versionName != null && versionCode > 0L -> "$versionName ($versionCode)"
            versionName != null -> versionName
            versionCode > 0L -> versionCode.toString()
            else -> ctx.getString(R.string.true_backup_value_unknown)
        }
        infoCat.addPreference(
            prefLine(ctx, "info_version", R.string.true_backup_info_version, versionSummary),
        )

        val firstInstall = pkgInfo?.optLong("firstInstallTime", 0L) ?: 0L
        infoCat.addPreference(
            prefLine(
                ctx,
                "info_first_install",
                R.string.true_backup_info_first_installed,
                formatEpochMillis(ctx, firstInstall),
            ),
        )

        val lastUpdate = pkgInfo?.optLong("lastUpdateTime", 0L) ?: 0L
        infoCat.addPreference(
            prefLine(
                ctx,
                "info_last_update",
                R.string.true_backup_info_last_update,
                formatEpochMillis(ctx, lastUpdate),
            ),
        )

        val lastBackup = backupConfig?.optLong("createdAt", 0L) ?: 0L
        infoCat.addPreference(
            prefLine(
                ctx,
                "info_last_backup",
                R.string.true_backup_info_last_backup,
                formatEpochMillis(ctx, lastBackup),
            ),
        )

        val perms = security?.optJSONArray("permissions")
        val permCat = PreferenceCategory(ctx).apply {
            key = "perm_cat"
            setTitle(R.string.true_backup_category_permissions)
        }
        screen.addPreference(permCat)
        if (perms != null && perms.length() > 0) {
            for (i in 0 until perms.length()) {
                val o = perms.optJSONObject(i) ?: continue
                val name = o.optString("name", "").takeIf { it.isNotEmpty() } ?: continue
                val granted = o.optBoolean("granted", false)
                permCat.addPreference(
                    Preference(ctx).apply {
                        key = "perm_$i"
                        title = name
                        summary = ctx.getString(
                            if (granted) {
                                R.string.true_backup_permission_granted
                            } else {
                                R.string.true_backup_permission_denied
                            },
                        )
                        isSelectable = false
                    },
                )
            }
        } else {
            permCat.addPreference(
                Preference(ctx).apply {
                    key = "perm_empty"
                    setTitle(R.string.true_backup_permissions_empty)
                    isSelectable = false
                },
            )
        }

        val canDelete = basePath.isNotEmpty() && packageName.isNotEmpty() &&
            (
                TrueBackupBinder.get() != null ||
                    (backupDir != null &&
                        TrueBackupBackupDeletion.mayDeleteBackup(appsRoot, basePath, backupDir, root))
                )
        if (canDelete) {
            val deletePref = Preference(ctx).apply {
                key = "delete_backup"
                setTitle(R.string.true_backup_delete_backup)
                setSummary(R.string.true_backup_delete_backup_summary)
                setOnPreferenceClickListener {
                    showDeleteConfirmation(backupDir, appsRoot, basePath, root, appLabel, packageName)
                    true
                }
            }
            screen.addPreference(deletePref)
        } else {
            screen.addPreference(
                Preference(ctx).apply {
                    key = "delete_unavailable"
                    setTitle(R.string.true_backup_delete_backup)
                    setSummary(R.string.true_backup_delete_unavailable_summary)
                    isSelectable = false
                },
            )
        }
    }

    private fun showDeleteConfirmation(
        backupDir: File?,
        appsRoot: File?,
        basePath: String,
        root: JSONObject,
        appLabel: String,
        packageName: String,
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.true_backup_delete_confirm_title)
            .setMessage(getString(R.string.true_backup_delete_confirm_message, appLabel))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        TrueBackupBackupDeletion.deleteBackupForPackage(basePath, packageName)
                    }
                    if (ok) {
                        parentFragmentManager.setFragmentResult(
                            FRAGMENT_RESULT_KEY,
                            Bundle().apply {
                                putBoolean(EXTRA_DELETED, true)
                                putString(EXTRA_PACKAGE_NAME, packageName)
                            },
                        )
                        Toast.makeText(
                            requireContext(),
                            R.string.true_backup_status_complete,
                            Toast.LENGTH_SHORT,
                        ).show()
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            R.string.true_backup_delete_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .show()
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.EVOLVER

    override fun getPreferenceScreenResId() = R.xml.true_backup_restore_backup_details

    override fun getLogTag() = LOG_TAG
}

private fun resolveBackupDirFromMetadata(
    current: File?,
    basePath: String,
    packageNameArg: String,
    root: JSONObject,
): File? {
    fun configFile(d: File) = File(d, TrueBackupPaths.PACKAGE_RESTORE_CONFIG)

    if (current != null && current.isDirectory && configFile(current).isFile()) {
        return current
    }

    val sp = root.optJSONObject("backupConfig")
        ?.optString("storagePath", null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (sp != null) {
        val f = File(sp)
        if (f.isDirectory && configFile(f).isFile()) {
            return f
        }
    }

    val pkg = root.optJSONObject("backupConfig")
        ?.optString("packageName", null)
        ?.takeIf { it.isNotEmpty() }
        ?: packageNameArg
    return TrueBackupPaths.findBackupPackageDirLocal(basePath, pkg) ?: current?.takeIf { it.isDirectory }
}

private fun prefLine(ctx: Context, key: String, titleRes: Int, summary: CharSequence): Preference =
    Preference(ctx).apply {
        this.key = key
        setTitle(titleRes)
        this.summary = summary
        isSelectable = false
    }

private fun formatBackupPartSummary(present: Boolean, bytes: Long): String {
    val tf = if (present) "true" else "false"
    return "$tf · ${bytesToMbString(bytes)}"
}

private fun bytesToMbString(bytes: Long): String {
    if (bytes <= 0L) return "0.00 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.2f MB", mb)
}

private fun formatEpochMillis(ctx: Context, ms: Long): String {
    if (ms <= 0L) return ctx.getString(R.string.true_backup_value_unknown)
    return DateUtils.formatDateTime(
        ctx,
        ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
}

private fun PreferenceScreen.removeAllPreferences() {
    while (preferenceCount > 0) {
        removePreference(getPreference(0))
    }
}

private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
    return try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
