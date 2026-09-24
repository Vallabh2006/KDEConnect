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
    val defaultEnabled: Boolean = true,
    val colorHex: String = "#1E88E5"
) {
    companion object {
        const val ACTION_MEDIA_CONTROL = "media_control"
        const val ACTION_PHONE_MIC = "phone_mic"
        const val ACTION_REMOTE_POINTER = "remote_pointer"
        const val ACTION_CLIPBOARD = "clipboard"
        const val ACTION_TAKE_SEND_SS = "take_send_ss"
        const val ACTION_SCREENMIRROR = "screenmirror"
        const val ACTION_MOUSEPAD = "mousepad"
        const val ACTION_LOCK_PC = "lock_pc"
        const val ACTION_TASKMANAGER = "taskmanager"
        const val ACTION_TERMINAL = "terminal"

        // Legacy compatibility aliases
        const val ACTION_VOLUME = "media_control"
        const val ACTION_SEND_CLIP = "clipboard"
        const val ACTION_RECEIVE_CLIP = "clipboard"
        const val ACTION_MPRIS = "mpris"
        const val ACTION_PRESENTER = "presenter"
        const val ACTION_RUNCOMMAND = "runcommand"
        const val ACTION_FILES = "files"

        val ALL_ACTIONS = listOf(
            FloatingActionItem(ACTION_MEDIA_CONTROL, R.string.floating_action_media_control, R.drawable.ic_multimedia, true, "#06B6D4"),
            FloatingActionItem(ACTION_PHONE_MIC, R.string.floating_action_phone_mic, R.drawable.ic_mic_white, true, "#10B981"),
            FloatingActionItem(ACTION_REMOTE_POINTER, R.string.floating_action_remote_pointer, R.drawable.touchpad_plugin_action_24dp, true, "#8B5CF6"),
            FloatingActionItem(ACTION_CLIPBOARD, R.string.pref_plugin_clipboard, R.drawable.ic_baseline_content_paste_24, true, "#0284C7"),
            FloatingActionItem(ACTION_TAKE_SEND_SS, R.string.floating_action_take_send_ss, R.drawable.ic_camera_24dp, true, "#F59E0B"),
            FloatingActionItem(ACTION_SCREENMIRROR, R.string.floating_action_screenmirror, R.drawable.ic_screen_mirror_24dp, true, "#3B82F6"),
            FloatingActionItem(ACTION_MOUSEPAD, R.string.floating_action_mousepad, R.drawable.ic_action_keyboard_24dp, true, "#6366F1"),
            FloatingActionItem(ACTION_LOCK_PC, R.string.floating_action_lock_pc, R.drawable.ic_key, true, "#EF4444"),
            FloatingActionItem(ACTION_TASKMANAGER, R.string.floating_action_taskmanager, R.drawable.ic_task_manager_24dp, true, "#EC4899"),
            FloatingActionItem(ACTION_TERMINAL, R.string.floating_action_terminal, R.drawable.ic_terminal_24dp, true, "#334155")
        )
    }
}
