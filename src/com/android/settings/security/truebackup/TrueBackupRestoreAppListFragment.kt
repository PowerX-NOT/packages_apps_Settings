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
import androidx.preference.PreferenceScreen
import com.android.settings.core.SubSettingLauncher
import com.android.settings.dashboard.DashboardFragment
import com.android.settingslib.widget.SelectorWithWidgetPreference
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

    private var selectedPackage: String? = null
    /** Skip one [onResume] after [onCreatePreferences] to avoid double [populateRestoreList]. */
    private var skipNextResumeRefresh = true
    private val pollHandler = Handler(Looper.getMainLooper())
    private var operationInProgress = false
    private var awaitingRestoreCompleteNotification = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            val svc = TrueBackupBinder.get()
            if (svc == null) {
                operationInProgress = false
                awaitingRestoreCompleteNotification = false
                activity?.invalidateOptionsMenu()
                return
            }
            try {
                if (svc.isOperationInProgress) {
                    pollHandler.postDelayed(this, 1000)
                } else {
                    operationInProgress = false
                    activity?.invalidateOptionsMenu()
                    if (awaitingRestoreCompleteNotification) {
                        awaitingRestoreCompleteNotification = false
                        this@TrueBackupRestoreAppListFragment.context?.applicationContext?.let {
                            TrueBackupNotifications.notifyRestoreCompleted(it)
                        }
                    }
                }
            } catch (e: RemoteException) {
                Log.e(LOG_TAG, "poll", e)
                operationInProgress = false
                awaitingRestoreCompleteNotification = false
                activity?.invalidateOptionsMenu()
            }
        }
    }

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
                if (deletedPkg != null && selectedPackage == deletedPkg) {
                    selectedPackage = null
                    TrueBackupPreferences.setSelectedPackage(requireContext(), null)
                }
                populateRestoreList()
            }
        }
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        skipNextResumeRefresh = true
        selectedPackage = TrueBackupPreferences.getSelectedPackage(requireContext())
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
            val sel = selectedPackage
            if (sel != null && rows.none { it.packageName == sel }) {
                selectedPackage = null
                TrueBackupPreferences.setSelectedPackage(ctx, null)
            }
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
        schedulePollIfNeeded()
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
            return
        }
        if (TrueBackupPreferences.getBackupPath(requireContext()) != null) {
            populateRestoreList()
        }
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
        val pkg = selectedPackage
        if (pkg == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_no_apps_selected, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var started = false
            try {
                svc.restorePackage(pkg, path)
                started = true
            } catch (e: RemoteException) {
                Log.e(LOG_TAG, "restore $pkg", e)
            }
            if (started) {
                awaitingRestoreCompleteNotification = true
                operationInProgress = true
                withContext(Dispatchers.Main) {
                    val appCtx = requireContext().applicationContext
                    TrueBackupNotifications.notifyRestoreStarted(appCtx)
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

    private fun clearAllRadioChecksExcept(keep: SelectorWithWidgetPreference) {
        val screen = preferenceScreen ?: return
        for (i in 0 until screen.preferenceCount) {
            val p = screen.getPreference(i)
            if (p is TrueBackupAppSelectorPreference && p !== keep) {
                p.isChecked = false
            }
        }
    }

    private fun createPreference(row: RestoreRow): TrueBackupAppSelectorPreference {
        return TrueBackupAppSelectorPreference(requireContext()).apply {
            key = row.packageName
            title = row.label
            summary = row.packageName
            icon = row.icon
            isPersistent = false
            isChecked = selectedPackage == row.packageName
            setOnClickListener { emitter ->
                val key = emitter.key ?: return@setOnClickListener
                selectedPackage = key
                TrueBackupPreferences.setSelectedPackage(requireContext(), key)
                clearAllRadioChecksExcept(emitter)
                emitter.isChecked = true
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
