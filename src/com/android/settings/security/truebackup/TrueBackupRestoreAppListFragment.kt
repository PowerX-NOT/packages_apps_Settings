/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import androidx.lifecycle.lifecycleScope
import com.android.settings.dashboard.DashboardFragment
import com.android.settingslib.PrimarySwitchPreference
import com.android.settingslib.widget.TwoTargetPreference.ICON_SIZE_MEDIUM
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LOG_TAG = "TrueBackupRestoreList"

private data class RestoreRow(val packageName: String, val label: String, val icon: Drawable)

class TrueBackupRestoreAppListFragment : DashboardFragment() {

    private val selectedPackages = mutableSetOf<String>()
    private val pollHandler = Handler(Looper.getMainLooper())
    private var operationInProgress = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            val svc = TrueBackupBinder.get()
            if (svc == null) {
                operationInProgress = false
                activity?.invalidateOptionsMenu()
                return
            }
            try {
                if (svc.isOperationInProgress) {
                    pollHandler.postDelayed(this, 1000)
                } else {
                    operationInProgress = false
                    activity?.invalidateOptionsMenu()
                }
            } catch (e: RemoteException) {
                Log.e(LOG_TAG, "poll", e)
                operationInProgress = false
                activity?.invalidateOptionsMenu()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        if (TrueBackupPreferences.getBackupPath(requireContext()) == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_path, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val ctx = requireContext()
            val path = TrueBackupPreferences.getBackupPath(ctx)
            val rows = withContext(Dispatchers.Default) { computeRows(ctx) }
            preferenceScreen?.let { screen ->
                for (row in rows) {
                    screen.addPreference(createPreference(row))
                }
            }
            if (rows.isEmpty() && path != null) {
                if (TrueBackupPaths.resolveAppsDir(path) == null) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.true_backup_toast_invalid_backup_path, path),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        ctx,
                        R.string.true_backup_toast_no_backed_up_apps,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        schedulePollIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        schedulePollIfNeeded()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    private fun schedulePollIfNeeded() {
        val svc = TrueBackupBinder.get() ?: return
        try {
            if (svc.isOperationInProgress) {
                operationInProgress = true
                pollHandler.removeCallbacks(pollRunnable)
                pollHandler.post(pollRunnable)
            }
        } catch (_: RemoteException) {
        }
        activity?.invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.true_backup_restore_list_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.true_backup_restore_start)?.isEnabled =
            !operationInProgress && TrueBackupBinder.get() != null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.true_backup_restore_start) {
            startRestoreForSelection()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun startRestoreForSelection() {
        val svc = TrueBackupBinder.get()
        if (svc == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_service_missing, Toast.LENGTH_LONG).show()
            return
        }
        val path = TrueBackupPreferences.getBackupPath(requireContext())
        if (path == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_path, Toast.LENGTH_LONG).show()
            return
        }
        val targets = selectedPackages.toList()
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_apps_selected, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var started = false
            for (pkg in targets) {
                try {
                    svc.restorePackage(pkg, path)
                    started = true
                } catch (e: RemoteException) {
                    Log.e(LOG_TAG, "restore $pkg", e)
                }
            }
            if (started) {
                operationInProgress = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        R.string.true_backup_status_restore_progress,
                        Toast.LENGTH_SHORT,
                    ).show()
                    pollHandler.removeCallbacks(pollRunnable)
                    pollHandler.postDelayed(pollRunnable, 1000)
                    activity?.invalidateOptionsMenu()
                }
            }
        }
    }

    private fun computeRows(ctx: Context): List<RestoreRow> {
        val pm = ctx.packageManager
        val backupPath = TrueBackupPreferences.getBackupPath(ctx) ?: return emptyList()
        val svc = TrueBackupBinder.get()
        val fromService = mutableListOf<RestoreRow>()
        if (svc != null) {
            try {
                val entries = svc.listBackedUpApps(backupPath)
                if (entries != null) {
                    for (e in entries) {
                        if (e.isNullOrEmpty()) continue
                        var pkg = e
                        var label = e
                        val sep = e.indexOf('|')
                        if (sep >= 0) {
                            pkg = e.substring(0, sep)
                            label = e.substring(sep + 1)
                        }
                        if (pkg.isEmpty()) continue
                        val icon: Drawable = try {
                            pm.getApplicationIcon(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            ctx.getDrawable(android.R.drawable.sym_def_app_icon)!!
                        }
                        val name = if (label.isNotEmpty()) label else pkg
                        fromService.add(RestoreRow(pkg, name, icon))
                    }
                    return fromService.sortedBy { it.label.lowercase() }
                }
            } catch (e: RemoteException) {
                Log.e(LOG_TAG, "listBackedUpApps", e)
            }
        }
        val appsDir = TrueBackupPaths.resolveAppsDir(backupPath)
        if (appsDir == null) {
            return emptyList()
        }
        val out = mutableListOf<RestoreRow>()
        appsDir.listFiles()?.forEach { pkgDir ->
            if (!pkgDir.isDirectory) return@forEach
            val row = loadRowFromBackup(ctx, pm, pkgDir) ?: return@forEach
            out.add(row)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun loadRowFromBackup(ctx: Context, pm: PackageManager, pkgDir: File): RestoreRow? {
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
        return RestoreRow(pkg, name, icon)
    }

    private fun createPreference(row: RestoreRow): PrimarySwitchPreference {
        return PrimarySwitchPreference(requireContext()).apply {
            key = row.packageName
            title = row.label
            summary = row.packageName
            icon = row.icon
            setIconSize(ICON_SIZE_MEDIUM)
            isChecked = selectedPackages.contains(row.packageName)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    selectedPackages.add(row.packageName)
                } else {
                    selectedPackages.remove(row.packageName)
                }
                true
            }
        }
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.EVOLVER

    override fun getPreferenceScreenResId() = R.xml.true_backup_restore_list_settings

    override fun getLogTag() = LOG_TAG
}
