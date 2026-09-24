/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    fun removeObserver(observer: ClipboardObserver) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    fun onClipboardChanged() {
        try {
            if (!this::cm.isInitialized) return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val item = clip.getItemAt(0)
            val rawContent = item.coerceToText(context)?.toString() ?: ""
            val contentType = detectContentType(clip)

            if (!isValidClipboardText(rawContent)) {
                return
            }

            val content = rawContent.trim()
            if (content == currentContent && contentType == currentContentType) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = content
            currentContentType = contentType

            ClipboardHistoryManager.addItem(context, content, isReceived = false)

            val obsCopy = synchronized(observers) { observers.toList() }
            for (observer in obsCopy) {
                observer.clipboardChanged(content, contentType)
            }
        } catch (_: Exception) {
            // Probably clipboard was not text or permission restricted
        }
    }

    @Suppress("deprecation")
    fun setText(text: String?) {
        if (!isValidClipboardText(text)) {
            return
        }
        val safeText = text!!.trim()
        ClipboardHistoryManager.addItem(context, safeText, isReceived = true)
        if (this::cm.isInitialized) {
            updateTimestamp = System.currentTimeMillis()
            currentContent = safeText
            currentContentType = ClipboardContentType.Text
            try {
                cm.text = safeText
            } catch (_: Exception) {}
        }
        val obsCopy = synchronized(observers) { observers.toList() }
        for (observer in obsCopy) {
            observer.clipboardChanged(safeText, ClipboardContentType.Text)
        }
    }

    /**
     * Actively read the system clipboard and trigger observers if it changed.
     * Call this from onResume() to detect changes made while the app was in background
     * (Android 10+ restricts background clipboard access).
     */
    fun refreshFromSystem() {
        if (this::cm.isInitialized) {
            onClipboardChanged()
        }
    }

    companion object {
        private var _instance: ClipboardListener? = null

        @JvmStatic
        fun instance(context: Context): ClipboardListener {
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        @JvmStatic
        fun isValidClipboardText(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val trimmed = text.trim()
            if (trimmed.equals("null", ignoreCase = true)) return false
            if (trimmed.startsWith("file://", ignoreCase = true)) return false
            if (trimmed.startsWith("content://", ignoreCase = true)) return false
            return true
        }

        @JvmStatic
        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
