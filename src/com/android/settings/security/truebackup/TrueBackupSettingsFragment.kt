/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.dashboard.DashboardFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable

@SearchIndexable
class TrueBackupSettingsFragment : DashboardFragment() {

    private lateinit var openTree: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTree = registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
            val uri = result.data!!.data ?: return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            val path = TrueBackupPreferences.uriTreeToDisplayPath(uri)
            if (path != null) {
                TrueBackupPreferences.setBackupPath(requireContext(), path)
                try {
                    TrueBackupBinder.get()?.recordBackupBasePath(path)
                } catch (_: Exception) {
                }
            }
            updateLocationSummary()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>(KEY_LOCATION)?.setOnPreferenceClickListener {
            val pickTree = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
            openTree.launch(pickTree)
            true
        }
        updateLocationSummary()
    }

    override fun onResume() {
        super.onResume()
        updateLocationSummary()
    }

    private fun updateLocationSummary() {
        val pref = findPreference<Preference>(KEY_LOCATION) ?: return
        val path = TrueBackupPreferences.getBackupPath(requireContext())
        pref.summary = if (path == null) {
            getString(R.string.true_backup_location_not_set)
        } else {
            getString(R.string.true_backup_location_current, path)
        }
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.EVOLVER

    override fun getPreferenceScreenResId() = R.xml.true_backup_settings

    override fun getLogTag() = TAG

    companion object {
        private const val TAG = "TrueBackupSettings"
        private const val KEY_LOCATION = "true_backup_location"

        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = BaseSearchIndexProvider(R.xml.true_backup_settings)
    }
}
