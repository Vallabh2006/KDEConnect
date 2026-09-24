/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.floating

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.kde.kdeconnect_tp.R

data class FloatingActionItem(
    val id: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val defaultEnabled: Boolean = true
) {
    companion object {
        const val ACTION_MOUSEPAD = "mousepad"
        const val ACTION_CLIPBOARD = "clipboard"
        const val ACTION_MPRIS = "mpris"
        const val ACTION_PRESENTER = "presenter"
        const val ACTION_SCREENMIRROR = "screenmirror"
        const val ACTION_RUNCOMMAND = "runcommand"
        const val ACTION_FILES = "files"
        const val ACTION_TASKMANAGER = "taskmanager"
        const val ACTION_TERMINAL = "terminal"

        val ALL_ACTIONS = listOf(
            FloatingActionItem(ACTION_MOUSEPAD, R.string.pref_plugin_mousepad, R.drawable.ic_action_keyboard_24dp, true),
            FloatingActionItem(ACTION_CLIPBOARD, R.string.pref_plugin_clipboard, R.drawable.ic_baseline_content_paste_24, true),
            FloatingActionItem(ACTION_MPRIS, R.string.pref_plugin_mpris, R.drawable.ic_volume, true),
            FloatingActionItem(ACTION_PRESENTER, R.string.pref_plugin_presenter, R.drawable.ic_arrow_forward_black_24dp, true),
            FloatingActionItem(ACTION_SCREENMIRROR, R.string.pref_plugin_screenmirror, R.drawable.ic_device_laptop_32dp, true),
            FloatingActionItem(ACTION_RUNCOMMAND, R.string.pref_plugin_runcommand, R.drawable.run_command_plugin_icon_24dp, true),
            FloatingActionItem(ACTION_FILES, R.string.transfer_files, R.drawable.ic_device_desktop_32dp, false),
            FloatingActionItem(ACTION_TASKMANAGER, R.string.pref_plugin_taskmanager, R.drawable.ic_cpu_24dp, false),
            FloatingActionItem(ACTION_TERMINAL, R.string.pref_plugin_terminal, R.drawable.ic_baseline_code_24, false)
        )
    }
}
