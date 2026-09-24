/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.terminal

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import android.widget.Toast
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import org.kde.kdeconnect.ui.compose.KdeTopAppBar

private const val PREFS_NAME = "terminal_quick_commands_prefs"
private const val KEY_COMMANDS_LIST = "terminal_active_commands_list"
private val INITIAL_DEFAULT_COMMANDS = listOf("ls -la", "htop", "clear", "uname -a", "df -h", "free -m", "pwd", "git status")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    deviceName: String,
    isSshConnected: Boolean,
    terminalOutput: String,
    onBackPressedDispatcher: OnBackPressedDispatcher?,
    onSendCommand: (String) -> Unit,
    onSendInterrupt: () -> Unit,
    onSendCtrlZ: () -> Unit,
    onSendTab: () -> Unit,
    onSendEsc: () -> Unit,
    onClearTerminal: () -> Unit,
    onConnectSsh: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val activeCommands = remember {
        val saved = prefs.getString(KEY_COMMANDS_LIST, null)
        val list = if (saved != null) {
            saved.split("\n").filter { it.isNotEmpty() }
        } else {
            INITIAL_DEFAULT_COMMANDS
        }
        mutableStateListOf<String>().apply { addAll(list) }
    }

    fun saveCommands() {
        prefs.edit { putString(KEY_COMMANDS_LIST, activeCommands.joinToString("\n")) }
    }

    var showManageDialog by remember { mutableStateOf(false) }
    var newCommandInput by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(terminalOutput) {
        if (verticalScrollState.maxValue > 0) {
            try {
                verticalScrollState.scrollTo(verticalScrollState.maxValue)
            } catch (_: Throwable) {}
        }
    }

    var modCtrl by remember { mutableStateOf(false) }
    var modAlt by remember { mutableStateOf(false) }
    var modShift by remember { mutableStateOf(false) }
    var modSuper by remember { mutableStateOf(false) }
    var selectedKeyInput by remember { mutableStateOf("") }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = {
                showManageDialog = false
                newCommandInput = ""
                selectedKeyInput = ""
                modCtrl = false
                modAlt = false
                modShift = false
                modSuper = false
            },
            title = {
                Text(
                    text = "Manage Preset Shortcuts & Commands",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Build a Shortcut (up to 4 modifiers + any key):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // 4 Modifiers Row: Ctrl, Alt, Shift, Super (Windows Key)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple("Ctrl", modCtrl) { modCtrl = !modCtrl },
                            Triple("Alt", modAlt) { modAlt = !modAlt },
                            Triple("Shift", modShift) { modShift = !modShift },
                            Triple("Super", modSuper) { modSuper = !modSuper }
                        ).forEach { (name, isSelected, onClick) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onClick() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Key Input / Command Input Field
                    OutlinedTextField(
                        value = newCommandInput,
                        onValueChange = { newCommandInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Key or Command (e.g. Z, Tab, Esc, git status)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Shortcut Chips
                    Text(
                        text = "Quick Presets:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Ctrl+Z", "Ctrl+L", "Tab", "Esc", "Ctrl+D", "Super+T", "fastfetch").forEach { qk ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (!activeCommands.contains(qk)) {
                                            activeCommands.add(qk)
                                            saveCommands()
                                        }
                                    }
                            ) {
                                Text(
                                    text = "+ $qk",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Active Presets (${activeCommands.size}) • At least 1 required:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        activeCommands.forEach { cmd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cmd,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        if (activeCommands.size > 1) {
                                            activeCommands.remove(cmd)
                                            saveCommands()
                                        } else {
                                            Toast.makeText(context, "At least 1 preset shortcut must be saved", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove command",
                                        tint = if (activeCommands.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        val mods = mutableListOf<String>()
                        if (modCtrl) mods.add("Ctrl")
                        if (modAlt) mods.add("Alt")
                        if (modShift) mods.add("Shift")
                        if (modSuper) mods.add("Super")

                        val rawKey = newCommandInput.trim()
                        val finalShortcut = if (mods.isNotEmpty() && rawKey.isNotEmpty()) {
                            mods.joinToString("+") + "+" + rawKey
                        } else if (rawKey.isNotEmpty()) {
                            rawKey
                        } else if (mods.isNotEmpty()) {
                            mods.joinToString("+")
                        } else {
                            ""
                        }

                        if (finalShortcut.isNotEmpty() && !activeCommands.contains(finalShortcut)) {
                            activeCommands.add(finalShortcut)
                            saveCommands()
                        }
                        newCommandInput = ""
                        modCtrl = false
                        modAlt = false
                        modShift = false
                        modSuper = false
                        showManageDialog = false
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("Add & Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManageDialog = false
                    newCommandInput = ""
                    modCtrl = false
                    modAlt = false
                    modShift = false
                    modSuper = false
                }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            KdeTopAppBar(
                title = "Live Terminal",
                subTitle = deviceName,
                navIconOnClick = { onBackPressedDispatcher?.onBackPressed() },
                actions = {
                    IconButton(onClick = {
                        if (terminalOutput.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(terminalOutput))
                            Toast.makeText(context, "Terminal output copied to clipboard", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Terminal output is empty", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Copy Output")
                    }
                    IconButton(onClick = onConnectSsh) {
                        Icon(Icons.Default.Refresh, contentDescription = "SSH Config / Reconnect")
                    }
                    IconButton(onClick = onClearTerminal) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear Screen")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Status Header Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onConnectSsh)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSshConnected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = "TERMINAL",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSshConnected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = if (isSshConnected) "SSH Active" else "KDE Socket (Tap for SSH)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    OutlinedButton(
                        onClick = onClearTerminal,
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Command Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            "Enter command...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send,
                        autoCorrectEnabled = false
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotEmpty()) {
                                onSendCommand(inputText)
                                inputText = ""
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = {
                        if (inputText.isNotEmpty()) {
                            onSendCommand(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Command")
                }
            }

            // Quick Keys & Preset Shortcuts/Commands Toolbar
            fun executePreset(cmd: String) {
                val upper = cmd.trim().uppercase()
                when (upper) {
                    "CTRL+C" -> onSendInterrupt()
                    "CTRL+Z" -> onSendCtrlZ()
                    "TAB" -> onSendTab()
                    "ESC", "ESCAPE" -> onSendEsc()
                    "CTRL+L" -> onSendCommand("\u000c")
                    "CTRL+D" -> onSendCommand("\u0004")
                    "CTRL+A" -> onSendCommand("\u0001")
                    "CTRL+E" -> onSendCommand("\u0005")
                    "CTRL+U" -> onSendCommand("\u0015")
                    "CTRL+W" -> onSendCommand("\u0017")
                    else -> {
                        if (cmd.startsWith("Ctrl+", ignoreCase = true) && cmd.length == 6) {
                            val letter = cmd.last().uppercaseChar()
                            if (letter in 'A'..'Z') {
                                val code = (letter.code - 'A'.code + 1).toChar()
                                onSendCommand(code.toString())
                                return
                            }
                        }
                        onSendCommand(cmd)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onSendInterrupt,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text("Ctrl+C", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                activeCommands.forEach { cmd ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    executePreset(cmd)
                                },
                                onLongClick = {
                                    showManageDialog = true
                                }
                            )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = cmd,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // "+ Add / Edit" Button
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showManageDialog = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Manage commands",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Edit Presets",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Terminal Output Canvas Card: Native selectable TextView (100% crash-proof on keyboard open)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState)
                        .padding(12.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.wrapContentSize(),
                        factory = { ctx ->
                            TextView(ctx).apply {
                                setTextIsSelectable(true)
                                typeface = Typeface.MONOSPACE
                                textSize = 11.5f
                                setTextColor(AndroidColor.WHITE)
                                setLineSpacing(0f, 1.25f)
                                setHorizontallyScrolling(true)
                            }
                        },
                        update = { tv ->
                            val target = terminalOutput.ifEmpty { "[Terminal ready. Enter command above...]" }
                            if (tv.text.toString() != target) {
                                tv.text = target
                            }
                        }
                    )
                }
            }
        }
    }
}
