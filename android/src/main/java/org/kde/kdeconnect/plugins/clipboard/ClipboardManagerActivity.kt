/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.clipboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.SshManager
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.logging.KdeLog
import org.kde.kdeconnect.ui.compose.KdeTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ClipboardManagerActivity : AppCompatActivity() {

    private lateinit var deviceId: String
    private val localHistoryList = mutableStateListOf<ClipboardHistoryManager.ClipboardHistoryItem>()
    private val laptopHistoryList = mutableStateListOf<String>()
    private var deviceName by mutableStateOf("Computer")

    private val clipboardObserver = object : ClipboardListener.ClipboardObserver {
        override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
            runOnUiThread {
                loadLocalHistory()
            }
        }
    }

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

        ClipboardListener.instance(this).registerObserver(clipboardObserver)

        loadLocalHistory()
        loadLaptopHistory()

        setContent {
            KdeTheme(this@ClipboardManagerActivity) {
                ClipboardManagerScreen(
                    deviceName = deviceName,
                    onBackPressedDispatcher = onBackPressedDispatcher,
                    localItems = localHistoryList,
                    laptopItems = laptopHistoryList,
                    onSendToLaptop = { text -> sendToLaptop(text) },
                    onDeleteLocalItem = { id -> deleteLocalItem(id) },
                    onClearAll = { clearAllLocalItems() },
                    onRefreshLaptop = { loadLaptopHistory() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ClipboardListener.instance(this).refreshFromSystem()
        loadLocalHistory()
        loadLaptopHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        ClipboardListener.instance(this).removeObserver(clipboardObserver)
    }

    private fun loadLocalHistory() {
        val items = ClipboardHistoryManager.getHistory(this).filter { item ->
            ClipboardListener.isValidClipboardText(item.content)
        }
        localHistoryList.clear()
        localHistoryList.addAll(items)
    }

    private fun saveLaptopHistory(items: List<String>) {
        val filtered = items.filter { ClipboardListener.isValidClipboardText(it) }
        val prefs = getSharedPreferences("kdeconnect_clipboard_history", MODE_PRIVATE)
        val ja = org.json.JSONArray(filtered)
        prefs.edit().putString("cached_laptop_history_" + deviceId, ja.toString()).apply()
    }

    private fun getCachedLaptopHistory(): List<String> {
        val prefs = getSharedPreferences("kdeconnect_clipboard_history", MODE_PRIVATE)
        val jsonStr = prefs.getString("cached_laptop_history_" + deviceId, null) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val ja = org.json.JSONArray(jsonStr)
            for (i in 0 until ja.length()) {
                val s = ja.getString(i)
                if (ClipboardListener.isValidClipboardText(s)) {
                    list.add(s)
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun loadLaptopHistory() {
        val cached = getCachedLaptopHistory()
        if (cached.isNotEmpty()) {
            laptopHistoryList.clear()
            laptopHistoryList.addAll(cached)
        }

        val device = KdeConnect.getInstance().getDevice(deviceId)
        val host = device?.getRemoteIpAddress().takeIf { !it.isNullOrEmpty() } ?: "127.0.0.1"

        if (host.isNotEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 2000
                        requestMethod = "GET"
                    }
                    if (conn.responseCode == 200) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val json = JSONObject(resp)
                        val curr = json.optString("current", "").ifEmpty { json.optString("clipboard", "") }.trim()
                        val histArray = json.optJSONArray("history")
                        val list = mutableListOf<String>()
                        if (ClipboardListener.isValidClipboardText(curr)) {
                            list.add(curr)
                        }
                        if (histArray != null) {
                            for (i in 0 until histArray.length()) {
                                val item = histArray.optString(i).trim()
                                if (ClipboardListener.isValidClipboardText(item) && !list.contains(item)) {
                                    list.add(item)
                                }
                            }
                        }
                        runOnUiThread {
                            saveLaptopHistory(list)
                            laptopHistoryList.clear()
                            laptopHistoryList.addAll(list)
                            KdeLog.clipboard("Fetched Laptop History", "${list.size} clips from $host")
                        }
                        return@execute
                    }
                } catch (e: Exception) {
                    // Fallback to SSH
                }

                // Fallback to SSH
                SshManager.fetchLaptopClipboardHistory(deviceId) { success, items, error ->
                    if (success && items != null) {
                        val validItems = items.filter { ClipboardListener.isValidClipboardText(it) }
                        saveLaptopHistory(validItems)
                        laptopHistoryList.clear()
                        laptopHistoryList.addAll(validItems)
                        KdeLog.clipboard("Fetched Laptop History (SSH)", "${validItems.size} clips")
                    } else if (error != null) {
                        KdeLog.w(KdeLog.LogTag.CLIPBOARD, "Laptop clipboard fetch notice", error)
                    }
                }
            }
        } else {
            SshManager.fetchLaptopClipboardHistory(deviceId) { success, items, error ->
                if (success && items != null) {
                    val validItems = items.filter { ClipboardListener.isValidClipboardText(it) }
                    laptopHistoryList.clear()
                    laptopHistoryList.addAll(validItems)
                }
            }
        }
    }

    private fun sendToLaptop(text: String) {
        if (!ClipboardListener.isValidClipboardText(text)) return
        val safeText = text.trim()
        ClipboardHistoryManager.addItem(this, safeText, isReceived = false)
        loadLocalHistory()

        val np = NetworkPacket("kdeconnect.clipboard").apply {
            set("content", safeText)
        }
        val device = KdeConnect.getInstance().getDevice(deviceId)
        device?.sendPacket(np)
        ClipboardListener.instance(this).setText(safeText)

        // Also post directly to daemon for instant Wayland / CachyOS wl-copy
        val host = device?.getRemoteIpAddress().takeIf { !it.isNullOrEmpty() } ?: "127.0.0.1"
        if (host.isNotEmpty()) {
            ThreadHelper.execute {
                try {
                    val url = URL("http://$host:59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 1500
                        requestMethod = "POST"
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(safeText) }
                    conn.responseCode
                } catch (ignored: Exception) {}
            }
        }

        KdeLog.clipboard("Sent to Laptop ($deviceName)", safeText)
        Toast.makeText(this, "Sent to $deviceName: ${safeText.take(30)}...", Toast.LENGTH_SHORT).show()
    }

    private fun deleteLocalItem(id: String) {
        ClipboardHistoryManager.removeItem(this, id)
        loadLocalHistory()
    }

    private fun clearAllLocalItems() {
        ClipboardHistoryManager.clearHistory(this)
        loadLocalHistory()
    }
}
