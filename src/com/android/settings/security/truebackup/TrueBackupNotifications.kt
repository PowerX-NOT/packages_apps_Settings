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
    /** Single slot for ongoing backup/restore (replaces separate backup/restore progress IDs). */
    private const val NOTIF_ACTIVE_OPERATION = 71001
    private const val NOTIF_BACKUP_DONE = 71002
    private const val NOTIF_RESTORE_PROGRESS_LEGACY = 71003
    private const val NOTIF_RESTORE_DONE = 71004
    private const val NOTIF_ALL_COMPLETE = 71007

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

    /**
     * Shows one ongoing notification with the current app and queue depth. Cancels legacy
     * restore-progress id so only one progress notification is visible.
     */
    fun updateActiveOperationProgress(
        context: Context,
        operationKind: String?,
        packageName: String?,
        appDisplayName: String,
        queuedAfterCurrent: Int,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_RESTORE_PROGRESS_LEGACY)
        val title = when (operationKind) {
            "restore" -> context.getString(R.string.true_backup_notif_restore_started_title)
            "delete" -> context.getString(R.string.true_backup_notif_delete_title)
            "rekey" -> context.getString(R.string.true_backup_notif_rekey_title)
            else -> context.getString(R.string.true_backup_notif_backup_started_title)
        }
        val mainText = appDisplayName.ifEmpty { packageName ?: "" }.ifEmpty {
            context.getString(R.string.true_backup_notif_preparing)
        }
        val bigText = buildString {
            append(mainText)
            if (packageName != null && packageName.isNotEmpty() && mainText != packageName) {
                append("\n")
                append(packageName)
            }
            if (queuedAfterCurrent > 0) {
                append("\n")
                append(context.getString(R.string.true_backup_notif_more_in_queue, queuedAfterCurrent))
            }
        }
        val n = newBuilder(context)
            .setSmallIcon(R.drawable.ic_settings_backup)
            .setContentTitle(title)
            .setContentText(mainText)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .build()
        nm.notify(NOTIF_ACTIVE_OPERATION, n)
    }

    /** Posted when the work counter returns to zero after at least one operation was observed. */
    fun notifyAllOperationsFinished(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ACTIVE_OPERATION)
        nm.cancel(NOTIF_RESTORE_PROGRESS_LEGACY)
        nm.notify(
            NOTIF_ALL_COMPLETE,
            newBuilder(context)
                .setSmallIcon(R.drawable.ic_settings_backup)
                .setContentTitle(context.getString(R.string.true_backup_notif_all_ops_complete_title))
                .setContentText(context.getString(R.string.true_backup_notif_all_ops_complete_text))
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(context.getString(R.string.true_backup_notif_all_ops_complete_text)),
                )
                .build(),
        )
    }
}
