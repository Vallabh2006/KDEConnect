/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.terminal

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.SshManager
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.logging.KdeLog
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@LoadablePlugin
class TerminalPlugin : Plugin() {

    interface TerminalOutputListener {
        fun onTerminalOutput(text: String, isError: Boolean = false)
        fun onTerminalClear() {}
    }

    private val listeners = CopyOnWriteArrayList<TerminalOutputListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isPollingActive = AtomicBoolean(false)
    private var pollingThread: Thread? = null

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_terminal)

    override val description: String
        get() = context.getString(R.string.pref_plugin_terminal_desc)

    override fun hasSettings(): Boolean = false

    override fun getUiButtons(): List<PluginUiButton> {
        val button = PluginUiButton(
            displayName,
            R.drawable.ic_terminal_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, TerminalActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        }
        return listOf(button)
    }

    fun registerListener(listener: TerminalOutputListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        startDaemonPolling()
    }

    fun unregisterListener(listener: TerminalOutputListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopDaemonPolling()
        }
    }

    private fun startDaemonPolling() {
        if (isPollingActive.getAndSet(true)) return

        pollingThread = Thread({
            while (isPollingActive.get()) {
                val host = device.getRemoteIpAddress()
                if (!host.isNullOrEmpty() && !SshManager.isConnected(device.deviceId)) {
                    try {
                        val url = URL("http://$host:59001/terminal_poll")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 800
                            readTimeout = 800
                            requestMethod = "GET"
                        }
                        if (conn.responseCode == 200) {
                            val out = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                            if (out.isNotEmpty()) {
                                handleTerminalOutput(out, false)
                            }
                        }
                    } catch (ignored: Exception) {}
                }
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "Terminal-Daemon-Poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopDaemonPolling() {
        isPollingActive.set(false)
        pollingThread?.interrupt()
        pollingThread = null
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_TERMINAL, PACKET_TYPE_TERMINAL_OUTPUT -> {
                val output = np.getString("output", np.getString("text", ""))
                val isError = np.getBoolean("isError", false)
                if (output.isNotEmpty()) {
                    handleTerminalOutput(output, isError)
                }
                return true
            }
            PACKET_TYPE_RUNCOMMAND, PACKET_TYPE_RUNCOMMAND_OUTPUT -> {
                if (np.has("commandOutput")) {
                    val stdoutList = np.getStringList("stdout")
                    if (stdoutList != null) {
                        for (line in stdoutList) {
                            handleTerminalOutput(line + "\n", false)
                        }
                    }
                    val stderrList = np.getStringList("stderr")
                    if (stderrList != null) {
                        for (line in stderrList) {
                            handleTerminalOutput(line + "\n", true)
                        }
                    }
                    return true
                } else if (np.has("output") || np.has("stdout")) {
                    val out = np.getString("output", np.getString("stdout", ""))
                    if (out.isNotEmpty()) {
                        handleTerminalOutput(out + "\n", false)
                    }
                    return true
                } else if (np.has("commandFinished")) {
                    val success = np.getBoolean("success", true)
                    handleTerminalOutput(if (success) "[Process finished]\n" else "[Process failed]\n", !success)
                    return true
                }
            }
        }
        return false
    }

    private fun handleTerminalOutput(text: String, isError: Boolean = false) {
        if (text.contains("\u001b[2J") || text.contains("\u001b[H") || text.contains("\u001b[3J")) {
            dispatchClear()
        }
        KdeLog.terminal(if (isError) "STDERR" else "STDOUT", text.trim())
        dispatchOutput(text, isError)
    }

    fun dispatchOutput(text: String, isError: Boolean = false) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onTerminalOutput(text, isError)
            }
        }
    }

    fun dispatchClear() {
        mainHandler.post {
            for (listener in listeners) {
                listener.onTerminalClear()
            }
        }
    }

    fun sendCommand(command: String) {
        val trimmed = command.trim()
        KdeLog.terminal("EXEC", command)

        if (trimmed.equals("clear", ignoreCase = true) || trimmed.equals("cls", ignoreCase = true)) {
            dispatchClear()
        }

        // 1. If SSH is connected, execute directly over SSH PTY
        if (SshManager.isConnected(device.deviceId)) {
            if (SshManager.hasActiveCommand(device.deviceId)) {
                val inputWithCrLf = if (command.endsWith("\r\n")) command else if (command.endsWith("\n")) command.dropLast(1) + "\r\n" else command + "\r\n"
                val sent = SshManager.sendInput(device.deviceId, inputWithCrLf)
                if (sent) return
            }

            SshManager.executeCommand(
                deviceId = device.deviceId,
                command = command,
                usePty = true,
                onOutput = { text, isError ->
                    handleTerminalOutput(text, isError)
                },
                onFinished = { exitCode ->
                    if (exitCode != 0) {
                        dispatchOutput("[Exit code: $exitCode]\n", true)
                    }
                }
            )
            return
        }

        // 2. Direct HTTP Daemon PTY Stream (Real Linux PTY with sudo, clear, pacman)
        val host = device.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/terminal_input")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1000
                        readTimeout = 1000
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(command + "\n") }
                    if (conn.responseCode == 200) {
                        return@execute
                    }
                } catch (ignored: Exception) {}

                // Fallback packet transmission
                sendPackets(command)
            }
            return
        }

        sendPackets(command)
    }

    private fun sendPackets(command: String) {
        val npTerminal = NetworkPacket(PACKET_TYPE_TERMINAL_INPUT).apply {
            set("command", command)
            set("input", command + "\n")
        }
        device.sendPacket(npTerminal)

        val npRun = NetworkPacket(PACKET_TYPE_RUNCOMMAND_REQUEST).apply {
            set("command", command)
            set("run", command)
            set("cmd", command)
        }
        device.sendPacket(npRun)
    }

    fun sendInterrupt() {
        KdeLog.terminal("SIGNAL", "SIGINT (Ctrl+C)")
        if (SshManager.isConnected(device.deviceId)) {
            SshManager.stopActiveCommand(device.deviceId)
        }
        val host = device.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/terminal_signal")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write("ctrl+c") }
                    conn.responseCode
                } catch (ignored: Exception) {}
            }
        }
        val npSignal = NetworkPacket(PACKET_TYPE_TERMINAL_SIGNAL).apply {
            set("signal", "SIGINT")
            set("key", "ctrl+c")
        }
        device.sendPacket(npSignal)
    }

    fun sendCtrlZ() {
        KdeLog.terminal("SIGNAL", "SIGTSTP (Ctrl+Z)")
        val host = device.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/terminal_signal")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write("ctrl+z") }
                    conn.responseCode
                } catch (ignored: Exception) {}
            }
        }
    }

    fun sendTab() {
        if (SshManager.isConnected(device.deviceId) && SshManager.hasActiveCommand(device.deviceId)) {
            SshManager.sendInput(device.deviceId, "\t")
            return
        }
        val host = device.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/terminal_input")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write("\t") }
                    conn.responseCode
                } catch (ignored: Exception) {}
            }
            return
        }
        val np = NetworkPacket(PACKET_TYPE_TERMINAL_INPUT).apply {
            set("key", "\t")
        }
        device.sendPacket(np)
    }

    fun sendEsc() {
        if (SshManager.isConnected(device.deviceId) && SshManager.hasActiveCommand(device.deviceId)) {
            SshManager.sendInput(device.deviceId, "\u001b")
            return
        }
        val host = device.getRemoteIpAddress()
        if (!host.isNullOrEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/terminal_input")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write("\u001b") }
                    conn.responseCode
                } catch (ignored: Exception) {}
            }
            return
        }
        val np = NetworkPacket(PACKET_TYPE_TERMINAL_INPUT).apply {
            set("key", "\u001b")
        }
        device.sendPacket(np)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_TERMINAL,
        PACKET_TYPE_TERMINAL_OUTPUT,
        PACKET_TYPE_RUNCOMMAND,
        PACKET_TYPE_RUNCOMMAND_OUTPUT
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_TERMINAL,
        PACKET_TYPE_TERMINAL_INPUT,
        PACKET_TYPE_TERMINAL_SIGNAL,
        PACKET_TYPE_RUNCOMMAND_REQUEST
    )

    companion object {
        const val PACKET_TYPE_TERMINAL = "kdeconnect.terminal"
        const val PACKET_TYPE_TERMINAL_INPUT = "kdeconnect.terminal.input"
        const val PACKET_TYPE_TERMINAL_OUTPUT = "kdeconnect.terminal.output"
        const val PACKET_TYPE_TERMINAL_SIGNAL = "kdeconnect.terminal.signal"
        const val PACKET_TYPE_RUNCOMMAND = "kdeconnect.runcommand"
        const val PACKET_TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
        const val PACKET_TYPE_RUNCOMMAND_OUTPUT = "kdeconnect.runcommand.output"
    }
}
