/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.truebackup

import android.os.ITrueBackupService
import android.os.ServiceManager

internal object TrueBackupBinder {
    fun get(): ITrueBackupService? =
        ITrueBackupService.Stub.asInterface(ServiceManager.getService("truebackup"))
}
