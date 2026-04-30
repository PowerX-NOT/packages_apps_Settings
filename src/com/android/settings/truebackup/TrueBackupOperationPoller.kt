/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.truebackup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
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
    private var sawRekeyThisSession = false
    private var displayedProgressPercent = 0
    private var targetProgressPercent = 0
    private var lastKind: String? = null
    private var lastPkg: String? = null
    private var lastLabel: String = ""
    private var lastQueuedAfterCurrent: Int = 0
    private var completionFramePending = false
    private var completionFrameShownAtMs: Long = 0L
    private var switchFramePending = false
    private var switchFrameShownAtMs: Long = 0L

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
            resetProgressState()
            clearOptimistic()
            appContext = null
            sawWorkThisSession = false
            stopPoll()
            return
        }
        try {
            if (!svc.isOperationInProgress) {
                if (sawWorkThisSession && !completionFramePending && (displayedProgressPercent < 100)) {
                    completionFramePending = true
                    completionFrameShownAtMs = SystemClock.uptimeMillis()
                    displayedProgressPercent = 100
                    targetProgressPercent = 100
                    TrueBackupNotifications.updateActiveOperationProgress(
                        ctx,
                        lastKind,
                        lastPkg,
                        lastLabel,
                        100,
                        lastQueuedAfterCurrent,
                    )
                    // Keep 100% visible briefly before final completion notification.
                    schedulePoll(900L)
                    return
                }
                if (completionFramePending) {
                    val elapsed = SystemClock.uptimeMillis() - completionFrameShownAtMs
                    if (elapsed < 900L) {
                        schedulePoll(900L - elapsed)
                        return
                    }
                }
                stopPoll()
                if (sawWorkThisSession) {
                    if (sawRekeyThisSession) {
                        TrueBackupNotifications.notifyRekeyFinished(ctx)
                    } else {
                        TrueBackupNotifications.notifyAllOperationsFinished(ctx)
                    }
                    try {
                        onAllOperationsIdle?.invoke()
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "onAllOperationsIdle", e)
                    }
                }
                sawWorkThisSession = false
                sawRekeyThisSession = false
                resetProgressState()
                clearOptimistic()
                appContext = null
                return
            }
            if (switchFramePending) {
                val elapsed = SystemClock.uptimeMillis() - switchFrameShownAtMs
                if (elapsed < 650L) {
                    schedulePoll(650L - elapsed)
                    return
                }
                // Done holding the previous task's 100% frame; proceed to show current task state.
                switchFramePending = false
                switchFrameShownAtMs = 0L
            }
            completionFramePending = false
            sawWorkThisSession = true
            val kind = svc.activeOperationKind
                ?: optimisticKind
                ?: if (optimisticPackage != null) {
                    KIND_BACKUP
                } else {
                    null
                }
            if (kind == KIND_REKEY || optimisticKind == KIND_REKEY) {
                sawRekeyThisSession = true
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
            val rawProgress = svc.activeOperationProgressPercent
            val serverProgress = when {
                rawProgress < 0 -> targetProgressPercent
                rawProgress > 100 -> 100
                else -> rawProgress
            }
            val changedTask = (lastKind != null || lastPkg != null) && (kind != lastKind || pkg != lastPkg)
            if (changedTask && displayedProgressPercent < 100 && lastLabel.isNotEmpty()) {
                // Queue advanced to a new task; show a brief 100% frame for the previous one.
                switchFramePending = true
                switchFrameShownAtMs = SystemClock.uptimeMillis()
                displayedProgressPercent = 100
                targetProgressPercent = 100
                TrueBackupNotifications.updateActiveOperationProgress(
                    ctx,
                    lastKind,
                    lastPkg,
                    lastLabel,
                    100,
                    lastQueuedAfterCurrent,
                )
                schedulePoll(650L)
                return
            }
            if (changedTask) {
                displayedProgressPercent = 0
                targetProgressPercent = 0
            }
            targetProgressPercent = maxOf(targetProgressPercent, serverProgress)
            val step = when {
                displayedProgressPercent < 30 -> 3
                displayedProgressPercent < 70 -> 2
                else -> 1
            }
            displayedProgressPercent = minOf(targetProgressPercent, displayedProgressPercent + step)
            val queued = svc.queuedOperationCount
            lastKind = kind
            lastPkg = pkg
            lastLabel = label
            lastQueuedAfterCurrent = queued
            TrueBackupNotifications.updateActiveOperationProgress(
                ctx,
                kind,
                pkg,
                label,
                displayedProgressPercent,
                queued,
            )
            schedulePoll(220L)
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "poll", e)
            stopPoll()
            resetProgressState()
            clearOptimistic()
            appContext = null
            sawWorkThisSession = false
        }
    }

    private fun resetProgressState() {
        displayedProgressPercent = 0
        targetProgressPercent = 0
        lastKind = null
        lastPkg = null
        lastLabel = ""
        lastQueuedAfterCurrent = 0
        completionFramePending = false
        completionFrameShownAtMs = 0L
        switchFramePending = false
        switchFrameShownAtMs = 0L
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
            ""
        }
    }
}
