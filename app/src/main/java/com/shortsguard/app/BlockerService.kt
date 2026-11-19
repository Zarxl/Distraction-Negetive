package com.shortsguard.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

const val PREFS = "shortsguard"
const val KEY_YT = "block_youtube_shorts"
const val KEY_IG = "block_instagram_reels"

private const val YT = "com.google.android.youtube"
private const val IG = "com.instagram.android"

private class Scan {
    val ids = HashSet<String>()
    val titles = ArrayList<Pair<Int, String>>() // (top position, text)
    var miniClose: AccessibilityNodeInfo? = null
    var homeTab: AccessibilityNodeInfo? = null
}

class BlockerService : AccessibilityService() {

    private var lastAction = 0L
    private var lastDmSeen = 0L
    private var friendReelActive = false
    private var friendReelStart = 0L
    private var closeMiniUntil = 0L

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        when (e.packageName?.toString()) {
            YT -> if (prefs.getBoolean(KEY_YT, false)) handleYouTube()
            IG -> if (prefs.getBoolean(KEY_IG, false)) handleInstagram(e)
            else -> friendReelActive = false
        }
    }

    // ---------- YouTube ----------
    private fun handleYouTube() {
        val s = scan() ?: return
        val now = System.currentTimeMillis()

        // 1) Shorts player -> jump to the Home tab (does not close the app)
        val inShortsPlayer = s.ids.any {
            it.startsWith("reel_player") || it.startsWith("reel_recycler") ||
                it.startsWith("reel_watch") || it.startsWith("shorts_player")
        }
        if (inShortsPlayer) {
            leave(s)
            return
        }

        // 2) Miniplayer is showing: if we just blocked a video, close it so it stops playing
        if (s.miniClose != null) {
            if (now < closeMiniUntil) {
                closeMiniUntil = 0L
                click(s.miniClose!!)
            }
            return
        }

        // 3) Hidden filter: block videos whose title has NSFW words
        val inWatch = s.ids.any {
            it.startsWith("watch_player") || it.startsWith("player_view") || it.startsWith("watch_while")
        }
        if (inWatch && now >= closeMiniUntil) {
            // The playing video's title is the top-most title under the player
            val title = s.titles.filter { it.first > 0 }.minByOrNull { it.first }?.second
            if (title != null && NsfwWords.matches(title)) {
                closeMiniUntil = now + 4000
                back()
            }
        }
    }

    // ---------- Instagram ----------
    private fun handleInstagram(event: AccessibilityEvent) {
        val s = scan() ?: return
        val now = System.currentTimeMillis()

        val inDm = s.ids.any { it.startsWith("direct_thread") || it.startsWith("row_thread_composer") }
        val inReels = s.ids.any { it.startsWith("clips_viewer") }
        val commentsOpen = s.ids.any { it.contains("comment") }

        if (inDm) lastDmSeen = now

        if (!inReels) {
            friendReelActive = false
            return
        }

        if (!friendReelActive) {
            if (now - lastDmSeen < 4000) {
                // Opened from a chat: this is a friend-sent reel, allow it
                friendReelActive = true
                friendReelStart = now
            } else {
                // Reels tab / feed / Explore: block (go to Home tab)
                leave(s)
            }
            return
        }

        // Friend reel is playing. Swiping to another reel sends you back to the chat.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            !commentsOpen && now - friendReelStart > 1500
        ) {
            friendReelActive = false
            back()
        }
    }

    // ---------- Helpers ----------
    /** Tap the Home tab if the bottom bar is visible; otherwise press Back. */
    private fun leave(s: Scan) {
        if (!throttle()) return
        val tab = s.homeTab
        if (tab != null && click(tab)) return
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun back() {
        if (!throttle()) return
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun throttle(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAction < 700) return false
        lastAction = now
        return true
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return false
    }

    private fun scan(): Scan? {
        val root = rootInActiveWindow ?: return null
        val s = Scan()
        val screenH = resources.displayMetrics.heightPixels
        val r = Rect()
        var count = 0

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || count > 1500) return
            count++
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val text = n.text?.toString() ?: ""
            if (id.isNotEmpty()) s.ids.add(id)

            if (id.contains("title") && text.isNotBlank()) {
                n.getBoundsInScreen(r)
                s.titles.add(r.top to text)
            }
            if (s.miniClose == null &&
                (desc.contains("Close miniplayer", true) || id.contains("floaty_bar_close"))
            ) s.miniClose = n

            if (s.homeTab == null &&
                (id == "feed_tab" || desc.startsWith("Home", true) || text.equals("Home", true))
            ) {
                n.getBoundsInScreen(r)
                if (r.top > screenH * 0.75) s.homeTab = n
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }

        walk(root, 0)
        return s
    }
}
