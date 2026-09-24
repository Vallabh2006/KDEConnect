/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.clipboard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ClipboardHistoryManager {
    private const val PREFS_NAME = "kdeconnect_clipboard_history"
    private const val KEY_HISTORY = "history_items"
    private const val MAX_HISTORY_SIZE = 50

    data class ClipboardHistoryItem(
        val id: String,
        val content: String,
        val timestamp: Long,
        val isReceived: Boolean
    ) {
        fun toJSON(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("content", content)
                put("timestamp", timestamp)
                put("isReceived", isReceived)
            }
        }

        companion object {
            fun fromJSON(jo: JSONObject): ClipboardHistoryItem {
                return ClipboardHistoryItem(
                    id = jo.optString("id", UUID.randomUUID().toString()),
                    content = jo.optString("content", ""),
                    timestamp = jo.optLong("timestamp", System.currentTimeMillis()),
                    isReceived = jo.optBoolean("isReceived", true)
                )
            }
        }
    }

    @Synchronized
    fun addItem(context: Context, content: String, isReceived: Boolean) {
        if (content.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val history = getHistory(context).toMutableList()

        // Avoid adding duplicate consecutive items
        if (history.isNotEmpty() && history.first().content == content) {
            return
        }

        val newItem = ClipboardHistoryItem(
            id = UUID.randomUUID().toString(),
            content = content,
            timestamp = System.currentTimeMillis(),
            isReceived = isReceived
        )
        history.add(0, newItem)

        if (history.size > MAX_HISTORY_SIZE) {
            history.subList(MAX_HISTORY_SIZE, history.size).clear()
        }

        val ja = JSONArray()
        for (item in history) {
            ja.put(item.toJSON())
        }

        prefs.edit {
            putString(KEY_HISTORY, ja.toString())
        }
    }

    @Synchronized
    fun getHistory(context: Context): List<ClipboardHistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<ClipboardHistoryItem>()
        try {
            val ja = JSONArray(jsonStr)
            for (i in 0 until ja.length()) {
                list.add(ClipboardHistoryItem.fromJSON(ja.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_HISTORY)
        }
    }

    @Synchronized
    fun removeItem(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val history = getHistory(context).filter { it.id != id }
        val ja = JSONArray()
        for (item in history) {
            ja.put(item.toJSON())
        }
        prefs.edit {
            putString(KEY_HISTORY, ja.toString())
        }
    }
}
