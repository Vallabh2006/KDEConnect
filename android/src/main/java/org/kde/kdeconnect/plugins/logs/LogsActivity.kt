/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.logs

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateListOf
import org.kde.kdeconnect.logging.KdeLog
import org.kde.kdeconnect.ui.compose.KdeTheme

class LogsActivity : AppCompatActivity(), KdeLog.LogListener {

    private val logsList = mutableStateListOf<KdeLog.LogEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        logsList.addAll(KdeLog.getAllLogs())
        KdeLog.registerListener(this)

        setContent {
            KdeTheme(this@LogsActivity) {
                LogsScreen(
                    logs = logsList,
                    onBackPressedDispatcher = onBackPressedDispatcher,
                    onClearLogs = { KdeLog.clear() },
                    onShareLogs = { KdeLog.shareLogs(this@LogsActivity) }
                )
            }
        }
    }

    override fun onNewLog(entry: KdeLog.LogEntry) {
        logsList.add(entry)
        if (logsList.size > 5000) {
            logsList.removeAt(0)
        }
    }

    override fun onLogsCleared() {
        logsList.clear()
    }

    override fun onDestroy() {
        KdeLog.unregisterListener(this)
        super.onDestroy()
    }
}
