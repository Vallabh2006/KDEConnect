/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.floating

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import org.kde.kdeconnect.helpers.ConnectionStateHelper

@RequiresApi(Build.VERSION_CODES.N)
class FloatingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val isEnabled = ConnectionStateHelper.toggleConnection(this)
        updateTileState()

        val msg = if (isEnabled) {
            "KDE Connect connected (Online)"
        } else {
            "KDE Connect disconnected (Offline)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = ConnectionStateHelper.isConnectionEnabled(this)
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isEnabled) "KDE Connect" else "KDE Connect (Off)"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isEnabled) "Connected" else "Disconnected"
        }
        tile.updateTile()
    }
}
