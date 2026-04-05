/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log

private const val LOG_TAG = "TrueBackupOpPoller"

/**
 * Polls the True Backup binder until the in-progress flag clears, then shows the matching
 * completion notification.
 *
 * Fragment-scoped [Handler] callbacks are removed when the fragment is destroyed, which stopped
 * polling when the user left True Backup settings and left the progress notification stuck. This
 * object uses a process-wide main-loop handler so polling continues in the background.
 */
object TrueBackupOperationPoller {

    enum class Kind {
        BACKUP,
        RESTORE,
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var pending: Kind? = null

    private var appContext: Context? = null

    private val pollRunnable = Runnable { pollOnce() }

    private fun pollOnce() {
        val ctx = appContext
        val kind = pending
        if (ctx == null || kind == null) return

        val svc = TrueBackupBinder.get()
        if (svc == null) {
            Log.w(LOG_TAG, "binder null; stop polling")
            pending = null
            appContext = null
            return
        }
        try {
            if (svc.isOperationInProgress) {
                handler.postDelayed(pollRunnable, 1000L)
            } else {
                pending = null
                appContext = null
                when (kind) {
                    Kind.BACKUP -> TrueBackupNotifications.notifyBackupCompleted(ctx)
                    Kind.RESTORE -> TrueBackupNotifications.notifyRestoreCompleted(ctx)
                }
            }
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "poll", e)
            pending = null
            appContext = null
        }
    }

    /** Call after a backup has been started successfully. */
    fun startWatchingForBackupCompletion(context: Context) {
        appContext = context.applicationContext
        pending = Kind.BACKUP
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 1000L)
    }

    /** Call after a restore has been started successfully. */
    fun startWatchingForRestoreCompletion(context: Context) {
        appContext = context.applicationContext
        pending = Kind.RESTORE
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 1000L)
    }

    /**
     * If an operation is already running (e.g. user left the screen mid-run) and we are not
     * polling yet, start polling so the completion notification still fires.
     */
    fun resumeWatchingIfOperationInProgress(context: Context, kindIfUnknown: Kind) {
        val svc = TrueBackupBinder.get() ?: return
        try {
            if (!svc.isOperationInProgress) return
        } catch (_: RemoteException) {
            return
        }
        if (pending != null) return
        appContext = context.applicationContext
        pending = kindIfUnknown
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }
}
