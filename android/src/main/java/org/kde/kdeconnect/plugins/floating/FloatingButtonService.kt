/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.floating

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.kde.kdeconnect.plugins.clipboard.ClipboardHistoryManager
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener
import org.kde.kdeconnect.plugins.systemvolume.SystemVolumePlugin
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.clipboard.ClipboardManagerActivity
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.mousepad.MousePadActivity
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.mpris.MprisActivity
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect.plugins.presenter.PresenterActivity
import org.kde.kdeconnect.plugins.runcommand.RunCommandActivity
import org.kde.kdeconnect.plugins.screenmirror.ScreenMirrorActivity
import org.kde.kdeconnect.plugins.share.RemoteFileBrowserActivity
import org.kde.kdeconnect.plugins.taskmanager.TaskManagerActivity
import org.kde.kdeconnect.plugins.terminal.TerminalActivity
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlin.math.hypot

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var dismissView: View? = null
    private var menuOverlayView: View? = null
    private var mouseOverlayView: View? = null
    private var mediaOverlayView: View? = null
    private var mediaUpdateRunnable: Runnable? = null
    private var clipboardOverlayView: View? = null

    private lateinit var clipboardOverlayParams: WindowManager.LayoutParams
    private var selectedPlayer: String = ""

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var dismissParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams
    private lateinit var mouseOverlayParams: WindowManager.LayoutParams
    private lateinit var mediaOverlayParams: WindowManager.LayoutParams
    private var isVolumeSeeking = false
    private var isMediaSeeking = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var isOverDismissZone = false
    private var isMenuShowing = false
    private var isIdle = false

    private var isPhoneMicStreaming = false
    private var phoneMicRecord: AudioRecord? = null
    private var phoneMicThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { enterIdleState() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()
        isRunning = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }

        try {
            val themedContext = ContextThemeWrapper(this, R.style.KdeConnectTheme)
            val inflater = LayoutInflater.from(themedContext)

            setupBubbleView(inflater)
            setupDismissView(inflater)
            setupMenuView(inflater)

            scheduleIdleTimer()
            mainHandler.post(clipboardPollRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing floating views", e)
            Toast.makeText(this, "Error creating floating button: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val restartServiceIntent = Intent(applicationContext, FloatingButtonService::class.java).apply {
                setPackage(packageName)
                action = ACTION_START
            }
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext, 1, restartServiceIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmService?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 500,
                restartServicePendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service persistence alarm", e)
        }
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        stopPhoneMicStreaming()
        removeViews()
        super.onDestroy()
    }

    
    data class InsetsRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun getSystemInsets(): InsetsRect {
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                )
                left = insets.left
                right = insets.right
                top = insets.top
                bottom = insets.bottom
            } catch (_: Exception) {}
        } else {
            val res = resources
            val isLandscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val navBarId = res.getIdentifier("navigation_bar_height", "dimen", "android")
            val navBarHeight = if (navBarId > 0) res.getDimensionPixelSize(navBarId) else 0
            if (isLandscape) {
                val navBarWidthId = res.getIdentifier("navigation_bar_width", "dimen", "android")
                val navBarWidth = if (navBarWidthId > 0) res.getDimensionPixelSize(navBarWidthId) else navBarHeight
                right = navBarWidth
            } else {
                bottom = navBarHeight
            }
            val statusBarId = res.getIdentifier("status_bar_height", "dimen", "android")
            top = if (statusBarId > 0) res.getDimensionPixelSize(statusBarId) else 0
        }
        return InsetsRect(left, top, right, bottom)
    }

    private fun updateBubbleVisualState() {
        val device = getActiveDevice()
        val isConnected = device != null && device.isReachable
        val card = bubbleView?.findViewById<MaterialCardView>(R.id.floating_bubble_card)
        val icon = bubbleView?.findViewById<ImageView>(R.id.floating_bubble_icon)

        if (isConnected) {
            card?.strokeColor = Color.parseColor("#8038bdf8")
            if (!isIdle) {
                card?.alpha = 1.0f
            }
            icon?.clearColorFilter()
        } else {
            card?.strokeColor = Color.parseColor("#64748b")
            if (!isIdle) {
                card?.alpha = 0.65f
            }
            val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            icon?.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
    }

    private val clipboardPollRunnable = object : Runnable {
        override fun run() {
            try {
                ClipboardListener.instance(applicationContext).refreshFromSystem()
                updateBubbleVisualState()
            } catch (ignored: Exception) {}
            if (isRunning) {
                mainHandler.postDelayed(this, 2000)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        val insets = getSystemInsets()
        val density = resources.displayMetrics.density
        val windowSize = (96 * density).toInt()
        val minY = insets.top
        val maxY = (screenHeight - insets.bottom - windowSize).coerceAtLeast(minY)
        bubbleParams.y = bubbleParams.y.coerceIn(minY, maxY)
        snapBubbleToEdge()
        updateBubbleVisualState()
        if (isMenuShowing) {
            hideMenu()
        }
    }

    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, NotificationHelper.Channels.PERSISTENT)
            .setContentTitle(getString(R.string.floating_action_button))
            .setContentText(getString(R.string.floating_action_button_desc))
            .setSmallIcon(R.drawable.ic_floating_button)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.close), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun scheduleIdleTimer() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, 3500L)
    }

    private fun enterIdleState() {
        if (isMenuShowing || isIdle || bubbleView == null) return
        isIdle = true
        val density = resources.displayMetrics.density
        val card = bubbleView?.findViewById<View>(R.id.floating_bubble_card) ?: return

        val isLeft = (bubbleParams.x + (48 * density)) < screenWidth / 2f
        val targetTranslationX = if (isLeft) -32f * density else 32f * density

        card.animate()
            .translationX(targetTranslationX)
            .alpha(0.38f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun exitIdleState() {
        mainHandler.removeCallbacks(idleRunnable)
        if (!isIdle) return
        isIdle = false
        val card = bubbleView?.findViewById<View>(R.id.floating_bubble_card) ?: return
        card.animate()
            .translationX(0f)
            .alpha(1.0f)
            .scaleX(1.10f)
            .scaleY(1.10f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleView(inflater: LayoutInflater) {
        bubbleView = inflater.inflate(R.layout.floating_bubble_layout, null)

        val density = resources.displayMetrics.density
        val windowSize = (96 * density).toInt()

        bubbleParams = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - windowSize + (10 * density).toInt()
            y = (screenHeight * 0.4).toInt()
        }

        val card = bubbleView?.findViewById<MaterialCardView>(R.id.floating_bubble_card)

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false
            private var touchStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        mainHandler.removeCallbacks(idleRunnable)
                        exitIdleState()

                        card?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.alpha(1.0f)?.setDuration(120)?.start()
                        bubbleView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isOverDismissZone = false
                        touchStartTime = System.currentTimeMillis()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > (8 * density)) {
                            isDragging = true
                            showDismissTarget(true)
                        }

                        if (isDragging) {
                            val insets = getSystemInsets()
                            val minDragX = insets.left - (20 * density).toInt()
                            val maxDragX = screenWidth - insets.right - windowSize + (20 * density).toInt()
                            val minDragY = insets.top - (10 * density).toInt()
                            val maxDragY = (screenHeight - insets.bottom - windowSize + (10 * density).toInt()).coerceAtLeast(minDragY)

                            bubbleParams.x = (initialX + dx).coerceIn(minDragX, maxDragX)
                            bubbleParams.y = (initialY + dy).coerceIn(minDragY, maxDragY)
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams)
                            } catch (ignored: Exception) {
                            }

                            checkDismissTargetCollision(event.rawX, event.rawY)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        card?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(160)?.start()
                        showDismissTarget(false)

                        if (isDragging) {
                            if (isOverDismissZone) {
                                bubbleView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                Toast.makeText(this@FloatingButtonService, R.string.floating_button_disabled, Toast.LENGTH_SHORT).show()
                                stopSelf()
                            } else {
                                snapBubbleToEdge()
                            }
                        } else {
                            val duration = System.currentTimeMillis() - touchStartTime
                            if (duration < 380) {
                                toggleMenu()
                            } else {
                                scheduleIdleTimer()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubbleView to WindowManager", e)
        }
    }

    private fun setupDismissView(inflater: LayoutInflater) {
        dismissView = inflater.inflate(R.layout.floating_dismiss_layout, null)

        val density = resources.displayMetrics.density
        val dismissHeight = (130 * density).toInt()

        dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dismissHeight,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
        }

        try {
            windowManager.addView(dismissView, dismissParams)
            dismissView?.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add dismissView to WindowManager", e)
        }
    }

    private fun setupMenuView(inflater: LayoutInflater) {
        menuOverlayView = inflater.inflate(R.layout.floating_menu_vertical_layout, null)

        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        menuOverlayView?.findViewById<View>(R.id.floating_menu_overlay_root)?.setOnClickListener {
            hideMenu()
        }
    }

    private fun showDismissTarget(show: Boolean) {
        dismissView?.let { dv ->
            if (show) {
                dv.visibility = View.VISIBLE
                dv.alpha = 0f
                dv.animate().alpha(1f).setDuration(180).start()
            } else {
                dv.animate().alpha(0f).setDuration(140).withEndAction {
                    dv.visibility = View.GONE
                }.start()
            }
        }
    }

    private fun checkDismissTargetCollision(rawX: Float, rawY: Float) {
        val density = resources.displayMetrics.density
        val dismissZoneHeight = 150 * density
        val dismissCenterX = screenWidth / 2f
        val dismissCenterY = screenHeight - (65 * density)

        val dist = hypot((rawX - dismissCenterX).toDouble(), (rawY - dismissCenterY).toDouble())
        val inZone = rawY > (screenHeight - dismissZoneHeight) && dist < (80 * density)

        if (inZone != isOverDismissZone) {
            isOverDismissZone = inZone
            val card = dismissView?.findViewById<MaterialCardView>(R.id.floating_dismiss_circle)
            if (inZone) {
                bubbleView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                card?.setCardBackgroundColor(Color.parseColor("#E53935"))
                card?.animate()?.scaleX(1.25f)?.scaleY(1.25f)?.setDuration(120)?.start()
            } else {
                card?.setCardBackgroundColor(Color.parseColor("#80000000"))
                card?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
            }
        }
    }

    private fun snapBubbleToEdge() {
        val density = resources.displayMetrics.density
        val windowSize = (96 * density).toInt()
        val insets = getSystemInsets()

        val minX = insets.left - (12 * density).toInt()
        val maxX = screenWidth - insets.right - windowSize + (12 * density).toInt()

        val targetX = if (bubbleParams.x + windowSize / 2 < screenWidth / 2) {
            minX
        } else {
            maxX
        }

        val minY = insets.top
        val maxY = (screenHeight - insets.bottom - windowSize).coerceAtLeast(minY)
        bubbleParams.y = bubbleParams.y.coerceIn(minY, maxY)

        val startX = bubbleParams.x
        val animator = ValueAnimator.ofInt(startX, targetX)
        animator.duration = 240
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { va ->
            bubbleParams.x = va.animatedValue as Int
            try {
                windowManager.updateViewLayout(bubbleView, bubbleParams)
            } catch (ignored: Exception) {
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                scheduleIdleTimer()
            }
        })
        animator.start()
    }

    private fun toggleMenu() {
        if (isMenuShowing) {
            hideMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        if (isMenuShowing || menuOverlayView == null) return
        mainHandler.removeCallbacks(idleRunnable)
        exitIdleState()

        val activeDevice = getActiveDevice()
        populateVerticalActionStack(activeDevice)

        try {
            windowManager.addView(menuOverlayView, menuParams)
            isMenuShowing = true

            val column = menuOverlayView?.findViewById<LinearLayout>(R.id.floating_actions_column) ?: return
            val count = column.childCount
            for (i in 0 until count) {
                val child = column.getChildAt(i)
                child.scaleX = 0f
                child.scaleY = 0f
                child.alpha = 0f
                child.translationY = -12f * resources.displayMetrics.density

                child.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((i * 28L))
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add menuOverlayView to WindowManager", e)
        }
    }

    private fun hideMenu() {
        if (!isMenuShowing || menuOverlayView == null) return
        val column = menuOverlayView?.findViewById<LinearLayout>(R.id.floating_actions_column)
        val count = column?.childCount ?: 0

        if (count > 0) {
            for (i in 0 until count) {
                val child = column?.getChildAt(i)
                child?.animate()
                    ?.scaleX(0f)
                    ?.scaleY(0f)
                    ?.alpha(0f)
                    ?.setStartDelay(((count - 1 - i) * 18L))
                    ?.setDuration(120)
                    ?.start()
            }
            mainHandler.postDelayed({
                try {
                    windowManager.removeView(menuOverlayView)
                } catch (ignored: Exception) {
                }
                isMenuShowing = false
                scheduleIdleTimer()
            }, (count * 18L + 120L))
        } else {
            try {
                windowManager.removeView(menuOverlayView)
            } catch (ignored: Exception) {
            }
            isMenuShowing = false
            scheduleIdleTimer()
        }
    }

    @SuppressLint("InflateParams")
    private fun populateVerticalActionStack(device: Device?) {
        val column = menuOverlayView?.findViewById<LinearLayout>(R.id.floating_actions_column) ?: return
        val scroll = menuOverlayView?.findViewById<View>(R.id.floating_menu_scroll) ?: return
        column.removeAllViews()

        val density = resources.displayMetrics.density
        val isLeft = (bubbleParams.x + (48 * density)) < screenWidth / 2f

        val scrollParams = scroll.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        scrollParams.gravity = if (isLeft) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        val marginSide = (18 * density).toInt()
        if (isLeft) {
            scrollParams.setMargins(marginSide, 0, 0, 0)
        } else {
            scrollParams.setMargins(0, 0, marginSide, 0)
        }
        scroll.layoutParams = scrollParams

        val enabledActions = FloatingButtonHelper.getEnabledActions(this)
        val themedContext = ContextThemeWrapper(this, R.style.KdeConnectTheme)
        val inflater = LayoutInflater.from(themedContext)

        val isDeviceReachable = device != null && device.isReachable

        for (action in enabledActions) {
            val itemView = inflater.inflate(R.layout.item_floating_action_vertical, column, false)
            val cardView = itemView.findViewById<MaterialCardView>(R.id.action_icon_card)
            val iconView = itemView.findViewById<ImageView>(R.id.action_icon)

            iconView.setImageResource(action.iconRes)

            if (!isDeviceReachable) {
                cardView.setCardBackgroundColor(Color.parseColor("#334155"))
                cardView.strokeColor = Color.parseColor("#475569")
                cardView.strokeWidth = (1 * density).toInt()
                cardView.alpha = 0.6f
                val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                iconView.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)

                itemView.setOnClickListener {
                    hideMenu()
                    Toast.makeText(this@FloatingButtonService, "Device is disconnected or unreachable", Toast.LENGTH_SHORT).show()
                }
            } else {
                cardView.strokeWidth = 0
                cardView.alpha = 1.0f
                iconView.clearColorFilter()

                if (action.id == FloatingActionItem.ACTION_PHONE_MIC && isPhoneMicStreaming) {
                    cardView.setCardBackgroundColor(Color.parseColor("#EF4444"))
                } else {
                    try {
                        cardView.setCardBackgroundColor(Color.parseColor(action.colorHex))
                    } catch (ignored: Exception) {
                    }
                }

                itemView.setOnClickListener {
                    hideMenu()
                    performAction(action.id, device)
                }
            }

            column.addView(itemView)
        }
    }

    private fun performAction(actionId: String, device: Device?) {
        val host = getActiveDeviceHost(device)

        when (actionId) {
            FloatingActionItem.ACTION_MEDIA_CONTROL, FloatingActionItem.ACTION_VOLUME -> {
                showMediaOverlay(device)
            }

            FloatingActionItem.ACTION_PHONE_MIC -> {
                togglePhoneMicStreaming(host)
            }

            FloatingActionItem.ACTION_REMOTE_POINTER -> {
                showRemoteMouseOverlay(device)
            }

            FloatingActionItem.ACTION_CLIPBOARD, FloatingActionItem.ACTION_SEND_CLIP, FloatingActionItem.ACTION_RECEIVE_CLIP -> {
                showClipboardOverlay(device)
            }

            FloatingActionItem.ACTION_TAKE_SEND_SS -> {
                Thread {
                    var savedSuccess = false
                    var fallbackExecuted = false
                    try {
                        val url = URL("http://$host:59001/capture_screenshot")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 4000
                            readTimeout = 8000
                        }
                        if (conn.responseCode == 200) {
                            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            val fileName = "PC_Screenshot_$timeStamp.png"

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KDEConnect")
                                }
                                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                if (uri != null) {
                                    contentResolver.openOutputStream(uri)?.use { out ->
                                        conn.inputStream.copyTo(out)
                                    }
                                    savedSuccess = true
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "KDEConnect")
                                if (!dir.exists()) dir.mkdirs()
                                val file = File(dir, fileName)
                                FileOutputStream(file).use { out ->
                                    conn.inputStream.copyTo(out)
                                }
                                savedSuccess = true
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to download screenshot", e)
                    }

                    if (!savedSuccess) {
                        try {
                            val cmd = "bash -c 'mkdir -p ~/Pictures/Screenshots; T=~/Pictures/Screenshots/Screenshot_\$(date +%Y%m%d_%H%M%S).png; (spectacle -b -n -o \$T 2>/dev/null || grim \$T 2>/dev/null || import -window root \$T 2>/dev/null); DEV=\$(kdeconnect-cli -a --id-only 2>/dev/null | head -n1); [ -n \"\$DEV\" ] && kdeconnect-cli -d \"\$DEV\" --share \$T 2>/dev/null'"
                            val enc = URLEncoder.encode(cmd, "UTF-8")
                            val url = URL("http://$host:59001/task_manager_action?cmd=$enc")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            if (conn.responseCode == 200) {
                                fallbackExecuted = true
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Fallback screenshot command error", e)
                        }
                    }

                    mainHandler.post {
                        if (savedSuccess) {
                            Toast.makeText(this@FloatingButtonService, "PC Screenshot saved to phone gallery", Toast.LENGTH_SHORT).show()
                        } else if (fallbackExecuted) {
                            Toast.makeText(this@FloatingButtonService, "Screenshot captured on PC", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@FloatingButtonService, "Failed to capture PC screenshot", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }

            FloatingActionItem.ACTION_LOCK_PC -> {
                Thread {
                    try {
                        val cmd = "loginctl lock-session 2>/dev/null || qdbus org.freedesktop.ScreenSaver /ScreenSaver Lock 2>/dev/null || true"
                        val enc = URLEncoder.encode(cmd, "UTF-8")
                        val url = URL("http://$host:59001/task_manager_action?cmd=$enc")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        mainHandler.post {
                            Toast.makeText(this@FloatingButtonService, "PC Locked", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            Toast.makeText(this@FloatingButtonService, "Sent lock command", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }

            FloatingActionItem.ACTION_SCREENMIRROR -> {
                val intent = Intent(this, ScreenMirrorActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_MOUSEPAD -> {
                val intent = Intent(this, MousePadActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_TASKMANAGER -> {
                val intent = Intent(this, TaskManagerActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_TERMINAL -> {
                val intent = Intent(this, TerminalActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_CLIPBOARD -> {
                val intent = Intent(this, ClipboardManagerActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_MPRIS -> {
                val intent = Intent(this, MprisActivity::class.java).apply {
                    device?.let {
                        putExtra(EXTRA_DEVICE_ID, it.deviceId)
                        putExtra(MprisPlugin.DEVICE_ID_KEY, it.deviceId)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_PRESENTER -> {
                val intent = Intent(this, PresenterActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_RUNCOMMAND -> {
                val intent = Intent(this, RunCommandActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }

            FloatingActionItem.ACTION_FILES -> {
                val intent = Intent(this, RemoteFileBrowserActivity::class.java).apply {
                    device?.let { putExtra(EXTRA_DEVICE_ID, it.deviceId) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
        }
    }

    private fun togglePhoneMicStreaming(host: String) {
        if (isPhoneMicStreaming) {
            stopPhoneMicStreaming()
            Toast.makeText(this, R.string.phone_mic_stopped, Toast.LENGTH_SHORT).show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
                return
            }
            startPhoneMicStreaming(host)
            Toast.makeText(this, R.string.phone_mic_started, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneMicStreaming(host: String) {
        stopPhoneMicStreaming()
        isPhoneMicStreaming = true

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            phoneMicRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            phoneMicRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed", e)
            isPhoneMicStreaming = false
            return
        }

        phoneMicThread = Thread {
            var socket: Socket? = null
            var out: OutputStream? = null
            var conn: HttpURLConnection? = null
            val buffer = ByteArray(2048)

            try {
                try {
                    socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, 59002), 2000)
                    out = socket.getOutputStream()
                } catch (e: Exception) {
                    val url = URL("http://$host:59001/mic_stream")
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setChunkedStreamingMode(2048)
                        connectTimeout = 3000
                        readTimeout = 5000
                    }
                    conn.connect()
                    out = conn.outputStream
                }

                while (isPhoneMicStreaming && !Thread.currentThread().isInterrupted) {
                    val readBytes = phoneMicRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        try {
                            out?.write(buffer, 0, readBytes)
                            out?.flush()
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Phone mic network stream ended: ${e.message}")
            } finally {
                try { out?.close() } catch (ignored: Exception) {}
                try { socket?.close() } catch (ignored: Exception) {}
                try { conn?.disconnect() } catch (ignored: Exception) {}
                mainHandler.post {
                    if (isPhoneMicStreaming) {
                        stopPhoneMicStreaming()
                    }
                }
            }
        }.apply {
            name = "FloatingPhoneMicThread"
            start()
        }
    }

    private fun stopPhoneMicStreaming() {
        isPhoneMicStreaming = false
        try {
            phoneMicThread?.interrupt()
            phoneMicThread = null
        } catch (ignored: Exception) {}
        try {
            phoneMicRecord?.stop()
            phoneMicRecord?.release()
            phoneMicRecord = null
        } catch (ignored: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRemoteMouseOverlay(device: Device?) {
        if (mouseOverlayView != null) {
            hideRemoteMouseOverlay()
            return
        }

        val themedContext = ContextThemeWrapper(this, R.style.KdeConnectTheme)
        val inflater = LayoutInflater.from(themedContext)
        mouseOverlayView = inflater.inflate(R.layout.floating_mouse_overlay, null)

        val density = resources.displayMetrics.density
        val mouseWidth = (220 * density).toInt()
        val mouseHeight = (260 * density).toInt()

        mouseOverlayParams = WindowManager.LayoutParams(
            mouseWidth,
            mouseHeight,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - mouseWidth) / 2
            y = (screenHeight - mouseHeight) / 3
        }

        val header = mouseOverlayView?.findViewById<View>(R.id.mouse_header_drag)
        val closeBtn = mouseOverlayView?.findViewById<ImageButton>(R.id.btn_close_mouse)
        val touchSurface = mouseOverlayView?.findViewById<View>(R.id.mouse_touch_surface)
        val leftBtn = mouseOverlayView?.findViewById<Button>(R.id.btn_left_click)
        val rightBtn = mouseOverlayView?.findViewById<Button>(R.id.btn_right_click)

        closeBtn?.setOnClickListener {
            hideRemoteMouseOverlay()
        }

        val activeDev = device ?: getActiveDevice()
        val mousePlugin = activeDev?.getPlugin(MousePadPlugin::class.java)

        header?.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = mouseOverlayParams.x
                        initY = mouseOverlayParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        mouseOverlayParams.x = initX + (event.rawX - touchX).toInt()
                        mouseOverlayParams.y = initY + (event.rawY - touchY).toInt()
                        try {
                            windowManager.updateViewLayout(mouseOverlayView, mouseOverlayParams)
                        } catch (ignored: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        touchSurface?.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            private var touchStartTime = 0L
            private var hasMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.x
                        lastY = event.y
                        touchStartTime = System.currentTimeMillis()
                        hasMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - lastX) * 1.5f
                        val dy = (event.y - lastY) * 1.5f

                        if (hypot(dx.toDouble(), dy.toDouble()) > 1.0) {
                            hasMoved = true
                            mousePlugin?.sendMouseDelta(dx, dy)
                            lastX = event.x
                            lastY = event.y
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchStartTime
                        if (!hasMoved && duration < 250) {
                            mousePlugin?.sendLeftClick()
                            touchSurface.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        return true
                    }
                }
                return false
            }
        })

        leftBtn?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mousePlugin?.sendSingleHold()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mousePlugin?.sendSingleRelease()
                    true
                }
                else -> false
            }
        }

        rightBtn?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    mousePlugin?.sendRightClick()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(mouseOverlayView, mouseOverlayParams)
            val card = mouseOverlayView?.findViewById<View>(R.id.floating_mouse_card)
            card?.scaleX = 0.85f
            card?.scaleY = 0.85f
            card?.alpha = 0f
            card?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add mouseOverlayView to WindowManager", e)
        }
    }

    private fun hideRemoteMouseOverlay() {
        if (mouseOverlayView == null) return
        val card = mouseOverlayView?.findViewById<View>(R.id.floating_mouse_card)
        card?.animate()?.scaleX(0.85f)?.scaleY(0.85f)?.alpha(0f)?.setDuration(150)?.withEndAction {
            try {
                windowManager.removeView(mouseOverlayView)
            } catch (ignored: Exception) {}
            mouseOverlayView = null
        }?.start()
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showMediaOverlay(device: Device?) {
        if (mediaOverlayView != null) {
            hideMediaOverlay()
            return
        }

        val themedContext = ContextThemeWrapper(this, R.style.KdeConnectTheme)
        val inflater = LayoutInflater.from(themedContext)
        mediaOverlayView = inflater.inflate(R.layout.floating_media_overlay, null)

        val density = resources.displayMetrics.density
        val mediaWidth = (290 * density).toInt()
        val mediaHeight = WindowManager.LayoutParams.WRAP_CONTENT

        mediaOverlayParams = WindowManager.LayoutParams(
            mediaWidth,
            mediaHeight,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - mediaWidth) / 2
            y = screenHeight / 4
        }

        val header = mediaOverlayView?.findViewById<View>(R.id.media_header_drag)
        val closeBtn = mediaOverlayView?.findViewById<ImageButton>(R.id.btn_close_media)
        val chipsContainer = mediaOverlayView?.findViewById<LinearLayout>(R.id.layout_player_chips)
        val titleText = mediaOverlayView?.findViewById<TextView>(R.id.text_media_title)
        val artistText = mediaOverlayView?.findViewById<TextView>(R.id.text_media_artist)
        val posText = mediaOverlayView?.findViewById<TextView>(R.id.text_media_pos)
        val durText = mediaOverlayView?.findViewById<TextView>(R.id.text_media_dur)
        val seekbarMedia = mediaOverlayView?.findViewById<SeekBar>(R.id.seekbar_media_seek)

        val btnPrev = mediaOverlayView?.findViewById<ImageButton>(R.id.btn_media_prev)
        val btnPlayPause = mediaOverlayView?.findViewById<ImageButton>(R.id.btn_media_play_pause)
        val btnNext = mediaOverlayView?.findViewById<ImageButton>(R.id.btn_media_next)

        val muteBtn = mediaOverlayView?.findViewById<ImageButton>(R.id.btn_volume_mute)
        val seekbarVol = mediaOverlayView?.findViewById<SeekBar>(R.id.seekbar_volume)
        val percentText = mediaOverlayView?.findViewById<TextView>(R.id.text_volume_percent)
        val btnMinus = mediaOverlayView?.findViewById<Button>(R.id.btn_vol_minus)
        val btn30 = mediaOverlayView?.findViewById<Button>(R.id.btn_vol_30)
        val btn60 = mediaOverlayView?.findViewById<Button>(R.id.btn_vol_60)
        val btn100 = mediaOverlayView?.findViewById<Button>(R.id.btn_vol_100)
        val btnPlus = mediaOverlayView?.findViewById<Button>(R.id.btn_vol_plus)

        closeBtn?.setOnClickListener {
            hideMediaOverlay()
        }

        val activeDev = device ?: getActiveDevice()
        val host = getActiveDeviceHost(activeDev)
        val sysVolPlugin = activeDev?.getPlugin(SystemVolumePlugin::class.java)

        var currentPosSec = 0L
        var maxDurationSec = 0L
        var isCurrentlyPlaying = false

        fun formatTime(sec: Long): String {
            val s = sec.coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            val remS = s % 60
            return if (h > 0) {
                "%d:%02d:%02d".format(h, m, remS)
            } else {
                "%d:%02d".format(m, remS)
            }
        }

        fun updateChipColors() {
            if (chipsContainer == null) return
            for (i in 0 until chipsContainer.childCount) {
                val btn = chipsContainer.getChildAt(i) as? Button ?: continue
                val pName = btn.tag as? String ?: ""
                val isCurrent = (pName == selectedPlayer) || (selectedPlayer.isEmpty() && i == 0)
                if (isCurrent) {
                    btn.setBackgroundColor(Color.parseColor("#38BDF8"))
                    btn.setTextColor(Color.parseColor("#0F172A"))
                } else {
                    btn.setBackgroundColor(Color.parseColor("#20FFFFFF"))
                    btn.setTextColor(Color.parseColor("#CBD5E1"))
                }
            }
        }

        var fetchMediaStatus: () -> Unit = {}

        fun applyJsonStatus(json: JSONObject) {
            val title = json.optString("title", "").trim()
            val artist = json.optString("artist", "").trim()
            val album = json.optString("album", "").trim()
            val status = json.optString("status", "Stopped")
            val posMicro = json.optLong("position", 0L)
            val lenMicro = json.optLong("length", 0L)
            val posSec = json.optLong("position_sec", -1L).takeIf { it >= 0 }
                ?: (if (posMicro > 100_000) posMicro / 1_000_000L else posMicro)
            val lenSec = json.optLong("length_sec", -1L).takeIf { it >= 0 }
                ?: (if (lenMicro > 100_000) lenMicro / 1_000_000L else lenMicro)

            val vol = json.optInt("volume", 50)
            val muted = json.optBoolean("muted", false)

            currentPosSec = posSec
            maxDurationSec = lenSec
            isCurrentlyPlaying = status.equals("Playing", ignoreCase = true)

            titleText?.text = if (title.isNotEmpty()) title else "Desktop Media"
            val sub = listOfNotNull(artist.ifEmpty { null }, album.ifEmpty { null }).joinToString(" - ")
            artistText?.text = if (sub.isNotEmpty()) sub else (if (status.isNotEmpty()) status else "Ready")

            if (!isMediaSeeking) {
                if (lenSec > 0) {
                    seekbarMedia?.max = lenSec.toInt()
                    seekbarMedia?.progress = posSec.toInt()
                    posText?.text = formatTime(posSec)
                    durText?.text = formatTime(lenSec)
                } else {
                    seekbarMedia?.max = 100
                    seekbarMedia?.progress = if (posSec > 0) (posSec % 100).toInt() else 0
                    posText?.text = if (posSec > 0) formatTime(posSec) else "--:--"
                    durText?.text = "--:--"
                }
            }

            btnPlayPause?.setImageResource(if (isCurrentlyPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white)

            if (!isVolumeSeeking) {
                seekbarVol?.progress = vol
                percentText?.text = "${vol}%"
            }
            muteBtn?.setImageResource(if (muted) R.drawable.ic_volume_mute else R.drawable.ic_volume)

            val currentSelected = json.optString("selected_player", selectedPlayer)
            if (selectedPlayer.isEmpty() && currentSelected.isNotEmpty()) {
                selectedPlayer = currentSelected
            }

            val playersArray = json.optJSONArray("players")
            if (playersArray != null && chipsContainer != null) {
                val currentCount = chipsContainer.childCount
                var needsRebuild = (currentCount != playersArray.length())
                if (!needsRebuild) {
                    for (i in 0 until playersArray.length()) {
                        val p = playersArray.optString(i)
                        val btn = chipsContainer.getChildAt(i) as? Button
                        if (btn?.tag != p) {
                            needsRebuild = true
                            break
                        }
                    }
                }
                if (needsRebuild) {
                    chipsContainer.removeAllViews()
                    for (i in 0 until playersArray.length()) {
                        val pName = playersArray.optString(i)
                        val chip = Button(this@FloatingButtonService).apply {
                            text = pName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                            tag = pName
                            isAllCaps = false
                            textSize = 12f
                            val isCurrent = (pName == selectedPlayer) || (selectedPlayer.isEmpty() && i == 0)
                            if (isCurrent) {
                                setBackgroundColor(Color.parseColor("#38BDF8"))
                                setTextColor(Color.parseColor("#0F172A"))
                            } else {
                                setBackgroundColor(Color.parseColor("#20FFFFFF"))
                                setTextColor(Color.parseColor("#CBD5E1"))
                            }
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                (28 * density).toInt()
                            ).apply {
                                marginEnd = (6 * density).toInt()
                            }
                            layoutParams = lp
                            setOnClickListener {
                                selectedPlayer = pName
                                updateChipColors()
                                fetchMediaStatus()
                                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                        chipsContainer.addView(chip)
                    }
                } else {
                    updateChipColors()
                }
            }
        }

        fetchMediaStatus = {
            Thread {
                try {
                    val playerParam = if (selectedPlayer.isNotEmpty()) "?player=" + URLEncoder.encode(selectedPlayer, "UTF-8") else ""
                    val url = URL("http://" + host + ":59001/media_status" + playerParam)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2000
                        readTimeout = 2000
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        mainHandler.post {
                            if (mediaOverlayView != null) {
                                applyJsonStatus(json)
                            }
                        }
                    }
                    conn.disconnect()
                } catch (ignored: Exception) {}
            }.start()
        }

        fun sendMediaAction(act: String, extra: String = "") {
            Thread {
                try {
                    val playerParam = if (selectedPlayer.isNotEmpty()) "&player=" + URLEncoder.encode(selectedPlayer, "UTF-8") else ""
                    val extraParam = if (extra.isNotEmpty()) "&" + extra else ""
                    val url = URL("http://" + host + ":59001/media_action?action=" + act + playerParam + extraParam)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2000
                        readTimeout = 2000
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        mainHandler.post {
                            if (mediaOverlayView != null) {
                                applyJsonStatus(json)
                            }
                        }
                    }
                    conn.disconnect()
                } catch (ignored: Exception) {}
            }.start()
        }

        fun sendVolumeLevel(vol: Int) {
            val clamped = vol.coerceIn(0, 100)
            percentText?.text = "${clamped}%"
            if (!isVolumeSeeking) {
                seekbarVol?.progress = clamped
            }

            Thread {
                try {
                    val url = URL("http://" + host + ":59001/set_volume?volume=" + clamped)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 1500
                        readTimeout = 1500
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val v = json.optInt("volume", clamped)
                        val m = json.optBoolean("muted", false)
                        mainHandler.post {
                            if (!isVolumeSeeking) seekbarVol?.progress = v
                            percentText?.text = "${v}%"
                            muteBtn?.setImageResource(if (m) R.drawable.ic_volume_mute else R.drawable.ic_volume)
                        }
                    }
                    conn.disconnect()
                } catch (ignored: Exception) {}

                val defaultSink = sysVolPlugin?.sinks?.firstOrNull { it.isDefault } ?: sysVolPlugin?.sinks?.firstOrNull()
                if (defaultSink != null) {
                    sysVolPlugin?.sendVolume(defaultSink.name, (clamped * defaultSink.maxVolume) / 100)
                }
            }.start()
        }

        fun sendMuteToggle() {
            Thread {
                try {
                    val url = URL("http://" + host + ":59001/set_volume?mute=toggle")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 1500
                        readTimeout = 1500
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val v = json.optInt("volume", seekbarVol?.progress ?: 50)
                        val m = json.optBoolean("muted", false)
                        mainHandler.post {
                            if (!isVolumeSeeking) seekbarVol?.progress = v
                            percentText?.text = "${v}%"
                            muteBtn?.setImageResource(if (m) R.drawable.ic_volume_mute else R.drawable.ic_volume)
                        }
                    }
                    conn.disconnect()
                } catch (ignored: Exception) {}

                val defaultSink = sysVolPlugin?.sinks?.firstOrNull { it.isDefault } ?: sysVolPlugin?.sinks?.firstOrNull()
                if (defaultSink != null) {
                    sysVolPlugin?.sendMute(defaultSink.name, !defaultSink.mute)
                }
            }.start()
        }

        header?.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = mediaOverlayParams.x
                        initY = mediaOverlayParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        mediaOverlayParams.x = initX + (event.rawX - touchX).toInt()
                        mediaOverlayParams.y = initY + (event.rawY - touchY).toInt()
                        try {
                            windowManager.updateViewLayout(mediaOverlayView, mediaOverlayParams)
                        } catch (ignored: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        fetchMediaStatus()

        var pollCounter = 0
        mediaUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (mediaOverlayView == null) return

                if (isCurrentlyPlaying && !isMediaSeeking) {
                    if (maxDurationSec <= 0 || currentPosSec < maxDurationSec) {
                        currentPosSec++
                        seekbarMedia?.progress = currentPosSec.toInt()
                        posText?.text = formatTime(currentPosSec)
                    }
                }

                pollCounter++
                if (pollCounter % 2 == 0) {
                    fetchMediaStatus()
                }

                mainHandler.postDelayed(this, 1000)
            }
        }
        mediaUpdateRunnable = runnable
        mainHandler.postDelayed(runnable, 1000)

        btnPlayPause?.setOnClickListener {
            sendMediaAction("play_pause")
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        btnPrev?.setOnClickListener {
            sendMediaAction("previous")
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        btnNext?.setOnClickListener {
            sendMediaAction("next")
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        seekbarMedia?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    posText?.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isMediaSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isMediaSeeking = false
                val pos = sb?.progress ?: 0
                currentPosSec = pos.toLong()
                sendMediaAction("seek", "position=" + pos)
            }
        })

        muteBtn?.setOnClickListener {
            sendMuteToggle()
        }

        seekbarVol?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    percentText?.text = "${progress}%"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isVolumeSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isVolumeSeeking = false
                val p = sb?.progress ?: 50
                sendVolumeLevel(p)
            }
        })

        btnMinus?.setOnClickListener {
            val curr = seekbarVol?.progress ?: 50
            sendVolumeLevel(curr - 10)
        }
        btn30?.setOnClickListener { sendVolumeLevel(30) }
        btn60?.setOnClickListener { sendVolumeLevel(60) }
        btn100?.setOnClickListener { sendVolumeLevel(100) }
        btnPlus?.setOnClickListener {
            val curr = seekbarVol?.progress ?: 50
            sendVolumeLevel(curr + 10)
        }

        try {
            windowManager.addView(mediaOverlayView, mediaOverlayParams)
            val card = mediaOverlayView?.findViewById<View>(R.id.floating_media_card)
            card?.scaleX = 0.85f
            card?.scaleY = 0.85f
            card?.alpha = 0f
            card?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add media overlay", e)
        }
    }

    private fun hideMediaOverlay() {
        mediaUpdateRunnable?.let {
            mainHandler.removeCallbacks(it)
            mediaUpdateRunnable = null
        }
        if (mediaOverlayView == null) return
        val card = mediaOverlayView?.findViewById<View>(R.id.floating_media_card)
        card?.animate()?.scaleX(0.85f)?.scaleY(0.85f)?.alpha(0f)?.setDuration(150)?.withEndAction {
            try {
                windowManager.removeView(mediaOverlayView)
            } catch (ignored: Exception) {}
            mediaOverlayView = null
        }?.start()
    }


    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showClipboardOverlay(device: Device?) {
        if (clipboardOverlayView != null) {
            hideClipboardOverlay()
            return
        }

        val themedContext = ContextThemeWrapper(this, R.style.KdeConnectTheme)
        val inflater = LayoutInflater.from(themedContext)
        clipboardOverlayView = inflater.inflate(R.layout.floating_clipboard_overlay, null)

        val density = resources.displayMetrics.density
        val clipWidth = (290 * density).toInt()
        val clipHeight = WindowManager.LayoutParams.WRAP_CONTENT

        clipboardOverlayParams = WindowManager.LayoutParams(
            clipWidth,
            clipHeight,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - clipWidth) / 2
            y = screenHeight / 4
        }

        val header = clipboardOverlayView?.findViewById<View>(R.id.clip_header_drag)
        val closeBtn = clipboardOverlayView?.findViewById<ImageButton>(R.id.btn_close_clip)
        val textPcClip = clipboardOverlayView?.findViewById<TextView>(R.id.text_pc_clip_preview)
        val textPhoneClip = clipboardOverlayView?.findViewById<TextView>(R.id.text_phone_clip_preview)
        val btnPullPcClip = clipboardOverlayView?.findViewById<Button>(R.id.btn_pull_pc_clip)
        val btnPushPhoneClip = clipboardOverlayView?.findViewById<Button>(R.id.btn_push_phone_clip)
        val editCustomClip = clipboardOverlayView?.findViewById<EditText>(R.id.edit_custom_clip)
        val btnSendCustomClip = clipboardOverlayView?.findViewById<Button>(R.id.btn_send_custom_clip)

        closeBtn?.setOnClickListener {
            hideClipboardOverlay()
        }

        val activeDev = device ?: getActiveDevice()
        val host = getActiveDeviceHost(activeDev)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        fun refreshPhoneClip() {
            val phoneText = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            textPhoneClip?.text = if (!phoneText.isNullOrEmpty()) phoneText else "Phone clipboard empty"
        }

        fun refreshPcClip() {
            Thread {
                var pcText: String? = null
                try {
                    val url = URL("http://" + host + ":59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2500
                        readTimeout = 2500
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        pcText = json.optString("current", "").ifEmpty { json.optString("clipboard", "") }.trim()
                    }
                    conn.disconnect()
                } catch (ignored: Exception) {}

                if (pcText.isNullOrEmpty()) {
                    val localClip = ClipboardListener.instance(this@FloatingButtonService).currentContent
                    if (ClipboardListener.isValidClipboardText(localClip)) {
                        pcText = localClip
                    }
                }

                mainHandler.post {
                    if (clipboardOverlayView != null) {
                        textPcClip?.text = if (!pcText.isNullOrEmpty()) pcText else "PC clipboard empty / unreachable"
                    }
                }
            }.start()
        }

        fun sendClipToPc(textToSend: String) {
            if (textToSend.isEmpty()) {
                Toast.makeText(this@FloatingButtonService, "Nothing to send", Toast.LENGTH_SHORT).show()
                return
            }
            Thread {
                var sent = false
                try {
                    val url = URL("http://" + host + ":59001/clipboard")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 2000
                        readTimeout = 2000
                        doOutput = true
                    }
                    conn.outputStream.write(textToSend.toByteArray(Charsets.UTF_8))
                    conn.outputStream.flush()
                    if (conn.responseCode == 200) sent = true
                    conn.disconnect()
                } catch (ignored: Exception) {}

                if (activeDev != null) {
                    try {
                        val plugin = activeDev.getPlugin(ClipboardPlugin::class.java)
                        plugin?.propagateClipboard(textToSend)
                        sent = true
                    } catch (ignored: Exception) {}
                }

                mainHandler.post {
                    if (sent) {
                        Toast.makeText(this@FloatingButtonService, "Sent to PC clipboard", Toast.LENGTH_SHORT).show()
                        textPcClip?.text = textToSend
                        ClipboardHistoryManager.addItem(this@FloatingButtonService, textToSend, isReceived = false)
                    } else {
                        Toast.makeText(this@FloatingButtonService, "Failed to send clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        refreshPhoneClip()
        refreshPcClip()

        btnPullPcClip?.setOnClickListener {
            val pcText = textPcClip?.text?.toString()?.trim()
            if (pcText.isNullOrEmpty() || pcText == "Loading PC clipboard..." || pcText == "PC clipboard empty / unreachable") {
                Toast.makeText(this@FloatingButtonService, "No PC clipboard content to copy", Toast.LENGTH_SHORT).show()
            } else {
                val clip = ClipData.newPlainText("PC Clipboard", pcText)
                cm?.setPrimaryClip(clip)
                ClipboardHistoryManager.addItem(this@FloatingButtonService, pcText, isReceived = true)
                refreshPhoneClip()
                Toast.makeText(this@FloatingButtonService, "Copied PC clipboard to phone", Toast.LENGTH_SHORT).show()
            }
        }

        btnPushPhoneClip?.setOnClickListener {
            val phoneText = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (phoneText.isNullOrEmpty()) {
                Toast.makeText(this@FloatingButtonService, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                sendClipToPc(phoneText)
            }
        }

        btnSendCustomClip?.setOnClickListener {
            val customText = editCustomClip?.text?.toString()?.trim() ?: ""
            if (customText.isNotEmpty()) {
                sendClipToPc(customText)
                editCustomClip?.setText("")
            } else {
                Toast.makeText(this@FloatingButtonService, "Enter text to send", Toast.LENGTH_SHORT).show()
            }
        }

        header?.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = clipboardOverlayParams.x
                        initY = clipboardOverlayParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        clipboardOverlayParams.x = initX + (event.rawX - touchX).toInt()
                        clipboardOverlayParams.y = initY + (event.rawY - touchY).toInt()
                        try {
                            windowManager.updateViewLayout(clipboardOverlayView, clipboardOverlayParams)
                        } catch (ignored: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(clipboardOverlayView, clipboardOverlayParams)
            val card = clipboardOverlayView?.findViewById<View>(R.id.floating_clip_card)
            card?.scaleX = 0.85f
            card?.scaleY = 0.85f
            card?.alpha = 0f
            card?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add clipboard overlay", e)
        }
    }

    private fun hideClipboardOverlay() {
        if (clipboardOverlayView == null) return
        val card = clipboardOverlayView?.findViewById<View>(R.id.floating_clip_card)
        card?.animate()?.scaleX(0.85f)?.scaleY(0.85f)?.alpha(0f)?.setDuration(150)?.withEndAction {
            try {
                windowManager.removeView(clipboardOverlayView)
            } catch (ignored: Exception) {}
            clipboardOverlayView = null
        }?.start()
    }

    private fun getActiveDeviceHost(device: Device?): String {
        val dev = device ?: getActiveDevice()
        return dev?.getRemoteIpAddress() ?: "127.0.0.1"
    }

    private fun getActiveDevice(): Device? {
        val reachable = KdeConnect.getInstance().devices.values.filter { it.isReachable && it.isPaired }
        return reachable.firstOrNull() ?: KdeConnect.getInstance().devices.values.firstOrNull()
    }

    private fun removeViews() {
        hideClipboardOverlay()
        hideMediaOverlay()
        hideRemoteMouseOverlay()
        if (isMenuShowing && menuOverlayView != null) {
            try {
                windowManager.removeView(menuOverlayView)
            } catch (ignored: Exception) {
            }
            isMenuShowing = false
        }
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView)
            } catch (ignored: Exception) {
            }
            bubbleView = null
        }
        if (dismissView != null) {
            try {
                windowManager.removeView(dismissView)
            } catch (ignored: Exception) {
            }
            dismissView = null
        }
    }

    companion object {
        private const val TAG = "FloatingButtonService"
        const val ACTION_START = "org.kde.kdeconnect.floating.ACTION_START"
        const val ACTION_STOP = "org.kde.kdeconnect.floating.ACTION_STOP"
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val NOTIFICATION_ID = 202409

        var isRunning: Boolean = false
            private set
    }
}
