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

    const val KIND_BACKUP = "backup"
    const val KIND_RESTORE = "restore"
    const val KIND_DELETE = "delete"
    const val KIND_REKEY = "rekey"

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    /** Optimistic op kind until the service sets [android.os.ITrueBackupService.getActiveOperationKind]. */
    private var optimisticKind: String? = null
    private var optimisticPackage: String? = null
    private var optimisticLabel: String? = null
    private var sawWorkThisSession = false

    @Volatile
    private var pollPosted = false

    private var onAllOperationsIdle: (() -> Unit)? = null

    private val pollRunnable = Runnable { pollOnce() }

    /**
     * Optional hook when [android.os.ITrueBackupService.isOperationInProgress] becomes false after
     * work was observed (e.g. refresh the restore app list after queued deletes).
     */
    fun setOnAllOperationsIdleListener(listener: (() -> Unit)?) {
        onAllOperationsIdle = listener
    }

    /**
     * Call after a backup, restore, or delete has been queued successfully.
     * [operationKind] is [KIND_BACKUP], [KIND_RESTORE], or [KIND_DELETE].
     */
    fun onUserQueuedOperation(
        context: Context,
        operationKind: String,
        packageName: String,
        appLabel: String,
    ) {
        appContext = context.applicationContext
        optimisticKind = operationKind
        optimisticPackage = packageName
        optimisticLabel = appLabel
        schedulePoll(0L)
    }

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
                    try {
                        onAllOperationsIdle?.invoke()
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "onAllOperationsIdle", e)
                    }
                }
                sawWorkThisSession = false
                clearOptimistic()
                appContext = null
                return
            }
            sawWorkThisSession = true
            val kind = svc.activeOperationKind
                ?: optimisticKind
                ?: if (optimisticPackage != null) {
                    KIND_BACKUP
                } else {
                    null
                }
            val optLabelSnapshot = optimisticLabel
            val activePkg = svc.activeOperationPackage
            val pkg = activePkg ?: optimisticPackage
            if (activePkg != null) {
                clearOptimistic()
            }
            val label = if (activePkg != null) {
                resolveDisplayName(ctx, activePkg, null)
            } else {
                resolveDisplayName(ctx, pkg, optLabelSnapshot)
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
        optimisticKind = null
        optimisticPackage = null
        optimisticLabel = null
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
