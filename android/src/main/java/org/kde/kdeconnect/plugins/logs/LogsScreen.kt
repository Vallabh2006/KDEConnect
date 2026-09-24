/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.logging.KdeLog
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<KdeLog.LogEntry>,
    onBackPressedDispatcher: OnBackPressedDispatcher?,
    onClearLogs: () -> Unit,
    onShareLogs: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<KdeLog.LogTag?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val filteredLogs = remember(logs, searchQuery, selectedTag) {
        logs.filter { entry ->
            val matchesTag = selectedTag == null || entry.tag == selectedTag
            val matchesSearch = searchQuery.isBlank() ||
                    entry.title.contains(searchQuery, ignoreCase = true) ||
                    entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.tag.displayName.contains(searchQuery, ignoreCase = true)
            matchesTag && matchesSearch
        }
    }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            KdeTopAppBar(
                title = "Live Activity Logs",
                subTitle = "${filteredLogs.size} events",
                navIconOnClick = { onBackPressedDispatcher?.onBackPressed() },
                actions = {
                    IconButton(
                        onClick = { autoScroll = !autoScroll }
                    ) {
                        Icon(
                            imageVector = if (autoScroll) Icons.Default.Lock else Icons.Default.PlayArrow,
                            contentDescription = if (autoScroll) "Auto-scroll ON" else "Auto-scroll OFF",
                            tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onShareLogs) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export / Share Logs"
                        )
                    }
                    IconButton(onClick = onClearLogs) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Logs"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search logs...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                            label = { Text("ALL") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        KdeLog.LogTag.values().forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { selectedTag = tag },
                                label = { Text(tag.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) "No logs recorded yet" else "No matching logs found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { logEntry ->
                        LogCard(entry = logEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(
    entry: KdeLog.LogEntry
) {
    var isExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val levelColor = when (entry.level) {
        KdeLog.LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        KdeLog.LogLevel.INFO -> MaterialTheme.colorScheme.primary
        KdeLog.LogLevel.WARN -> Color(0xFFE65100)
        KdeLog.LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = when (entry.level) {
                KdeLog.LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                isExpanded = !isExpanded
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(levelColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = entry.tag.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = entry.timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val displayMessage = remember(entry.message) { formatCleanLogMessage(entry.message) }
            if (displayMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                    overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            val textToCopy = "[${entry.timeFormatted}] [${entry.tag.displayName}] ${entry.title}\n${entry.message}"
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("KDE Log", textToCopy))
                            Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Copy Log",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Copy",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun formatCleanLogMessage(rawMessage: String): String {
    if (rawMessage.isBlank()) return ""
    val trimmed = rawMessage.trim()

    val jsonCandidate = when {
        trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
        trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
        else -> null
    }

    if (jsonCandidate != null) {
        try {
            val json = JSONObject(jsonCandidate)
            val body = json.optJSONObject("body") ?: json
            val pairs = mutableListOf<String>()

            if (body.has("signalStrengths")) {
                val sig = body.optJSONObject("signalStrengths")
                if (sig != null && sig.length() > 0) {
                    val sigList = mutableListOf<String>()
                    val it = sig.keys()
                    while (it.hasNext()) {
                        val k = it.next().toString()
                        sigList.add("SIM $k: ${sig.optInt(k)}/4 bars")
                    }
                    return "Signal Strength: " + sigList.joinToString(", ")
                }
            }
            if (body.has("action")) {
                pairs.add("Action: " + body.optString("action"))
            }
            if (body.has("content")) {
                val clip = body.optString("content").replace("\n", " ")
                val snippet = if (clip.length > 70) clip.take(70) + "..." else clip
                return "Clip: \"$snippet\""
            }
            if (body.has("filename")) {
                pairs.add("File: " + body.optString("filename"))
            }
            if (body.has("currentCharge")) {
                pairs.add("Battery: " + body.optInt("currentCharge") + "%")
            }
            if (body.has("isCharging")) {
                pairs.add("Charging: " + (if (body.optBoolean("isCharging")) "Yes" else "No"))
            }
            if (body.has("command")) {
                pairs.add("Command: " + body.optString("command"))
            }

            val keys = body.keys()
            while (keys.hasNext()) {
                val k = keys.next().toString()
                if (k != "id" && k != "type" && k != "body" && k != "action" && k != "filename" && k != "currentCharge" && k != "isCharging" && k != "content" && k != "signalStrengths" && k != "command") {
                    val v = body.opt(k)
                    if (v != null && v !is JSONObject && v !is JSONArray) {
                        pairs.add("$k: $v")
                    }
                }
            }

            if (pairs.isNotEmpty()) {
                return pairs.joinToString(" • ")
            }
        } catch (_: Exception) {}
    }

    return rawMessage
}
