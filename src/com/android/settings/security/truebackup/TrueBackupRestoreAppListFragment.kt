/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
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
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import com.android.settings.core.SubSettingLauncher
import com.android.settings.dashboard.DashboardFragment
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LOG_TAG = "TrueBackupRestoreList"

private data class RestoreRow(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val installed: Boolean,
    /** Directory under `…/apps/` containing [TrueBackupPaths.PACKAGE_RESTORE_CONFIG], or null. */
    val backupPackageDir: File?,
)

class TrueBackupRestoreAppListFragment : DashboardFragment() {

    /** In-memory only; cleared when leaving this screen (new fragment instance). */
    private val selectedPackages = mutableSetOf<String>()
    /** Skip one [onResume] after [onCreatePreferences] to avoid double [populateRestoreList]. */
    private var skipNextResumeRefresh = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Details opens in SubSettings (separate activity), so fragment results never reach here;
        // we refresh the list in onResume when returning from that activity.
        parentFragmentManager.setFragmentResultListener(
            TrueBackupRestoreBackupDetailsFragment.FRAGMENT_RESULT_KEY,
            this,
        ) { _, bundle ->
            if (bundle.getBoolean(TrueBackupRestoreBackupDetailsFragment.EXTRA_DELETED, false)) {
                val deletedPkg = bundle.getString(TrueBackupRestoreBackupDetailsFragment.EXTRA_PACKAGE_NAME)
                if (deletedPkg != null) {
                    selectedPackages.remove(deletedPkg)
                }
                populateRestoreList()
            }
        }
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        skipNextResumeRefresh = true
        if (TrueBackupPreferences.getBackupPath(requireContext()) == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_path, Toast.LENGTH_LONG).show()
            return
        }
        populateRestoreList()
        schedulePollIfNeeded()
    }

    private fun populateRestoreList() {
        lifecycleScope.launch {
            val ctx = requireContext()
            val path = TrueBackupPreferences.getBackupPath(ctx)
            val rows = withContext(Dispatchers.Default) { computeRows(ctx) }
            selectedPackages.retainAll { pkg -> rows.any { it.packageName == pkg } }
            preferenceScreen?.let { screen ->
                screen.removeAllPreferences()
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
    }

    override fun onResume() {
        super.onResume()
        TrueBackupOperationPoller.setOnAllOperationsIdleListener {
            if (isAdded) {
                populateRestoreList()
                activity?.invalidateOptionsMenu()
            }
        }
        schedulePollIfNeeded()
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
            return
        }
        if (TrueBackupPreferences.getBackupPath(requireContext()) != null) {
            populateRestoreList()
        }
    }

    override fun onPause() {
        TrueBackupOperationPoller.setOnAllOperationsIdleListener(null)
        super.onPause()
    }

    private fun schedulePollIfNeeded() {
        TrueBackupOperationPoller.resumeWatchingIfOperationInProgress(requireContext())
        activity?.invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.true_backup_restore_list_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val pathOk = TrueBackupPreferences.getBackupPath(requireContext()) != null
        val svc = TrueBackupBinder.get()
        val hasPw = try {
            svc?.isRegistrationPasswordSet == true
        } catch (_: RemoteException) {
            false
        }
        menu.findItem(R.id.true_backup_restore_start)?.isEnabled = svc != null && hasPw
        menu.findItem(R.id.true_backup_restore_delete_selected)?.isEnabled =
            pathOk && selectedPackages.isNotEmpty()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.true_backup_restore_start -> {
                startRestoreForSelection()
                return true
            }
            R.id.true_backup_restore_delete_selected -> {
                confirmDeleteSelectedBackups()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmDeleteSelectedBackups() {
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
        if (selectedPackages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_apps_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val total = selectedPackages.size
        val packagesSnapshot = selectedPackages.toList()
        val screen = preferenceScreen
        val labelsByPkg = packagesSnapshot.associateWith { pkg ->
            screen?.findPreference<androidx.preference.Preference>(pkg)?.title?.toString().orEmpty()
        }
        val appCtx = requireContext().applicationContext
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.true_backup_delete_multi_confirm_title)
            .setMessage(getString(R.string.true_backup_delete_multi_confirm_message, total))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val queuedPkgs = mutableListOf<String>()
                    for (pkg in packagesSnapshot) {
                        try {
                            svc.enqueueDeleteBackupPackage(path, pkg)
                            queuedPkgs.add(pkg)
                            val label = labelsByPkg[pkg].orEmpty()
                            withContext(Dispatchers.Main) {
                                TrueBackupOperationPoller.onUserQueuedOperation(
                                    appCtx,
                                    TrueBackupOperationPoller.KIND_DELETE,
                                    pkg,
                                    label,
                                )
                            }
                        } catch (e: RemoteException) {
                            Log.e(LOG_TAG, "enqueueDelete $pkg", e)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        queuedPkgs.forEach { selectedPackages.remove(it) }
                        when {
                            queuedPkgs.size == packagesSnapshot.size -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.true_backup_delete_queued,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            queuedPkgs.isNotEmpty() -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.true_backup_delete_queue_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            else -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.true_backup_delete_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        activity?.invalidateOptionsMenu()
                    }
                }
            }
            .show()
    }

    private fun startRestoreForSelection() {
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
        val screen = preferenceScreen
        val labelsByPkg = selectedPackages.associateWith { pkg ->
            screen?.findPreference<androidx.preference.Preference>(pkg)?.title?.toString().orEmpty()
        }
        val toQueue = selectedPackages.toList()
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            var startedAny = false
            for (pkg in toQueue) {
                try {
                    svc.restorePackage(pkg, path)
                    startedAny = true
                    val label = labelsByPkg[pkg].orEmpty()
                    withContext(Dispatchers.Main) {
                        TrueBackupOperationPoller.onUserQueuedOperation(
                            appCtx,
                            TrueBackupOperationPoller.KIND_RESTORE,
                            pkg,
                            label,
                        )
                    }
                } catch (e: RemoteException) {
                    Log.e(LOG_TAG, "restore $pkg", e)
                }
            }
            withContext(Dispatchers.Main) {
                if (startedAny) {
                    Toast.makeText(
                        requireContext(),
                        R.string.true_backup_status_restore_progress,
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
                        val backupDir = resolveBackupPackageDir(ctx, backupPath, pkg)
                        fromService.add(
                            RestoreRow(
                                pkg,
                                name,
                                icon,
                                isPackageInstalled(pm, pkg),
                                backupDir,
                            ),
                        )
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
            val row = loadRowFromBackup(ctx, pm, backupPath, pkgDir) ?: return@forEach
            out.add(row)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun loadRowFromBackup(
        ctx: Context,
        pm: PackageManager,
        backupPath: String,
        pkgDir: File,
    ): RestoreRow? {
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
        val resolvedDir = resolveBackupPackageDir(ctx, backupPath, pkg) ?: pkgDir
        return RestoreRow(
            pkg,
            name,
            icon,
            isPackageInstalled(pm, pkg),
            resolvedDir,
        )
    }

    /** Prefer [ITrueBackupService], then [TrueBackupPaths.findBackupPackageDirLocal]. */
    private fun resolveBackupPackageDir(ctx: Context, backupPath: String, packageName: String): File? {
        TrueBackupBinder.get()?.let { svc ->
            try {
                val path = svc.resolveBackupPackageDir(backupPath, packageName)
                if (!path.isNullOrEmpty()) {
                    val f = File(path)
                    if (f.isDirectory && File(f, TrueBackupPaths.PACKAGE_RESTORE_CONFIG).isFile()) {
                        return f
                    }
                }
            } catch (_: RemoteException) {
            }
        }
        return TrueBackupPaths.findBackupPackageDirLocal(backupPath, packageName)
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun createPreference(row: RestoreRow): TrueBackupAppSelectorPreference {
        return TrueBackupAppSelectorPreference(requireContext()).apply {
            key = row.packageName
            title = row.label
            summary = null
            icon = row.icon
            isPersistent = false
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
                activity?.invalidateOptionsMenu()
            }
            onContentClick = {
                SubSettingLauncher(requireContext())
                    .setDestination(TrueBackupRestoreBackupDetailsFragment::class.java.name)
                    .setTitleText(row.label)
                    .setSourceMetricsCategory(metricsCategory)
                    .setArguments(
                        Bundle().apply {
                            putString(TrueBackupRestoreBackupDetailsFragment.ARG_PACKAGE_NAME, row.packageName)
                            val dir = row.backupPackageDir
                            if (dir != null && dir.isDirectory) {
                                putString(TrueBackupRestoreBackupDetailsFragment.ARG_BACKUP_DIR, dir.absolutePath)
                            }
                        },
                    )
                    .launch()
            }
        }
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.EVOLVER

    override fun getPreferenceScreenResId() = R.xml.true_backup_restore_list_settings

    override fun getLogTag() = LOG_TAG
}

private fun PreferenceScreen.removeAllPreferences() {
    while (preferenceCount > 0) {
        removePreference(getPreference(0))
    }
}
