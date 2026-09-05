/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/** Shows one third-party component's notice, as monospaced text; opened from [Licenses]. */
class LicenseReader : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_license_reader)

        requireViewById<View>(R.id.license_root).applySystemBarInsets(left = true, right = true)
        requireViewById<Toolbar>(R.id.toolbar).apply {
            title = intent.getStringExtra(EXTRA_NAME)
            applyStatusBarInsetToToolbar()
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        requireViewById<View>(R.id.license_scroll).applySystemBarInsets(bottom = true)
        requireViewById<TextView>(R.id.license_text).text =
            resources.openRawResource(intent.getIntExtra(EXTRA_TEXT, 0)).bufferedReader().use { it.readText() }
    }

    companion object {
        /** The component's name, shown as the title. */
        const val EXTRA_NAME = "name"

        /** The raw text resource of the notice. */
        const val EXTRA_TEXT = "text"
    }
}
