/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.terminal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.SshManager
import org.kde.kdeconnect.ui.compose.KdeTheme

class TerminalActivity : AppCompatActivity(), TerminalPlugin.TerminalOutputListener {

    private lateinit var deviceId: String
    private var deviceName by mutableStateOf("Computer")
    private var isSshConnected by mutableStateOf(false)
    private var terminalOutputText by mutableStateOf("")

    private val plugin: TerminalPlugin?
        get() = KdeConnect.getInstance().getDevicePlugin(deviceId, TerminalPlugin::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            finish()
            return
        }

        val device = KdeConnect.getInstance().getDevice(deviceId)
        deviceName = device?.name ?: "Computer"

        plugin?.registerListener(this)
        initSshConnection()

        setContent {
            KdeTheme(this@TerminalActivity) {
                TerminalScreen(
                    deviceName = deviceName,
                    isSshConnected = isSshConnected,
                    terminalOutput = terminalOutputText,
                    onBackPressedDispatcher = onBackPressedDispatcher,
                    onSendCommand = { cmd -> executeCommand(cmd) },
                    onSendInterrupt = { plugin?.sendInterrupt() },
                    onSendCtrlZ = { plugin?.sendCtrlZ() },
                    onSendTab = { plugin?.sendTab() },
                    onSendEsc = { plugin?.sendEsc() },
                    onClearTerminal = { terminalOutputText = "" },
                    onConnectSsh = {
                        val host = KdeConnect.getInstance().getDevice(deviceId)?.getRemoteIpAddress()
                        val saved = SshManager.getSavedCredentials(this@TerminalActivity, deviceId)
                        if (saved != null && saved.user.isNotEmpty()) {
                            val effectiveCreds = if (!host.isNullOrEmpty() && host != "127.0.0.1") saved.copy(host = host) else saved
                            val progress = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TerminalActivity)
                                .setTitle("Connecting to SSH")
                                .setMessage("Authenticating with \${effectiveCreds.user}@\${effectiveCreds.host}:\${effectiveCreds.port}...")
                                .setCancelable(false)
                                .create()
                            progress.show()

                            SshManager.connect(deviceId, effectiveCreds) { success, error ->
                                progress.dismiss()
                                isSshConnected = success
                                if (success) {
                                    android.widget.Toast.makeText(this@TerminalActivity, "SSH Connected Successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                                                    } else {
                                    android.widget.Toast.makeText(this@TerminalActivity, "Auto-reconnect failed: \$error", android.widget.Toast.LENGTH_LONG).show()
                                    SshManager.showSshDialog(this@TerminalActivity, deviceId, host) {
                                        isSshConnected = SshManager.isConnected(deviceId)
                                                                            }
                                }
                            }
                        } else {
                            SshManager.showSshDialog(this@TerminalActivity, deviceId, host) {
                                isSshConnected = SshManager.isConnected(deviceId)
                                                            }
                        }
                    }
                )
            }
        }
    }

    private fun initSshConnection() {
        isSshConnected = SshManager.isConnected(deviceId)
        val saved = SshManager.getSavedCredentials(this, deviceId)
        if (saved != null && !isSshConnected) {
            SshManager.connect(deviceId, saved) { success, _ ->
                isSshConnected = success
                if (success) {
                                    }
            }
        }
    }

    private fun executeCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.equals("clear", ignoreCase = true) || trimmed.equals("cls", ignoreCase = true)) {
            terminalOutputText = ""
            plugin?.sendCommand(cmd)
            return
        }

        val creds = SshManager.getSavedCredentials(this, deviceId)
        val user = creds?.user ?: "user"
        val host = if (deviceName.isNotEmpty() && deviceName != "Computer") deviceName else (KdeConnect.getInstance().getDevice(deviceId)?.name ?: "host")
        val prompt = "[$user@$host ~]$ $cmd\n"

        if (terminalOutputText.isNotEmpty() && !terminalOutputText.endsWith("\n")) {
            terminalOutputText += "\n"
        }
        terminalOutputText += prompt
        plugin?.sendCommand(cmd)
    }

    override fun onTerminalOutput(text: String, isError: Boolean) {
        appendOutput(text)
    }

    override fun onTerminalClear() {
        terminalOutputText = ""
    }

    private val ansiRegex = Regex(
        "\\u001B\\][0-9]+;[^\\u0007\\u001B\\\\\n]*(\\u0007|\\u001B\\\\|\\\\)?" +
        "|\\][0-9]+;[^\\u0007\\u001B\\\\\n]*(\\u0007|\\u001B\\\\|\\\\)?" +
        "|\\u001B\\[[?0-9;]*[a-zA-Z~]" +
        "|\\u001B\\([a-zA-Z]" +
        "|\\u001B[=>NOPQRSTUXYZ\\]^_~]"
    )
    private fun appendOutput(text: String) {
        if (text.contains("\u001b[2J") || text.contains("\u001b[H") || text.contains("\u001b[3J") || text.contains("\u001bc")) {
            terminalOutputText = ""
        }
        var cleanText = text.replace(ansiRegex, "")
        cleanText = cleanText.replace("\r\n", "\n").replace("\r", "\n")
        terminalOutputText += cleanText
        if (terminalOutputText.length > 50000) {
            terminalOutputText = terminalOutputText.takeLast(30000)
        }
    }

    override fun onDestroy() {
        plugin?.unregisterListener(this)
        super.onDestroy()
    }
}
