/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.taskmanager

import android.content.Intent
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class TaskManagerPlugin : Plugin() {

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_taskmanager)

    override val description: String
        get() = context.getString(R.string.pref_plugin_taskmanager_desc)

    override val minSdk: Int
        get() = 21

    override fun hasSettings(): Boolean = false

    override fun getUiButtons(): List<PluginUiButton> {
        val button = PluginUiButton(
            displayName,
            R.drawable.ic_task_manager_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, TaskManagerActivity::class.java).apply {
                putExtra("deviceId", device.deviceId)
            }
            parentActivity.startActivity(intent)
        }
        return listOf(button)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        return false
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_TASKMANAGER,
        PACKET_TYPE_TASKMANAGER_RESPONSE,
        PACKET_TYPE_PING,
        PACKET_TYPE_RUNCOMMAND,
        PACKET_TYPE_MOUSEPAD
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_TASKMANAGER,
        PACKET_TYPE_TASKMANAGER_REQUEST,
        PACKET_TYPE_PING,
        PACKET_TYPE_RUNCOMMAND_REQUEST,
        PACKET_TYPE_MOUSEPAD_REQUEST
    )

    companion object {
        const val PACKET_TYPE_TASKMANAGER = "kdeconnect.taskmanager"
        const val PACKET_TYPE_TASKMANAGER_REQUEST = "kdeconnect.taskmanager.request"
        const val PACKET_TYPE_TASKMANAGER_RESPONSE = "kdeconnect.taskmanager.response"
        const val PACKET_TYPE_PING = "kdeconnect.ping"
        const val PACKET_TYPE_RUNCOMMAND = "kdeconnect.runcommand"
        const val PACKET_TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
        const val PACKET_TYPE_MOUSEPAD = "kdeconnect.mousepad"
        const val PACKET_TYPE_MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
    }
}
