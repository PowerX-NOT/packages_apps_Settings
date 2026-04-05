/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.security.truebackup

import android.content.Context
import android.view.View
import androidx.preference.PreferenceViewHolder
import com.android.settings.R
import com.android.settingslib.widget.SelectorWithWidgetPreference

/**
 * Radio selector row: tapping the main content is handled separately (e.g. app info);
 * tapping the widget area runs [SelectorWithWidgetPreference.onClick] for single-select.
 */
class TrueBackupAppSelectorPreference(context: Context) :
    SelectorWithWidgetPreference(context, false) {

    var onContentClick: (() -> Unit)? = null

    init {
        setLayoutResource(R.layout.true_backup_preference_selector_row)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.apply {
            isClickable = false
            isFocusable = false
            setOnClickListener(null)
            background = null
        }

        val content = holder.findViewById(R.id.true_backup_row_content) as? View
        val widgetFrame = holder.findViewById(android.R.id.widget_frame)

        if (!isEnabled) {
            content?.isClickable = false
            content?.isFocusable = false
            content?.setOnClickListener(null)
            content?.background = null
            widgetFrame?.isClickable = false
            widgetFrame?.isFocusable = false
            widgetFrame?.setOnClickListener(null)
            widgetFrame?.background = null
            return
        }

        val contentAction = onContentClick
        if (contentAction != null) {
            content?.isClickable = true
            content?.isFocusable = true
            content?.setOnClickListener { contentAction() }
        } else {
            content?.isClickable = false
            content?.isFocusable = false
            content?.setOnClickListener(null)
            content?.background = null
        }

        widgetFrame?.isClickable = true
        widgetFrame?.isFocusable = true
        widgetFrame?.setOnClickListener { onClick() }
    }
}
