/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.truebackup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.applications.appinfo.AppInfoDashboardFragment
import androidx.lifecycle.lifecycleScope
import com.android.settings.dashboard.DashboardFragment
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LOG_TAG = "TrueBackupBackupList"

private data class BackupRow(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val installed: Boolean,
    val hasBackup: Boolean,
)

class TrueBackupBackupAppListFragment : DashboardFragment() {

    /** In-memory only; cleared when leaving this screen (new fragment instance). */
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        lifecycleScope.launch {
            val ctx = requireContext()
            val rows = withContext(Dispatchers.Default) {
                computeRows(ctx.packageManager, ctx)
            }
            selectedPackages.retainAll { pkg -> rows.any { it.packageName == pkg } }
            preferenceScreen?.let { screen ->
                for (row in rows) {
                    screen.addPreference(createPreference(row))
                }
            }
        }
        schedulePollIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        schedulePollIfNeeded()
    }

    private fun schedulePollIfNeeded() {
        TrueBackupOperationPoller.resumeWatchingIfOperationInProgress(requireContext())
        activity?.invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.true_backup_backup_list_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val svc = TrueBackupBinder.get()
        val hasPw = try {
            svc?.isRegistrationPasswordSet == true
        } catch (_: RemoteException) {
            false
        }
        menu.findItem(R.id.true_backup_start)?.isEnabled = svc != null && hasPw
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.true_backup_start) {
            startBackupForSelection()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun startBackupForSelection() {
        val svc = TrueBackupBinder.get()
        if (svc == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_service_missing, Toast.LENGTH_LONG).show()
            return
        }
        val hasPw = try {
            svc.isRegistrationPasswordSet
        } catch (_: RemoteException) {
            false
        }
        if (!hasPw) {
            Toast.makeText(requireContext(), R.string.true_backup_password_required, Toast.LENGTH_LONG).show()
            return
        }
        val path = TrueBackupPreferences.getBackupPath(requireContext())
        if (path == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_path, Toast.LENGTH_LONG).show()
            return
        }
        if (selectedPackages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_apps_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val pm = requireContext().packageManager
        val toQueue = selectedPackages.toList()
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            var startedAny = false
            for (pkg in toQueue) {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                    svc.backupPackage(pkg, path)
                    startedAny = true
                    val label = info.loadLabel(pm).toString()
                    withContext(Dispatchers.Main) {
                        TrueBackupOperationPoller.onUserQueuedOperation(
                            appCtx,
                            TrueBackupOperationPoller.KIND_BACKUP,
                            pkg,
                            label,
                        )
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    continue
                } catch (e: RemoteException) {
                    Log.e(LOG_TAG, "backup $pkg", e)
                }
            }
            withContext(Dispatchers.Main) {
                if (startedAny) {
                    Toast.makeText(
                        requireContext(),
                        R.string.true_backup_status_backup_progress,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.true_backup_toast_no_apps_selected,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                activity?.invalidateOptionsMenu()
            }
        }
    }

    private fun computeRows(pm: PackageManager, ctx: Context): List<BackupRow> {
        val backupPath = TrueBackupPreferences.getBackupPath(ctx)
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val byPkg = linkedMapOf<String, BackupRow>()
        for (info in apps) {
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val pkg = info.packageName
            byPkg[pkg] = BackupRow(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                icon = info.loadIcon(pm),
                installed = true,
                hasBackup = false,
            )
        }
        if (backupPath != null) {
            val appsDir = TrueBackupPaths.resolveAppsDir(backupPath)
            appsDir?.listFiles()?.forEach { pkgDir ->
                if (!pkgDir.isDirectory) return@forEach
                val dirName = pkgDir.name
                val existing = byPkg[dirName]
                if (existing != null) {
                    byPkg[dirName] = existing.copy(hasBackup = true)
                } else {
                    val fromBackup = loadRowFromBackup(ctx, pm, pkgDir)
                    if (fromBackup != null) {
                        byPkg[fromBackup.packageName] = fromBackup.copy(hasBackup = true)
                    }
                }
            }
        }
        return byPkg.values.sortedBy { it.label.lowercase() }
    }

    private fun loadRowFromBackup(ctx: Context, pm: PackageManager, pkgDir: File): BackupRow? {
        val config = File(pkgDir, TrueBackupPaths.PACKAGE_RESTORE_CONFIG)
        if (!config.isFile) return null
        var pkg = pkgDir.name
        var name = pkg
        try {
            val json = String(TrueBackupPaths.readFully(config), StandardCharsets.UTF_8)
            val root = JSONObject(json)
            val pkgInfo = root.optJSONObject("packageInfo")
            if (pkgInfo != null) {
                val label = pkgInfo.optString("appLabel", null)?.takeIf { it.isNotEmpty() }
                    ?: pkgInfo.optString("label", null)?.takeIf { it.isNotEmpty() }
                val pkgFromJson = pkgInfo.optString("packageName", null)?.takeIf { it.isNotEmpty() }
                if (pkgFromJson != null) pkg = pkgFromJson
                if (label != null) name = label
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "parse ${pkgDir.name}", e)
        }
        val icon: Drawable = try {
            pm.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            ctx.getDrawable(android.R.drawable.sym_def_app_icon)!!
        }
        val installed = isPackageInstalled(pm, pkg)
        return BackupRow(pkg, name, icon, installed, hasBackup = true)
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun createPreference(row: BackupRow): TrueBackupAppSelectorPreference {
        return TrueBackupAppSelectorPreference(requireContext()).apply {
            key = row.packageName
            title = row.label
            summary = null
            icon = row.icon
            isPersistent = false
            isEnabled = row.installed
            isChecked = selectedPackages.contains(row.packageName)
            setOnClickListener { emitter ->
                val key = emitter.key ?: return@setOnClickListener
                val on = !emitter.isChecked
                emitter.isChecked = on
                if (on) {
                    selectedPackages.add(key)
                } else {
                    selectedPackages.remove(key)
                }
            }
            if (row.installed) {
                onContentClick = {
                    try {
                        val appInfo = requireContext().packageManager.getApplicationInfo(
                            row.packageName,
                            PackageManager.GET_META_DATA,
                        )
                        AppInfoDashboardFragment.startAppInfoFragment(
                            AppInfoDashboardFragment::class.java,
                            appInfo,
                            requireContext(),
                            metricsCategory,
                        )
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            } else {
                onContentClick = null
            }
        }
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.EVOLVER

    override fun getPreferenceScreenResId() = R.xml.true_backup_backup_list_settings

    override fun getLogTag() = LOG_TAG
}
