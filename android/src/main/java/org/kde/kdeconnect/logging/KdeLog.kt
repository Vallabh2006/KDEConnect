/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.logging

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.kde.kdeconnect.NetworkPacket
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object KdeLog {

    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    enum class LogTag(val displayName: String) {
        ALL("All"),
        PACKET("Packets"),
        CLIPBOARD("Clipboard"),
        TERMINAL("Terminal"),
        MIRROR("Mirror"),
        TASK_MANAGER("Tasks"),
        SYSTEM("System"),
        ERROR("Errors")
    }

    data class LogEntry(
        val id: Long,
        val timestamp: Long,
        val level: LogLevel,
        val tag: LogTag,
        val title: String,
        val message: String
    ) {
        val timeFormatted: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

        val dateFormatted: String
            get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    interface LogListener {
        fun onNewLog(entry: LogEntry)
        fun onLogsCleared()
    }

    private const val MAX_LOGS = 5000
    private val idCounter = AtomicLong(1)
    private val logBuffer = mutableListOf<LogEntry>()
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<LogListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun registerListener(listener: LogListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: LogListener) {
        listeners.remove(listener)
    }

    fun getAllLogs(): List<LogEntry> {
        synchronized(lock) {
            return ArrayList(logBuffer)
        }
    }

    fun clear() {
        synchronized(lock) {
            logBuffer.clear()
        }
        mainHandler.post {
            for (l in listeners) {
                l.onLogsCleared()
            }
        }
    }

    fun log(level: LogLevel, tag: LogTag, title: String, message: String = "") {
        val entry = LogEntry(
            id = idCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            title = title,
            message = message
        )

        synchronized(lock) {
            if (logBuffer.size >= MAX_LOGS) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(entry)
        }

        val logcatTag = "KDE/${tag.name}"
        val logcatMsg = if (message.isNotEmpty()) "$title - $message" else title
        when (level) {
            LogLevel.DEBUG -> Log.d(logcatTag, logcatMsg)
            LogLevel.INFO -> Log.i(logcatTag, logcatMsg)
            LogLevel.WARN -> Log.w(logcatTag, logcatMsg)
            LogLevel.ERROR -> Log.e(logcatTag, logcatMsg)
        }

        mainHandler.post {
            for (l in listeners) {
                l.onNewLog(entry)
            }
        }
    }

    fun d(tag: LogTag, title: String, message: String = "") = log(LogLevel.DEBUG, tag, title, message)
    fun i(tag: LogTag, title: String, message: String = "") = log(LogLevel.INFO, tag, title, message)
    fun w(tag: LogTag, title: String, message: String = "") = log(LogLevel.WARN, tag, title, message)
    fun e(tag: LogTag, title: String, message: String = "", throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) {
            val st = Log.getStackTraceString(throwable)
            if (message.isNotEmpty()) "$message\n$st" else st
        } else {
            message
        }
        log(LogLevel.ERROR, tag, title, fullMsg)
    }

    fun packet(direction: String, packet: NetworkPacket) {
        val type = packet.type
        val cleanType = formatPacketType(type)
        val summary = formatPacketSummary(packet)
        log(LogLevel.INFO, LogTag.PACKET, "$direction: $cleanType", summary)
    }

    private fun formatPacketType(type: String): String {
        return when (type) {
            "kdeconnect.connectivity_report" -> "Connectivity Report"
            "kdeconnect.battery" -> "Battery Update"
            "kdeconnect.battery.request" -> "Battery Request"
            "kdeconnect.ping" -> "Ping"
            "kdeconnect.clipboard" -> "Clipboard Sync"
            "kdeconnect.clipboard.connect" -> "Clipboard Request"
            "kdeconnect.screenmirror.request" -> "Screen Mirror Request"
            "kdeconnect.terminal" -> "Terminal Packet"
            "kdeconnect.terminal.input" -> "Terminal Input"
            "kdeconnect.terminal.output" -> "Terminal Output"
            "kdeconnect.terminal.signal" -> "Terminal Signal"
            "kdeconnect.mpris" -> "Media Player Sync"
            "kdeconnect.mpris.request" -> "Media Control Request"
            "kdeconnect.mousepad.request" -> "Mouse / Input Event"
            "kdeconnect.mousepad.echo" -> "Mouse Echo"
            "kdeconnect.mousepad.keyboardstate" -> "Keyboard State"
            "kdeconnect.notification" -> "Notification"
            "kdeconnect.notification.request" -> "Notification Request"
            "kdeconnect.notification.reply" -> "Notification Reply"
            "kdeconnect.share.request" -> "File Share Request"
            "kdeconnect.runcommand" -> "Run Command List"
            "kdeconnect.runcommand.request" -> "Run Command Request"
            "kdeconnect.findmyphone.request" -> "Find My Phone"
            "kdeconnect.telephony" -> "Telephony Event"
            "kdeconnect.sms.messages" -> "SMS Sync"
            "kdeconnect.sms.request" -> "SMS Request"
            "kdeconnect.lockdevice" -> "Lock Device"
            "kdeconnect.bigscreen" -> "Bigscreen Navigation"
            else -> type.removePrefix("kdeconnect.").replace(".", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private fun formatPacketSummary(packet: NetworkPacket): String {
        try {
            return when (packet.type) {
                "kdeconnect.connectivity_report" -> {
                    val sig = packet.getJSONObject("signalStrengths")
                    if (sig != null && sig.length() > 0) {
                        val pairs = mutableListOf<String>()
                        val it = sig.keys()
                        while (it.hasNext()) {
                            val k = it.next().toString()
                            pairs.add("SIM $k: ${sig.optInt(k)}/4 bars")
                        }
                        "Signal Strength: " + pairs.joinToString(", ")
                    } else {
                        "Network connectivity active"
                    }
                }
                "kdeconnect.battery" -> {
                    val charge = packet.getInt("currentCharge")
                    val isCharging = packet.getBoolean("isCharging")
                    val chargeText = if (charge >= 0) "$charge%" else "Unknown"
                    "Battery Level: $chargeText • Charging: ${if (isCharging) "Yes" else "No"}"
                }
                "kdeconnect.ping" -> "Keep-alive ping message"
                "kdeconnect.clipboard", "kdeconnect.clipboard.connect" -> {
                    val content = packet.getString("content")
                    if (content.isNotEmpty()) {
                        val clean = content.replace("\n", " ").trim()
                        val snippet = if (clean.length > 70) clean.take(70) + "..." else clean
                        "Clip: \"$snippet\""
                    } else {
                        "Clipboard synchronization request"
                    }
                }
                "kdeconnect.screenmirror.request" -> {
                    val action = packet.getString("action")
                    val mode = packet.getString("mode")
                    "Action: ${action.ifEmpty { "stream" }}" + (if (mode.isNotEmpty()) " • Mode: $mode" else "")
                }
                "kdeconnect.terminal.input", "kdeconnect.terminal" -> {
                    val cmd = packet.getString("command").ifEmpty { packet.getString("input") }
                    if (cmd.isNotEmpty()) "Command: $cmd" else "Terminal interaction"
                }
                "kdeconnect.terminal.output" -> {
                    val out = packet.getString("output").trim().replace("\n", " ")
                    val snippet = if (out.length > 70) out.take(70) + "..." else out
                    if (snippet.isNotEmpty()) "Output: $snippet" else "Terminal output received"
                }
                "kdeconnect.terminal.signal" -> {
                    val sig = packet.getString("signal").ifEmpty { packet.getString("action") }
                    "Signal: ${sig.ifEmpty { "Interrupt" }}"
                }
                "kdeconnect.mpris", "kdeconnect.mpris.request" -> {
                    val player = packet.getString("player")
                    val title = packet.getString("title")
                    val artist = packet.getString("artist")
                    val action = packet.getString("action")
                    if (title.isNotEmpty()) {
                        "Playing: $title" + (if (artist.isNotEmpty()) " by $artist" else "") + (if (player.isNotEmpty()) " ($player)" else "")
                    } else if (action.isNotEmpty()) {
                        "Control: $action" + (if (player.isNotEmpty()) " ($player)" else "")
                    } else {
                        "Media player state update"
                    }
                }
                "kdeconnect.share.request" -> {
                    val filename = packet.getString("filename")
                    val size = if (packet.hasPayload()) packet.payloadSize else 0L
                    val sizeStr = if (size > 1048576) "${size / 1048576} MB" else if (size > 1024) "${size / 1024} KB" else "$size bytes"
                    "File: ${filename.ifEmpty { "Shared Item" }} ($sizeStr)"
                }
                "kdeconnect.runcommand", "kdeconnect.runcommand.request" -> {
                    val key = packet.getString("key")
                    val setup = packet.getString("commandList")
                    if (key.isNotEmpty()) "Execute command: $key" else if (setup.isNotEmpty()) "Commands list updated" else "Run command event"
                }
                "kdeconnect.notification" -> {
                    val app = packet.getString("appName")
                    val title = packet.getString("title")
                    val ticker = packet.getString("ticker")
                    val name = app.ifEmpty { "App" }
                    val desc = title.ifEmpty { ticker }.replace("\n", " ").trim()
                    val snippet = if (desc.length > 60) desc.take(60) + "..." else desc
                    "$name: $snippet"
                }
                else -> {
                    if (packet.hasPayload()) {
                        "Payload attached (${packet.payloadSize} bytes)"
                    } else {
                        "Packet event received"
                    }
                }
            }
        } catch (_: Exception) {
            return "Packet event"
        }
    }

    fun clipboard(event: String, contentSnippet: String = "") {
        val snippet = if (contentSnippet.length > 100) contentSnippet.take(100) + "..." else contentSnippet
        log(LogLevel.INFO, LogTag.CLIPBOARD, event, snippet)
    }

    fun terminal(action: String, detail: String = "") {
        log(LogLevel.INFO, LogTag.TERMINAL, action, detail)
    }

    fun mirror(action: String, detail: String = "") {
        log(LogLevel.INFO, LogTag.MIRROR, action, detail)
    }

    fun taskManager(action: String, detail: String = "") {
        log(LogLevel.INFO, LogTag.TASK_MANAGER, action, detail)
    }

    fun exportFormattedLogs(): String {
        val logs = getAllLogs()
        return buildString {
            appendLine("=== KDE CONNECT LOG EXPORT ===")
            appendLine("Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Total Entries: ${logs.size}")
            appendLine("========================================\n")
            for (entry in logs) {
                append("[${entry.dateFormatted}] [${entry.level.name}] [${entry.tag.displayName}] ${entry.title}")
                if (entry.message.isNotEmpty()) {
                    append(" -> ").append(entry.message)
                }
                appendLine()
            }
        }
    }

    fun shareLogs(context: Context) {
        try {
            val content = exportFormattedLogs()
            val fileName = "kdeconnect_logs_${System.currentTimeMillis()}.txt"
            val file = File(context.cacheDir, fileName)
            file.writeText(content)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "KDE Connect Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export KDE Connect Logs"))
        } catch (e: Exception) {
            Log.e("KdeLog", "Failed to share logs", e)
        }
    }
}
