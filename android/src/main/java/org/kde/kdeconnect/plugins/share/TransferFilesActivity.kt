/*
 * SPDX-FileCopyrightText: 2026 Antigravity
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.content.Intent
import android.os.Bundle
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.extensions.viewBinding
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityTransferFilesBinding

class TransferFilesActivity : BaseActivity<ActivityTransferFilesBinding>() {

    override val binding: ActivityTransferFilesBinding by viewBinding(ActivityTransferFilesBinding::inflate)

    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            finish()
            return
        }

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.transfer_files)

        setupListeners()
    }

    private fun setupListeners() {
        binding.cardSendFiles.setOnClickListener {
            val intent = Intent(this, SendFileActivity::class.java)
            intent.putExtra("deviceId", deviceId)
            startActivity(intent)
        }

        binding.cardReceiveFiles.setOnClickListener {
            val intent = Intent(this, RemoteFileBrowserActivity::class.java)
            intent.putExtra("deviceId", deviceId)
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
