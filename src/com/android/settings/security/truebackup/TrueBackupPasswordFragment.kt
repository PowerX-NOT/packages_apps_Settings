/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.os.Bundle
import android.os.RemoteException
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.dashboard.DashboardFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class TrueBackupPasswordFragment : DashboardFragment() {

    private var isSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(false)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        refreshState()
        findPreference<Preference>("true_backup_password_set")?.setOnPreferenceClickListener {
            refreshState()
            showSetPasswordDialog(isReset = isSet)
            true
        }
        findPreference<Preference>("true_backup_password_reset")?.setOnPreferenceClickListener {
            showSetPasswordDialog(isReset = true)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val svc = TrueBackupBinder.get()
        isSet = try {
            svc?.isRegistrationPasswordSet == true
        } catch (_: RemoteException) {
            false
        }
        findPreference<Preference>("true_backup_password_reset")?.isEnabled = isSet && svc != null
        findPreference<Preference>("true_backup_password_set")?.apply {
            isEnabled = svc != null
            title = getString(
                if (isSet) R.string.true_backup_password_change_title
                else R.string.true_backup_password_set_title
            )
            summary = getString(
                if (isSet) R.string.true_backup_password_set_summary_is_set
                else R.string.true_backup_password_set_summary_not_set
            )
        }
    }

    private fun showSetPasswordDialog(isReset: Boolean) {
        val svc = TrueBackupBinder.get()
        if (svc == null) {
            Toast.makeText(requireContext(), R.string.true_backup_toast_service_missing, Toast.LENGTH_LONG).show()
            return
        }

        val inputOld = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.true_backup_password_hint_old)
        }
        val input1 = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.true_backup_password_hint_new)
        }
        val input2 = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.true_backup_password_hint_confirm)
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
            if (isReset) addView(inputOld)
            addView(input1)
            addView(input2)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (isReset) R.string.true_backup_password_reset_title else R.string.true_backup_password_set_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val oldPw = inputOld.text?.toString().orEmpty()
                val p1 = input1.text?.toString().orEmpty()
                val p2 = input2.text?.toString().orEmpty()
                if (p1.isBlank() || p1 != p2) {
                    Toast.makeText(requireContext(), R.string.true_backup_password_mismatch, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (isReset && oldPw.isBlank()) {
                    Toast.makeText(requireContext(), R.string.true_backup_password_old_incorrect, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val appCtx = requireContext().applicationContext
                lifecycleScope.launch(Dispatchers.IO) {
                    var ok = false
                    try {
                        if (isReset) {
                            ok = svc.changeRegistrationPassword(oldPw, p1)
                        } else {
                            ok = svc.setRegistrationPassword(p1)
                        }
                    } catch (e: RemoteException) {
                        ok = false
                    }
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            TrueBackupOperationPoller.onUserQueuedOperation(
                                appCtx,
                                TrueBackupOperationPoller.KIND_REKEY,
                                "truebackup",
                                getString(R.string.true_backup_notif_rekey_title),
                            )
                            Toast.makeText(requireContext(), R.string.true_backup_password_saved, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), R.string.true_backup_password_save_failed, Toast.LENGTH_LONG).show()
                        }
                        refreshState()
                    }
                }
            }
            .show()
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.EVOLVER

    override fun getPreferenceScreenResId() = R.xml.true_backup_password_settings

    override fun getLogTag() = "TrueBackupPassword"
}

