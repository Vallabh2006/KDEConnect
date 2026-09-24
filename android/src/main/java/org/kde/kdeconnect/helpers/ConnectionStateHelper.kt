/*
 * SPDX-FileCopyrightText: 2026 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.helpers

import android.content.Context
import android.util.Log
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.floating.FloatingButtonHelper

object ConnectionStateHelper {

    private const val PREFS_NAME = "connection_state_prefs"
    private const val KEY_CONNECTION_ENABLED = "kdeconnect_connection_enabled"

    fun isConnectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CONNECTION_ENABLED, true)
    }

    fun setConnectionEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CONNECTION_ENABLED, enabled).apply()
        Log.i("ConnectionStateHelper", "KDE Connect master connection state set to: ")

        if (enabled) {
            try {
                BackgroundService.ForceRefreshConnections(context)
                BackgroundService.instance?.onNetworkChange(null)
            } catch (e: Exception) {
                Log.e("ConnectionStateHelper", "Error refreshing connections on enable", e)
            }
            if (FloatingButtonHelper.hasOverlayPermission(context)) {
                FloatingButtonHelper.startFloatingButton(context)
            }
        } else {
            try {
                KdeConnect.getInstance().devices.values.forEach { device ->
                    try {
                        device.disconnect()
                    } catch (ignored: Exception) {}
                }
                BackgroundService.instance?.updateForegroundNotification()
            } catch (e: Exception) {
                Log.e("ConnectionStateHelper", "Error disconnecting devices on disable", e)
            }
            FloatingButtonHelper.stopFloatingButton(context)
        }
    }

    fun toggleConnection(context: Context): Boolean {
        val newState = !isConnectionEnabled(context)
        setConnectionEnabled(context, newState)
        return newState
    }
}
