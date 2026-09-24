/*
 * SPDX-FileCopyrightText: 2016 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.presenter

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

enum class TrapezoidDirection {
    NORTH, SOUTH, WEST, EAST
}

/**
 * Geometric Quad-Trapezoid Segment Shape with uniform separation channels
 * where the distance between adjacent segments is identical to the distance
 * between each segment and the central pointer button.
 */
class TrapezoidSegmentShape(
    private val direction: TrapezoidDirection,
    private val separationGapDp: Float = 20f,
    private val pointerRadiusDp: Float = 34f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val d = density.density
        // Outer square radius (half dimension with outer margin)
        val s = min(w, h) / 2f - (4f * d)

        // Exact distance between triangle and pointer button = separationGapDp
        val gap = separationGapDp * d
        val rIn = (pointerRadiusDp * d) + gap

        // Separation channel half-slit (total gap between adjacent buttons = separationGapDp)
        val slit = gap / 2f
        val slitDiag = slit * 1.41421356f

        // Corner rounding
        val crOuter = 14f * d
        val crInner = 6f * d

        // Angular offset where diagonal flank meets the inner circle
        val gapRad = asin((slit / rIn).coerceIn(0f, 0.95f))

        // Rotation angle for each cardinal direction
        val rotationAngle = when (direction) {
            TrapezoidDirection.NORTH -> 0f
            TrapezoidDirection.EAST -> (Math.PI.toFloat() / 2f)
            TrapezoidDirection.SOUTH -> Math.PI.toFloat()
            TrapezoidDirection.WEST -> (3f * Math.PI.toFloat() / 2f)
        }

        fun rotX(x: Float, y: Float): Float {
            return (x * cos(rotationAngle) - y * sin(rotationAngle)) + cx
        }

        fun rotY(x: Float, y: Float): Float {
            return (x * sin(rotationAngle) + y * cos(rotationAngle)) + cy
        }

        val path = Path().apply {
            // P0: Top-left outer edge after rounded corner
            val p0x = -s + slitDiag + crOuter
            val p0y = -s
            moveTo(rotX(p0x, p0y), rotY(p0x, p0y))

            // Line along outer top edge to top-right corner
            val p1x = s - slitDiag - crOuter
            val p1y = -s
            lineTo(rotX(p1x, p1y), rotY(p1x, p1y))

            // Rounded Top-Right Corner of the button
            val trCornerX = s - slitDiag
            val trCornerY = -s
            val diagStartX = trCornerX - (crOuter * 0.7071f)
            val diagStartY = trCornerY + (crOuter * 0.7071f)
            quadraticTo(
                rotX(trCornerX, trCornerY),
                rotY(trCornerX, trCornerY),
                rotX(diagStartX, diagStartY),
                rotY(diagStartX, diagStartY)
            )

            // Right diagonal flank down to inner circular ring
            val startRad = (315f * Math.PI.toFloat() / 180f) - gapRad
            val arcStartX = rIn * cos(startRad)
            val arcStartY = rIn * sin(startRad)

            // Inner right rounded transition into circular arc
            val preArcX = arcStartX + (crInner * 0.7071f)
            val preArcY = arcStartY - (crInner * 0.7071f)
            lineTo(rotX(preArcX, preArcY), rotY(preArcX, preArcY))
            quadraticTo(
                rotX(arcStartX, arcStartY),
                rotY(arcStartX, arcStartY),
                rotX(arcStartX, arcStartY),
                rotY(arcStartX, arcStartY)
            )

            // Smooth concentric circular ring arc facing the center pointer
            val endRad = (225f * Math.PI.toFloat() / 180f) + gapRad
            val steps = 24
            for (i in 1..steps) {
                val a = startRad + (endRad - startRad) * (i.toFloat() / steps.toFloat())
                val arcX = rIn * cos(a)
                val arcY = rIn * sin(a)
                lineTo(rotX(arcX, arcY), rotY(arcX, arcY))
            }

            // Inner left rounded transition from circular arc into left diagonal flank
            val arcEndX = rIn * cos(endRad)
            val arcEndY = rIn * sin(endRad)
            val postArcX = arcEndX - (crInner * 0.7071f)
            val postArcY = arcEndY - (crInner * 0.7071f)
            quadraticTo(
                rotX(arcEndX, arcEndY),
                rotY(arcEndX, arcEndY),
                rotX(postArcX, postArcY),
                rotY(postArcX, postArcY)
            )

            // Left diagonal flank up to top-left corner
            val tlCornerX = -s + slitDiag
            val tlCornerY = -s
            val diagLeftX = tlCornerX + (crOuter * 0.7071f)
            val diagLeftY = tlCornerY + (crOuter * 0.7071f)
            lineTo(rotX(diagLeftX, diagLeftY), rotY(diagLeftX, diagLeftY))

            // Rounded Top-Left Corner of the button
            quadraticTo(
                rotX(tlCornerX, tlCornerY),
                rotY(tlCornerX, tlCornerY),
                rotX(p0x, p0y),
                rotY(p0x, p0y)
            )

            close()
        }
        return Outline.Generic(path)
    }
}

class PresenterActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var plugin: PresenterPlugin
    private var mousePlugin: MousePadPlugin? = null
    private var sensorManager: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra("deviceId")
        val p = KdeConnect.getInstance().getDevicePlugin(deviceId, PresenterPlugin::class.java)
        if (p == null) {
            finish()
            return
        }
        plugin = p
        mousePlugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager?

        setContent {
            KdeTheme(this@PresenterActivity) {
                MainContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) {
            sensorManager = null
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val prefSensitivity = prefs.getInt(getString(R.string.pref_presenter_sensitivity), 50)
        // Highly refined damping factor (0.16f multiplier) for smooth, precision aiming
        val scale = (prefSensitivity / 100f) * 0.16f
        // Horizontal on screen (dx): yaw panning around Z-axis + wrist roll around Y-axis
        val radx = -(event.values[2] * 0.85f + event.values[1] * 0.15f) * scale
        // Vertical on screen (dy): pitch tilting up/down around X-axis (- values[0])
        val rady = -event.values[0] * scale
        plugin.sendPointer(radx, rady)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    plugin.sendRight()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    plugin.sendLeft()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun MainContent() {
        var isPointerActive by remember { mutableStateOf(false) }
        Scaffold(
            topBar = { PresenterAppBar() },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // D-PAD: 4 PROMINENTLY SEPARATED TRAPEZOID BUTTONS + WIDE CIRCULAR RING VOID + CENTER POINTER
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(230.dp)
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val unifiedBtnColor = MaterialTheme.colorScheme.primary
                            val unifiedIconTint = MaterialTheme.colorScheme.onPrimary

                            // 1. NORTH TRAPEZOID (UP)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(TrapezoidSegmentShape(TrapezoidDirection.NORTH))
                                    .background(unifiedBtnColor)
                                    .clickable { plugin.sendUp() },
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_upward_black_24dp),
                                    contentDescription = "North / Up",
                                    tint = unifiedIconTint,
                                    modifier = Modifier.padding(top = 18.dp).size(28.dp)
                                )
                            }

                            // 2. SOUTH TRAPEZOID (DOWN)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(TrapezoidSegmentShape(TrapezoidDirection.SOUTH))
                                    .background(unifiedBtnColor)
                                    .clickable { plugin.sendDown() },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_downward_black_24dp),
                                    contentDescription = "South / Down",
                                    tint = unifiedIconTint,
                                    modifier = Modifier.padding(bottom = 18.dp).size(28.dp)
                                )
                            }

                            // 3. WEST TRAPEZOID (LEFT / PREV)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(TrapezoidSegmentShape(TrapezoidDirection.WEST))
                                    .background(unifiedBtnColor)
                                    .clickable { plugin.sendLeft() },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back_black_24dp),
                                    contentDescription = "West / Left",
                                    tint = unifiedIconTint,
                                    modifier = Modifier.padding(start = 18.dp).size(28.dp)
                                )
                            }

                            // 4. EAST TRAPEZOID (RIGHT / NEXT)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(TrapezoidSegmentShape(TrapezoidDirection.EAST))
                                    .background(unifiedBtnColor)
                                    .clickable { plugin.sendRight() },
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_forward_black_24dp),
                                    contentDescription = "East / Right",
                                    tint = unifiedIconTint,
                                    modifier = Modifier.padding(end = 18.dp).size(28.dp)
                                )
                            }

                            // 5. CENTRAL REMOTE INPUT TOUCHPAD (IN CIRCULAR RING VOID)
                            Surface(
                                shape = CircleShape,
                                color = unifiedBtnColor,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .size(64.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { mousePlugin?.sendLeftClick() },
                                            onDoubleTap = { mousePlugin?.sendDoubleClick() },
                                            onLongPress = { mousePlugin?.sendRightClick() }
                                        )
                                    }
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            mousePlugin?.sendMouseDelta(dragAmount.x * 2.5f, dragAmount.y * 2.5f)
                                        }
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.touchpad_plugin_action_24dp),
                                        contentDescription = "Remote Input Pad",
                                        tint = unifiedIconTint,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Gyroscope Pointer Control Area (Hold / Touch to activate gyroscope laser)
                    val pointerCardBg by animateColorAsState(
                        targetValue = if (isPointerActive) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        label = "pointerCardBg"
                    )
                    val pointerBorderColor by animateColorAsState(
                        targetValue = if (isPointerActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        label = "pointerBorderColor"
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .pointerInteropFilter { event ->
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        isPointerActive = true
                                        sensorManager?.registerListener(
                                            this@PresenterActivity,
                                            sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                                            SensorManager.SENSOR_DELAY_GAME
                                        )
                                        true
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        isPointerActive = false
                                        sensorManager?.unregisterListener(this@PresenterActivity)
                                        plugin.stopPointer()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, pointerBorderColor),
                        colors = CardDefaults.cardColors(containerColor = pointerCardBg)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_presenter_24dp),
                                    contentDescription = "Gyroscope Pointer",
                                    tint = if (isPointerActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (isPointerActive) "Active (Tracking Gyroscope)" else "Hold to Aim Gyroscope Pointer",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPointerActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3 Mouse Buttons (Icon-only, no text labels, matching M3 secondary container)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.any { it.pressed }) {
                                                mousePlugin?.sendSingleHold()
                                                while (true) {
                                                    val nextEvent = awaitPointerEvent()
                                                    if (!nextEvent.changes.any { it.pressed }) {
                                                        mousePlugin?.sendSingleRelease()
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shadowElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back_black_24dp),
                                    contentDescription = "Left Click",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = { mousePlugin?.sendMiddleClick() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.touchpad_plugin_action_24dp),
                                contentDescription = "Middle Click",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            mousePlugin?.sendRightClick()
                                        }
                                    )
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shadowElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_forward_black_24dp),
                                    contentDescription = "Right Click",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Keystroke Input (Native Crash-Proof EditText with Material 3 Styling)
                    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f).toArgb()
                    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f).toArgb()
                    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()

                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        factory = { ctx ->
                            EditText(ctx).apply {
                                setSingleLine(true)
                                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                setTextColor(textColor)
                                setHintTextColor(hintColor)
                                textSize = 14f
                                setPadding(36, 0, 36, 0)
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    setColor(surfaceColor)
                                    setStroke(2, outlineColor)
                                    cornerRadius = 24f
                                }
                                var lastText = ""
                                addTextChangedListener(object : TextWatcher {
                                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                    override fun afterTextChanged(s: Editable?) {
                                        val cur = s?.toString() ?: ""
                                        if (cur.length > lastText.length) {
                                            val added = cur.substring(lastText.length)
                                            mousePlugin?.sendText(added) ?: plugin.sendReportText(added)
                                        } else if (cur.length < lastText.length) {
                                            val deletedCount = lastText.length - cur.length
                                            for (i in 0 until deletedCount) {
                                                mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_DEL)
                                            }
                                        }
                                        lastText = cur
                                    }
                                })
                                setOnEditorActionListener { v, actionId, event ->
                                    if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                                        if (mousePlugin != null) {
                                            mousePlugin?.sendSpecialKey(KeyEvent.KEYCODE_ENTER)
                                        } else {
                                            plugin.sendReportText("\n")
                                        }
                                        v.text = null
                                        lastText = ""
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @Preview
    @Composable
    private fun PresenterAppBar() {
        var dropdownShownState by remember { mutableStateOf(false) }

        KdeTopAppBar(
            title = "Presentation",
            navIconOnClick = { onBackPressedDispatcher.onBackPressed() },
            navIconDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description),
            actions = {
                IconButton(onClick = { dropdownShownState = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.extra_options))
                }
                DropdownMenu(expanded = dropdownShownState, onDismissRequest = { dropdownShownState = false }) {
                    DropdownMenuItem(
                        onClick = { plugin.sendFullscreen() },
                        text = { Text(stringResource(R.string.presenter_fullscreen)) },
                    )
                    DropdownMenuItem(
                        onClick = { plugin.sendEsc() },
                        text = { Text(stringResource(R.string.presenter_exit)) },
                    )
                }
            }
        )
    }
}
