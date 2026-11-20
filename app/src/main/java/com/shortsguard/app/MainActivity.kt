package com.shortsguard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "ShortsGuard"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "Keep running in background"
            setOnClickListener { openBatterySettings() }
        })

        fun toggle(label: String, key: String) = Switch(this).apply {
            text = label
            textSize = 18f
            isChecked = prefs.getBoolean(key, false)
            setPadding(0, pad, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }

        root.addView(toggle("Hide YouTube Shorts", KEY_YT))
        root.addView(toggle("Hide Instagram Reels (friend-sent only)", KEY_IG))
        root.addView(toggle("Lock Instagram Home feed (no scrolling)", KEY_IG_LOCK))

        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "Setup: tap \"Open Accessibility settings\", find ShortsGuard under Installed/Downloaded apps, and turn it on. " +
                "If Android says \"Restricted setting\", go to Settings > Apps > ShortsGuard > ⋮ menu > " +
                "Allow restricted settings, then try again.\n\n" +
                "YouTube: opening a Short sends you to the Home tab.\n" +
                "Instagram: the Reels tab and Search/Explore tab send you to Home. Reels opened from a profile are blocked. " +
                "Reels that show up in your Home feed get scrolled past. " +
                "A reel a friend sent in chat opens normally, but swiping to the next reel sends you back to the chat.\n" +
                "Lock feed: the Instagram Home feed snaps back to the top when you try to scroll down. " +
                "Stories and the other tabs still work.\n\n" +
                "Running in the background: once the Accessibility service is on, it runs by itself even with this app closed, " +
                "and starts again after a restart. Phone battery savers can kill it, so tap \"Keep running in background\" and allow it. " +
                "On Xiaomi, Realme, Oppo, Vivo and similar phones, also turn on Autostart for ShortsGuard and lock it in the Recent apps screen."
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val service = if (isServiceEnabled()) "Service is ON." else "Service is OFF. Turn it on in Accessibility settings."
        val pm = getSystemService(PowerManager::class.java)
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "Battery: unrestricted."
        } else {
            "Battery: restricted (tap \"Keep running in background\")."
        }
        status.text = "$service\n$battery"
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        val intent = if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, BlockerService::class.java)
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
    }
}
