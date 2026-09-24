/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins.mousepad

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.kde.kdeconnect.NetworkPacket

internal class KeyInputConnection(
    private val view: KeyListenerView,
    fullEditor: Boolean
) : BaseInputConnection(view, false) {

    private val mEditable: Editable = SpannableStringBuilder()

    override fun getEditable(): Editable {
        return mEditable
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return false
        try {
            view.sendChars(text)
        } catch (_: Exception) {}
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        try {
            for (i in 0 until beforeLength) {
                val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
                    set("specialKey", KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DEL, 1))
                }
                view.sendKeyPressPacket(np)
            }
            for (i in 0 until afterLength) {
                val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
                    set("specialKey", KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_FORWARD_DEL, 13))
                }
                view.sendKeyPressPacket(np)
            }
        } catch (_: Exception) {}
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        return true
    }

    override fun finishComposingText(): Boolean {
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        return ""
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        return ""
    }

    override fun getSelectedText(flags: Int): CharSequence {
        return ""
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        return 0
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        return ExtractedText().apply {
            text = ""
            startOffset = 0
            selectionStart = 0
            selectionEnd = 0
        }
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        try {
            if (event.action == KeyEvent.ACTION_UP) {
                view.onKeyUp(event.keyCode, event)
            } else if (event.action == KeyEvent.ACTION_DOWN) {
                view.onKeyDown(event.keyCode, event)
            }
        } catch (_: Exception) {}
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        try {
            val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
                set("specialKey", KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ENTER, 12))
            }
            view.sendKeyPressPacket(np)
        } catch (_: Exception) {}
        return true
    }
}
