/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.android.settings.R

/** Posts status notifications when backup/restore runs from Settings. */
object TrueBackupNotifications {

    private const val CHANNEL_ID = "true_backup_status"
    private const val NOTIF_BACKUP_PROGRESS = 71001
    private const val NOTIF_BACKUP_DONE = 71002
    private const val NOTIF_RESTORE_PROGRESS = 71003
    private const val NOTIF_RESTORE_DONE = 71004

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.true_backup_notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.true_backup_notif_channel_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun newBuilder(context: Context): Notification.Builder {
        ensureChannel(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT)
        }
    }

    /** Indeterminate progress bar (animated) while work runs. */
    private fun buildInProgress(context: Context, title: String, text: String): Notification {
        return newBuilder(context)
            .setSmallIcon(R.drawable.ic_settings_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    /** Finished: no progress bar, can dismiss. */
    private fun buildComplete(context: Context, title: String, text: String): Notification {
        return newBuilder(context)
            .setSmallIcon(R.drawable.ic_settings_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
    }

    fun notifyBackupStarted(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            NOTIF_BACKUP_PROGRESS,
            buildInProgress(
                context,
                context.getString(R.string.true_backup_notif_backup_started_title),
                context.getString(R.string.true_backup_notif_backup_started_text),
            ),
        )
    }

    fun notifyBackupCompleted(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_BACKUP_PROGRESS)
        nm.notify(
            NOTIF_BACKUP_DONE,
            buildComplete(
                context,
                context.getString(R.string.true_backup_notif_backup_complete_title),
                context.getString(R.string.true_backup_notif_backup_complete_text),
            ),
        )
    }

    fun notifyRestoreStarted(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            NOTIF_RESTORE_PROGRESS,
            buildInProgress(
                context,
                context.getString(R.string.true_backup_notif_restore_started_title),
                context.getString(R.string.true_backup_notif_restore_started_text),
            ),
        )
    }

    fun notifyRestoreCompleted(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_RESTORE_PROGRESS)
        nm.notify(
            NOTIF_RESTORE_DONE,
            buildComplete(
                context,
                context.getString(R.string.true_backup_notif_restore_complete_title),
                context.getString(R.string.true_backup_notif_restore_complete_text),
            ),
        )
    }
}
