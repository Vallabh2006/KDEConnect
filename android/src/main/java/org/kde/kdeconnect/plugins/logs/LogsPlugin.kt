/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.logs

import android.content.Intent
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class LogsPlugin : Plugin() {

    override val displayName: String
        get() = "Logs"

    override val description: String
        get() = "Live packet inspector, event recorder, and diagnostic logging"

    override val minSdk: Int
        get() = 21

    override fun hasSettings(): Boolean = false

    override fun getUiButtons(): List<PluginUiButton> {
        val button = PluginUiButton(
            "Logs",
            R.drawable.ic_baseline_bug_report_24
        ) { parentActivity ->
            val intent = Intent(parentActivity, LogsActivity::class.java).apply {
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
        PACKET_TYPE_PING,
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_RUNCOMMAND
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_PING,
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_RUNCOMMAND_REQUEST
    )

    companion object {
        const val PACKET_TYPE_PING = "kdeconnect.ping"
        const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"
        const val PACKET_TYPE_RUNCOMMAND = "kdeconnect.runcommand"
        const val PACKET_TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
    }
}
