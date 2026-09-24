/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.helpers

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SshManager {
    private const val TAG = "SshManager"
    private const val PREFS_NAME = "kdeconnect_ssh_prefs"

    private var sshClient: SshClient? = null
    private val activeSessions = ConcurrentHashMap<String, ClientSession>()
    private val activeChannels = ConcurrentHashMap<String, ChannelExec>()
    private val activePipedInputs = ConcurrentHashMap<String, PipedOutputStream>()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class SshCredentials(
        val host: String,
        val port: Int = 22,
        val user: String,
        val pass: String
    )

    data class RemoteFileItem(
        val name: String,
        val fullPath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    private fun getClient(): SshClient {
        if (sshClient == null || !sshClient!!.isOpen) {
            sshClient = SshClient.setUpDefaultClient()
            sshClient!!.start()
        }
        return sshClient!!
    }

    fun isConnected(deviceId: String): Boolean {
        val session = activeSessions[deviceId]
        return session != null && session.isOpen
    }

    fun getSession(deviceId: String): ClientSession? {
        val session = activeSessions[deviceId]
        return if (session != null && session.isOpen) session else null
    }

    fun getSavedCredentials(context: Context, deviceId: String): SshCredentials? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val user = prefs.getString("user_" + deviceId, null)
        if (user.isNullOrEmpty()) return null

        val savedHost = prefs.getString("host_" + deviceId, "") ?: ""
        val port = prefs.getInt("port_" + deviceId, 22)
        val pass = prefs.getString("pass_" + deviceId, "") ?: ""

        val liveIp = try {
            org.kde.kdeconnect.KdeConnect.getInstance().getDevice(deviceId)?.getRemoteIpAddress()
        } catch (_: Exception) { null }

        val effectiveHost = if (!liveIp.isNullOrEmpty()) liveIp else savedHost
        if (effectiveHost.isEmpty()) return null

        return SshCredentials(effectiveHost, port, user, pass)
    }

    fun saveCredentials(context: Context, deviceId: String, creds: SshCredentials) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUser = prefs.getString("user_" + deviceId, "") ?: ""
        val savedPass = prefs.getString("pass_" + deviceId, "") ?: ""
        val savedPort = prefs.getInt("port_" + deviceId, 22)
        val savedHost = prefs.getString("host_" + deviceId, "") ?: ""

        val effectiveUser = if (creds.user.isNotEmpty()) creds.user else savedUser
        val effectivePass = if (creds.pass.isNotEmpty() || creds.user.isNotEmpty()) creds.pass else savedPass
        val effectivePort = if (creds.port > 0) creds.port else (if (savedPort > 0) savedPort else 22)
        val effectiveHost = if (creds.host.isNotEmpty()) creds.host else savedHost

        prefs.edit()
            .putString("host_" + deviceId, effectiveHost)
            .putInt("port_" + deviceId, effectivePort)
            .putString("user_" + deviceId, effectiveUser)
            .putString("pass_" + deviceId, effectivePass)
            .apply()
    }
    fun clearCredentials(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("host_" + deviceId)
            .remove("port_" + deviceId)
            .remove("user_" + deviceId)
            .remove("pass_" + deviceId)
            .apply()
        disconnect(deviceId)
    }

    fun connect(
        deviceId: String,
        creds: SshCredentials,
        callback: (Boolean, String?) -> Unit
    ) {
        ThreadHelper.execute {
            try {
                disconnect(deviceId)
                val client = getClient()
                val connectFuture = client.connect(creds.user, creds.host, creds.port).verify(10, TimeUnit.SECONDS)
                val session = connectFuture.session
                session.addPasswordIdentity(creds.pass)
                session.auth().verify(10, TimeUnit.SECONDS)
                activeSessions[deviceId] = session
                Log.i(TAG, "SSH connection established to " + creds.user + "@" + creds.host + ":" + creds.port)
                mainHandler.post { callback(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "SSH connection failed: " + e.message, e)
                mainHandler.post { callback(false, e.message ?: "Connection failed") }
            }
        }
    }

    fun executeCommand(
        deviceId: String,
        command: String,
        onOutput: (String, Boolean) -> Unit,
        onFinished: ((Int) -> Unit)? = null
    ) {
        executeCommand(deviceId, command, false, onOutput, onFinished)
    }

    fun executeCommand(
        deviceId: String,
        command: String,
        usePty: Boolean,
        onOutput: (String, Boolean) -> Unit,
        onFinished: ((Int) -> Unit)? = null
    ) {
        val session = getSession(deviceId)
        if (session == null || !session.isOpen) {
            onOutput("SSH Error: Not connected to remote host\n", true)
            onFinished?.invoke(-1)
            return
        }

        ThreadHelper.execute {
            var channel: ChannelExec? = null
            try {
                // Cancel any previous running channel on this device
                stopActiveCommand(deviceId)

                val isClearCmd = command.trim().equals("clear", ignoreCase = true) || command.trim().equals("cls", ignoreCase = true)
                val execCmd = if (isClearCmd) "clear" else command
                val wrappedCmd = "export TERM=xterm-256color LANG=en_US.UTF-8 PATH=\"\$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"; $execCmd"
                channel = session.createExecChannel(wrappedCmd).apply {
                    if (usePty) {
                        setUsePty(true)
                        setPtyType("xterm-256color")
                        setPtyColumns(125)
                        setPtyLines(35)
                    } else {
                        setUsePty(false)
                    }
                    setEnv("TERM", "xterm-256color")
                    setEnv("LANG", "en_US.UTF-8")
                    setEnv("LC_ALL", "en_US.UTF-8")
                }
                activeChannels[deviceId] = channel

                val stdoutStream = ByteArrayOutputStream()
                val stderrStream = ByteArrayOutputStream()
                channel.out = stdoutStream
                channel.err = stderrStream

                val pipedOut = PipedOutputStream()
                val pipedIn = PipedInputStream(pipedOut, 16384)
                channel.setIn(pipedIn)
                activePipedInputs[deviceId] = pipedOut

                channel.open().verify(5, TimeUnit.SECONDS)

                var lastStdoutLen = 0
                var lastStderrLen = 0
                while (!channel.isClosed) {
                    val currentOut = stdoutStream.toByteArray()
                    if (currentOut.size > lastStdoutLen) {
                        val chunk = String(currentOut, lastStdoutLen, currentOut.size - lastStdoutLen, Charsets.UTF_8)
                        lastStdoutLen = currentOut.size
                        mainHandler.post { onOutput(chunk, false) }
                    }

                    val currentErr = stderrStream.toByteArray()
                    if (currentErr.size > lastStderrLen) {
                        val chunk = String(currentErr, lastStderrLen, currentErr.size - lastStderrLen, Charsets.UTF_8)
                        lastStderrLen = currentErr.size
                        mainHandler.post { onOutput(chunk, true) }
                    }
                    Thread.sleep(80)
                }

                val currentOut = stdoutStream.toByteArray()
                if (currentOut.size > lastStdoutLen) {
                    val chunk = String(currentOut, lastStdoutLen, currentOut.size - lastStdoutLen, Charsets.UTF_8)
                    mainHandler.post { onOutput(chunk, false) }
                }
                val currentErr = stderrStream.toByteArray()
                if (currentErr.size > lastStderrLen) {
                    val chunk = String(currentErr, lastStderrLen, currentErr.size - lastStderrLen, Charsets.UTF_8)
                    mainHandler.post { onOutput(chunk, true) }
                }

                val exitStatus = channel.exitStatus ?: 0
                mainHandler.post { onFinished?.invoke(exitStatus) }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error: " + e.message, e)
                mainHandler.post {
                    onOutput("Execution failed: " + e.message + "\n", true)
                    onFinished?.invoke(-1)
                }
            } finally {
                activePipedInputs.remove(deviceId)?.let { try { it.close() } catch (_: Exception) {} }
                activeChannels.remove(deviceId)
                try { channel?.close(false) } catch (_: Exception) {}
            }
        }
    }

    fun hasActiveCommand(deviceId: String): Boolean {
        val ch = activeChannels[deviceId]
        return ch != null && !ch.isClosed && ch.isOpen
    }

    fun sendInput(deviceId: String, input: String): Boolean {
        val pipedOut = activePipedInputs[deviceId]
        val channel = activeChannels[deviceId]
        if (pipedOut != null && channel != null && !channel.isClosed && channel.isOpen) {
            try {
                val fullInput = if (input.endsWith("\n") || input.endsWith("\r")) input else input + "\n"
                val bytes = fullInput.toByteArray(Charsets.UTF_8)
                pipedOut.write(bytes)
                pipedOut.flush()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to piped stdin: " + e.message, e)
            }
        }
        return false
    }

    fun stopActiveCommand(deviceId: String) {
        activePipedInputs.remove(deviceId)?.let { try { it.close() } catch (_: Exception) {} }
        val channel = activeChannels.remove(deviceId)
        if (channel != null) {
            try {
                // closed immediately below
            } catch (_: Exception) {}
            try {
                channel.close(true)
            } catch (_: Exception) {}
        }
        val session = getSession(deviceId)
        if (session != null && session.isOpen) {
            ThreadHelper.execute {
                try {
                    val killChannel = session.createExecChannel("kill -INT $(jobs -p) 2>/dev/null || pkill -INT -P $$ 2>/dev/null || true")
                    killChannel.open().verify(2, TimeUnit.SECONDS)
                    killChannel.close(false)
                } catch (_: Exception) {}
            }
        }
    }

    fun executeRemoteCopyOrMove(
        deviceId: String,
        isCut: Boolean,
        srcPaths: List<String>,
        destDir: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val dest = if (destDir == ".") "." else destDir.trimEnd('/')
        val cmd = buildString {
            for (src in srcPaths) {
                val escapedSrc = src.replace("'", "'\\''")
                val escapedDest = dest.replace("'", "'\\''")
                if (isCut) {
                    append("mv -f '$escapedSrc' '$escapedDest/' 2>&1; ")
                } else {
                    append("cp -rf '$escapedSrc' '$escapedDest/' 2>&1; ")
                }
            }
        }
        executeCommand(deviceId, cmd, false, { _, _ -> }, onFinished = { exitCode ->
            mainHandler.post { callback(exitCode == 0, if (exitCode != 0) "Operation exit code: $exitCode" else null) }
        })
    }

    fun listRemoteDirectory(
        deviceId: String,
        remotePath: String,
        callback: (Boolean, List<RemoteFileItem>?, String?) -> Unit
    ) {
        val session = getSession(deviceId)
        if (session == null || !session.isOpen) {
            callback(false, null, "SSH session not connected")
            return
        }

        ThreadHelper.execute {
            var sftp: SftpClient? = null
            try {
                sftp = SftpClientFactory.instance().createSftpClient(session)
                val targetPath = if (remotePath.isBlank()) "." else remotePath
                val items = mutableListOf<RemoteFileItem>()

                for (entry in sftp.readDir(targetPath)) {
                    val filename = entry.filename
                    if (filename == "." || filename == "..") continue

                    val attrs = entry.attributes
                    val isDir = attrs.isDirectory
                    val size = attrs.size
                    val mtime = attrs.modifyTime?.toMillis() ?: 0L
                    val separator = if (targetPath.endsWith("/")) "" else "/"
                    val full = if (targetPath == ".") filename else (targetPath + separator + filename)

                    items.add(RemoteFileItem(filename, full, isDir, size, mtime))
                }

                items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                mainHandler.post { callback(true, items, null) }
            } catch (e: Exception) {
                Log.e(TAG, "SFTP readDir error: " + e.message, e)
                mainHandler.post { callback(false, null, e.message) }
            } finally {
                try { sftp?.close() } catch (_: Exception) {}
            }
        }
    }

    fun downloadFolderAsZip(
        deviceId: String,
        remoteFolderPath: String,
        destinationZipFile: File,
        onProgress: (Long) -> Unit,
        callback: (Boolean, String?) -> Unit
    ) {
        val session = getSession(deviceId)
        if (session == null || !session.isOpen) {
            callback(false, "SSH session not connected")
            return
        }

        ThreadHelper.execute {
            var channel: ChannelExec? = null
            try {
                destinationZipFile.parentFile?.mkdirs()
                val cmd = "cd \"" + remoteFolderPath + "\" && (zip -r - . 2>/dev/null || tar -czf - . 2>/dev/null)"
                channel = session.createExecChannel(cmd)
                val outStream = FileOutputStream(destinationZipFile)

                channel.open().verify(5, TimeUnit.SECONDS)
                val inStream = channel.invertedOut
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalBytes: Long = 0

                inStream.use { input ->
                    outStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            mainHandler.post { onProgress(totalBytes) }
                        }
                    }
                }

                channel.waitFor(listOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 30000)
                mainHandler.post { callback(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Download zip error: " + e.message, e)
                mainHandler.post { callback(false, e.message) }
            } finally {
                try { channel?.close(false) } catch (_: Exception) {}
            }
        }
    }

    fun fetchLaptopClipboardHistory(
        deviceId: String,
        callback: (Boolean, List<String>?, String?) -> Unit
    ) {
        val session = getSession(deviceId)
        if (session == null || !session.isOpen) {
            callback(false, null, "SSH session not connected")
            return
        }

        ThreadHelper.execute {
            var channel: ChannelExec? = null
            try {
                val cmd = "qdbus6 org.kde.klipper /klipper org.kde.klipper.klipper.getClipboardHistoryMenu 2>/dev/null || qdbus org.kde.klipper /klipper getClipboardHistoryMenu 2>/dev/null || wl-paste 2>/dev/null"
                channel = session.createExecChannel(cmd)
                val outStream = ByteArrayOutputStream()
                channel.out = outStream

                channel.open().verify(5, TimeUnit.SECONDS)
                channel.waitFor(listOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 10000)

                val output = outStream.toString(Charsets.UTF_8)
                val list = mutableListOf<String>()
                for (line in output.split("\n")) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !list.contains(trimmed)) {
                        list.add(trimmed)
                    }
                }
                mainHandler.post { callback(true, list, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch clipboard history error: " + e.message, e)
                mainHandler.post { callback(false, null, e.message) }
            } finally {
                try { channel?.close(false) } catch (_: Exception) {}
            }
        }
    }

    fun downloadRemoteFile(
        deviceId: String,
        remoteFilePath: String,
        destinationFile: File,
        onProgress: (Long, Long) -> Unit,
        callback: (Boolean, String?) -> Unit
    ) {
        val session = getSession(deviceId)
        if (session == null || !session.isOpen) {
            callback(false, "SSH session not connected")
            return
        }

        ThreadHelper.execute {
            var sftp: SftpClient? = null
            try {
                destinationFile.parentFile?.mkdirs()
                sftp = SftpClientFactory.instance().createSftpClient(session)
                val stat = sftp.stat(remoteFilePath)
                val totalBytes = stat.size

                val inStream: InputStream = sftp.read(remoteFilePath)
                val outStream = FileOutputStream(destinationFile)
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalRead: Long = 0

                inStream.use { input ->
                    outStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            mainHandler.post { onProgress(totalRead, totalBytes) }
                        }
                    }
                }

                mainHandler.post { callback(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "SFTP download error: " + e.message, e)
                mainHandler.post { callback(false, e.message) }
            } finally {
                try { sftp?.close() } catch (_: Exception) {}
            }
        }
    }

    fun disconnect(deviceId: String) {
        try {
            stopActiveCommand(deviceId)
            activeSessions.remove(deviceId)?.close(false)
        } catch (_: Exception) {}
    }

    fun showSshDialog(
        activity: Activity,
        deviceId: String,
        defaultHost: String? = null,
        onConnected: (() -> Unit)? = null
    ) {
        val saved = getSavedCredentials(activity, deviceId)
        val liveIp = try {
            org.kde.kdeconnect.KdeConnect.getInstance().getDevice(deviceId)?.getRemoteIpAddress()
        } catch (_: Exception) { null }
        val hostValue = (if (!liveIp.isNullOrEmpty()) liveIp else saved?.host) ?: defaultHost ?: ""
        val portValue = (saved?.port ?: 22).toString()
        val userValue = saved?.user ?: ""
        val passValue = saved?.pass ?: ""

        val layout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val editHost = EditText(activity).apply {
            hint = "Laptop Host / IP Address"
            setText(hostValue)
        }
        val editPort = EditText(activity).apply {
            hint = "SSH Port (Default 22)"
            setText(portValue)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val editUser = EditText(activity).apply {
            hint = "Username (e.g. user)"
            setText(userValue)
        }
        val editPass = EditText(activity).apply {
            hint = "SSH / Login Password"
            setText(passValue)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val checkSave = CheckBox(activity).apply {
            text = "Save credentials for automatic connection"
            isChecked = true
        }

        layout.addView(editHost)
        layout.addView(editPort)
        layout.addView(editUser)
        layout.addView(editPass)
        layout.addView(checkSave)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Connect to Laptop (SSH / SFTP)")
            .setMessage("Enter your laptop login credentials to execute live commands and browse files:")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val host = editHost.text.toString().trim()
                val port = editPort.text.toString().trim().toIntOrNull() ?: 22
                val user = editUser.text.toString().trim()
                val pass = editPass.text.toString()

                if (host.isEmpty() || user.isEmpty()) {
                    Toast.makeText(activity, "Host and Username cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val creds = SshCredentials(host, port, user, pass)
                if (checkSave.isChecked) {
                    saveCredentials(activity, deviceId, creds)
                }

                val progress = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("Connecting to SSH")
                    .setMessage("Authenticating with " + user + "@" + host + ":" + port + "...")
                    .setCancelable(false)
                    .create()
                progress.show()

                connect(deviceId, creds) { success, error ->
                    progress.dismiss()
                    if (success) {
                        Toast.makeText(activity, "SSH Connected Successfully!", Toast.LENGTH_SHORT).show()
                        onConnected?.invoke()
                    } else {
                        Toast.makeText(activity, "SSH Connection Failed: " + error, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
