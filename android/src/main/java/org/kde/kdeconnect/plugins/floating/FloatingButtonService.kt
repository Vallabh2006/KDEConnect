/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.floating

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.material.card.MaterialCardView
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.clipboard.ClipboardManagerActivity
import org.kde.kdeconnect.plugins.mousepad.MousePadActivity
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
import kotlin.math.hypot

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var dismissView: View? = null
    private var menuOverlayView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var dismissParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private var isOverDismissZone = false
    private var isMenuShowing = false

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

    override fun onDestroy() {
        isRunning = false
        removeViews()
        super.onDestroy()
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleView(inflater: LayoutInflater) {
        bubbleView = inflater.inflate(R.layout.floating_bubble_layout, null)

        val density = resources.displayMetrics.density
        val bubbleSize = (72 * density).toInt()

        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - bubbleSize - (16 * density).toInt()
            y = (screenHeight * 0.4).toInt()
        }

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

                        if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > (10 * density)) {
                            isDragging = true
                            showDismissTarget(true)
                        }

                        if (isDragging) {
                            bubbleParams.x = initialX + dx
                            bubbleParams.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams)
                            } catch (ignored: Exception) {
                            }

                            checkDismissTargetCollision(event.rawX, event.rawY)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
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
                            if (duration < 350) {
                                toggleMenu()
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
        menuOverlayView = inflater.inflate(R.layout.floating_menu_layout, null)

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

        menuOverlayView?.findViewById<View>(R.id.floating_menu_card)?.setOnClickListener {
            // Consume touch so card background doesn't close menu
        }

        menuOverlayView?.findViewById<View>(R.id.btn_menu_close)?.setOnClickListener {
            hideMenu()
        }

        menuOverlayView?.findViewById<View>(R.id.btn_menu_customize)?.setOnClickListener {
            hideMenu()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
    }

    private fun showDismissTarget(show: Boolean) {
        dismissView?.let { dv ->
            if (show) {
                dv.visibility = View.VISIBLE
                dv.alpha = 0f
                dv.animate().alpha(1f).setDuration(200).start()
            } else {
                dv.animate().alpha(0f).setDuration(150).withEndAction {
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
        val bubbleWidth = (72 * density).toInt()
        val targetX = if (bubbleParams.x + bubbleWidth / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - bubbleWidth
        }

        val startX = bubbleParams.x
        val animator = ValueAnimator.ofInt(startX, targetX)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { va ->
            bubbleParams.x = va.animatedValue as Int
            try {
                windowManager.updateViewLayout(bubbleView, bubbleParams)
            } catch (ignored: Exception) {
            }
        }
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

        val activeDevice = getActiveDevice()
        val titleView = menuOverlayView?.findViewById<TextView>(R.id.floating_menu_device_title)
        titleView?.text = activeDevice?.name ?: getString(R.string.kde_connect)

        populateActionGrid(activeDevice)

        try {
            windowManager.addView(menuOverlayView, menuParams)
            isMenuShowing = true
            val card = menuOverlayView?.findViewById<View>(R.id.floating_menu_card)
            card?.scaleX = 0.85f
            card?.scaleY = 0.85f
            card?.alpha = 0f
            card?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(180)?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add menuOverlayView to WindowManager", e)
        }
    }

    private fun hideMenu() {
        if (!isMenuShowing || menuOverlayView == null) return
        val card = menuOverlayView?.findViewById<View>(R.id.floating_menu_card)
        card?.animate()?.scaleX(0.85f)?.scaleY(0.85f)?.alpha(0f)?.setDuration(140)?.withEndAction {
            try {
                windowManager.removeView(menuOverlayView)
            } catch (ignored: Exception) {
            }
            isMenuShowing = false
        }?.start()
    }

    @SuppressLint("InflateParams")
    private fun populateActionGrid(device: Device?) {
        val grid = menuOverlayView?.findViewById<GridLayout>(R.id.floating_actions_grid) ?: return
        grid.removeAllViews()

        val enabledActions = FloatingButtonHelper.getEnabledActions(this)
        val themedContext = ContextThemeWrapper(this, R.style.KdeConnectTheme)
        val inflater = LayoutInflater.from(themedContext)

        for (action in enabledActions) {
            val itemView = inflater.inflate(R.layout.item_floating_action, grid, false)
            val iconView = itemView.findViewById<ImageView>(R.id.action_icon)
            val labelView = itemView.findViewById<TextView>(R.id.action_label)

            iconView.setImageResource(action.iconRes)
            labelView.setText(action.titleRes)

            itemView.setOnClickListener {
                hideMenu()
                launchAction(action.id, device?.deviceId)
            }

            grid.addView(itemView)
        }
    }

    private fun launchAction(actionId: String, deviceId: String?) {
        val intent = when (actionId) {
            FloatingActionItem.ACTION_MOUSEPAD -> Intent(this, MousePadActivity::class.java)
            FloatingActionItem.ACTION_CLIPBOARD -> Intent(this, ClipboardManagerActivity::class.java)
            FloatingActionItem.ACTION_MPRIS -> Intent(this, MprisActivity::class.java)
            FloatingActionItem.ACTION_PRESENTER -> Intent(this, PresenterActivity::class.java)
            FloatingActionItem.ACTION_SCREENMIRROR -> Intent(this, ScreenMirrorActivity::class.java)
            FloatingActionItem.ACTION_RUNCOMMAND -> Intent(this, RunCommandActivity::class.java)
            FloatingActionItem.ACTION_FILES -> Intent(this, RemoteFileBrowserActivity::class.java)
            FloatingActionItem.ACTION_TASKMANAGER -> Intent(this, TaskManagerActivity::class.java)
            FloatingActionItem.ACTION_TERMINAL -> Intent(this, TerminalActivity::class.java)
            else -> null
        }

        if (intent != null) {
            if (deviceId != null) {
                intent.putExtra(EXTRA_DEVICE_ID, deviceId)
                intent.putExtra(MprisPlugin.DEVICE_ID_KEY, deviceId)
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open action: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getActiveDevice(): Device? {
        val reachable = KdeConnect.getInstance().devices.values.filter { it.isReachable && it.isPaired }
        return reachable.firstOrNull()
    }

    private fun removeViews() {
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
