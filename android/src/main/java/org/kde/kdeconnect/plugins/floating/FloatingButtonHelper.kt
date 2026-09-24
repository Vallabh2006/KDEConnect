/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect_tp.R

object FloatingButtonHelper {

    private const val PREFS_NAME = "floating_button_preferences"
    private const val KEY_ENABLED_ACTIONS = "enabled_actions"
    const val PREF_KEY_FLOATING_ENABLED = "floating_button_enabled_pref"

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.packageName)
            )
            activity.startActivity(intent)
        }
    }

    fun checkAndRequestPermission(activity: FragmentActivity, onGranted: () -> Unit) {
        if (hasOverlayPermission(activity)) {
            onGranted()
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.overlay_permission_grant) { _, _ ->
                    requestOverlayPermission(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun isServiceRunning(): Boolean {
        return FloatingButtonService.isRunning
    }

    fun startFloatingButton(context: Context) {
        if (!hasOverlayPermission(context)) {
            return
        }
        val intent = Intent(context, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopFloatingButton(context: Context) {
        val intent = Intent(context, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggleFloatingButton(context: Context) {
        if (isServiceRunning()) {
            stopFloatingButton(context)
        } else {
            startFloatingButton(context)
        }
    }

    fun getEnabledActions(context: Context): List<FloatingActionItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultIds = FloatingActionItem.ALL_ACTIONS.filter { it.defaultEnabled }.map { it.id }.toSet()
        val savedIds = prefs.getStringSet(KEY_ENABLED_ACTIONS, defaultIds) ?: defaultIds
        return FloatingActionItem.ALL_ACTIONS.filter { savedIds.contains(it.id) }
    }

    fun saveEnabledActions(context: Context, enabledIds: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putStringSet(KEY_ENABLED_ACTIONS, enabledIds)
        }
    }

    fun showOptionsDialog(activity: FragmentActivity) {
        val isRunning = isServiceRunning()
        val items = arrayOf(
            if (isRunning) activity.getString(R.string.floating_button_disabled) else activity.getString(R.string.floating_button_enabled),
            activity.getString(R.string.customize_floating_button)
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.floating_action_button)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        checkAndRequestPermission(activity) {
                            toggleFloatingButton(activity)
                            val msg = if (isServiceRunning()) R.string.floating_button_disabled else R.string.floating_button_enabled
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> showCustomizationDialog(activity)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showCustomizationDialog(activity: FragmentActivity) {
        val allActions = FloatingActionItem.ALL_ACTIONS
        val enabledActions = getEnabledActions(activity).map { it.id }.toSet()
        val actionNames = allActions.map { activity.getString(it.titleRes) }.toTypedArray()
        val checkedItems = allActions.map { enabledActions.contains(it.id) }.toBooleanArray()
        val selectedIds = enabledActions.toMutableSet()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.customize_floating_button)
            .setMultiChoiceItems(actionNames, checkedItems) { _, which, isChecked ->
                val actionId = allActions[which].id
                if (isChecked) {
                    selectedIds.add(actionId)
                } else {
                    selectedIds.remove(actionId)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveEnabledActions(activity, selectedIds)
                Toast.makeText(activity, "Custom actions saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
