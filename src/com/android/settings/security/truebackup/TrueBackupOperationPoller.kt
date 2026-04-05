/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log

private const val LOG_TAG = "TrueBackupOpPoller"

/**
 * Polls the True Backup binder while work is scheduled or running, updates a single notification
 * with the current app name and queue length, and posts a completion notification when idle.
 *
 * Uses a process-wide [Handler] so leaving True Backup settings does not stop updates.
 */
object TrueBackupOperationPoller {

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var optimisticIsRestore: Boolean = false
    private var optimisticPackage: String? = null
    private var optimisticLabel: String? = null
    private var sawWorkThisSession = false

    @Volatile
    private var pollPosted = false

    private val pollRunnable = Runnable { pollOnce() }

    /**
     * Call after [android.os.ITrueBackupService.backupPackage] or [restorePackage] returns
     * successfully. Pass the app label shown in the list for instant notification text.
     */
    fun onUserQueuedOperation(
        context: Context,
        isRestore: Boolean,
        packageName: String,
        appLabel: String,
    ) {
        appContext = context.applicationContext
        optimisticIsRestore = isRestore
        optimisticPackage = packageName
        optimisticLabel = appLabel
        schedulePoll(0L)
    }

    /**
     * If work is already in progress (e.g. user returned to the screen) and nothing is polling,
     * attach to the session so completion still notifies.
     */
    fun resumeWatchingIfOperationInProgress(context: Context) {
        val svc = TrueBackupBinder.get() ?: return
        try {
            if (!svc.isOperationInProgress) return
        } catch (_: RemoteException) {
            return
        }
        if (pollPosted) return
        appContext = context.applicationContext
        schedulePoll(0L)
    }

    private fun schedulePoll(delayMs: Long) {
        pollPosted = true
        handler.removeCallbacks(pollRunnable)
        if (delayMs <= 0L) {
            handler.post(pollRunnable)
        } else {
            handler.postDelayed(pollRunnable, delayMs)
        }
    }

    private fun stopPoll() {
        pollPosted = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun pollOnce() {
        val ctx = appContext ?: return
        val svc = TrueBackupBinder.get()
        if (svc == null) {
            Log.w(LOG_TAG, "binder null; stop polling")
            clearOptimistic()
            appContext = null
            sawWorkThisSession = false
            stopPoll()
            return
        }
        try {
            if (!svc.isOperationInProgress) {
                stopPoll()
                if (sawWorkThisSession) {
                    TrueBackupNotifications.notifyAllOperationsFinished(ctx)
                }
                sawWorkThisSession = false
                clearOptimistic()
                appContext = null
                return
            }
            sawWorkThisSession = true
            val kind = svc.activeOperationKind
                ?: if (optimisticPackage != null) {
                    if (optimisticIsRestore) "restore" else "backup"
                } else {
                    null
                }
            val activePkg = svc.activeOperationPackage
            val pkg = activePkg ?: optimisticPackage
            val label = if (activePkg != null) {
                resolveDisplayName(ctx, activePkg, null)
            } else {
                resolveDisplayName(ctx, pkg, optimisticLabel)
            }
            val queued = svc.queuedOperationCount
            TrueBackupNotifications.updateActiveOperationProgress(
                ctx,
                kind,
                pkg,
                label,
                queued,
            )
            schedulePoll(1000L)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "poll", e)
            stopPoll()
            clearOptimistic()
            appContext = null
            sawWorkThisSession = false
        }
    }

    private fun clearOptimistic() {
        optimisticPackage = null
        optimisticLabel = null
        optimisticIsRestore = false
    }

    private fun resolveDisplayName(context: Context, packageName: String?, optimistic: String?): String {
        if (!optimistic.isNullOrEmpty()) return optimistic
        if (packageName.isNullOrEmpty()) return ""
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
