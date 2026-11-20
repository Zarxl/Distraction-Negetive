package com.shortsguard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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

        fun toggle(label: String, key: String) = Switch(this).apply {
            text = label
            textSize = 18f
            isChecked = prefs.getBoolean(key, false)
            setPadding(0, pad, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }

        root.addView(toggle("Hide YouTube Shorts", KEY_YT))
        root.addView(toggle("Hide Instagram Reels (friend-sent only)", KEY_IG))

        root.addView(TextView(this).apply {
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, pad, 0, 0)
            text = "Setup: tap the button above, find ShortsGuard under Installed/Downloaded apps, and turn it on. " +
                "If Android says \"Restricted setting\", go to Settings > Apps > ShortsGuard > ⋮ menu > " +
                "Allow restricted settings, then try again.\n\n" +
                "YouTube: opening a Short sends you to the Home tab.\n" +
                "Instagram: the Reels tab and Search/Explore tab send you to Home. Reels opened from a profile are blocked. Reels that show up in your Home feed get scrolled past. " +
                "A reel a friend sent in chat opens normally, but swiping to the next reel sends you back to the chat."
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "Service is ON. Toggles below are active." else "Service is OFF. Turn it on in Accessibility settings."
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, BlockerService::class.java)
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
    }
}
