package com.shortsguard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

const val PREFS = "shortsguard"
const val KEY_YT = "block_youtube_shorts"
const val KEY_IG = "block_instagram_reels"
const val KEY_IG_LOCK = "lock_instagram_home"

private const val YT = "com.google.android.youtube"
private const val IG = "com.instagram.android"

private class Scan {
    val ids = HashSet<String>()
    val titles = ArrayList<Pair<Int, String>>() // (top position, text)
    var miniClose: AccessibilityNodeInfo? = null
    var homeTab: AccessibilityNodeInfo? = null
    var homeSelected = false
    var reelsTabSelected = false
    var searchTabSelected = false
    val reelRects = ArrayList<Rect>() // reels visible inline in a feed
    var feedList: AccessibilityNodeInfo? = null // the big vertical scrolling list
}

class BlockerService : AccessibilityService() {

    private var lastAction = 0L
    private val recent = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var lastSnap = 0L

    private var lastDmSeen = 0L
    private var friendReelActive = false
    private var friendReelStart = 0L

    private var ytHomeUntil = 0L

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        when (e.packageName?.toString()) {
            YT -> if (prefs.getBoolean(KEY_YT, false)) handleYouTube()
            IG -> {
                val blockReels = prefs.getBoolean(KEY_IG, false)
                val lockFeed = prefs.getBoolean(KEY_IG_LOCK, false)
                if (blockReels || lockFeed) handleInstagram(e, blockReels, lockFeed)
            }
            else -> friendReelActive = false
        }
    }

    // ---------- YouTube ----------
    private fun handleYouTube() {
        val s = scan() ?: return
        val now = System.currentTimeMillis()

        // 1) Shorts player -> Home tab
        val inShortsPlayer = s.ids.any {
            it.startsWith("reel_player") || it.startsWith("reel_recycler") ||
                it.startsWith("reel_watch") || it.startsWith("shorts_player")
        }
        if (inShortsPlayer) {
            leave(s)
            return
        }

        // 2) Finishing an NSFW block: close the miniplayer, then go to the Home tab
        if (now < ytHomeUntil) {
            val close = s.miniClose
            if (close != null) {
                if (throttle()) click(close)
                return
            }
            val tab = s.homeTab
            if (tab != null) {
                if (!s.homeSelected && throttle()) click(tab)
                ytHomeUntil = 0L
            }
            return
        }

        // 3) Hidden filter: NSFW words in the playing video's title
        val inWatch = s.ids.any {
            it.startsWith("watch_player") || it.startsWith("player_view") || it.startsWith("watch_while")
        }
        if (inWatch && s.miniClose == null) {
            // The playing video's title is the top-most title under the player
            val title = s.titles.filter { it.first > 0 }.minByOrNull { it.first }?.second
            if (title != null && NsfwWords.matches(title)) {
                ytHomeUntil = now + 6000
                back() // minimizes the video; step 2 then closes it and goes Home
            }
        }
    }

    // ---------- Instagram ----------
    private fun handleInstagram(event: AccessibilityEvent, blockReels: Boolean, lockFeed: Boolean) {
        val s = scan() ?: return
        val now = System.currentTimeMillis()

        val onHomeFeed = s.homeSelected ||
            s.ids.any { it == "reels_tray_container" || it.startsWith("reel_tray") }

        // Lock: the Home feed can't be scrolled down. It snaps back to the top.
        if (lockFeed && onHomeFeed && !s.reelsTabSelected && !s.searchTabSelected && isScrollDown(event)) {
            snapToTop(s)
            return
        }

        if (!blockReels) return

        val inDm = s.ids.any { it.startsWith("direct_thread") || it.startsWith("row_thread_composer") }
        if (inDm) lastDmSeen = now

        // Reels tab and Search/Explore tab are full of reels: send to Home.
        // (Only acts when that tab is the selected one, so the normal Home feed is left alone.)
        if (s.reelsTabSelected || s.searchTabSelected) {
            friendReelActive = false
            leave(s)
            return
        }

        // Home feed: scroll past any reel that shows up, so only posts are left.
        // (Skipped when the feed is locked, because the lock holds it at the top.)
        if (!lockFeed && s.homeSelected && s.reelRects.isNotEmpty()) {
            skipReel(s.reelRects.maxByOrNull { it.height() }!!)
            return
        }

        // A full-screen reel viewer has no bottom bar
        val barVisible = s.homeTab != null
        val inViewer = !barVisible && s.ids.any { it.startsWith("clips_viewer") }
        val commentsOpen = s.ids.any { it.contains("comment") }

        if (!inViewer) {
            friendReelActive = false
            return
        }

        if (!friendReelActive) {
            if (now - lastDmSeen < 4000) {
                // Opened from a chat: friend-sent reel, allow it
                friendReelActive = true
                friendReelStart = now
            } else {
                // Reel opened from a profile, etc.: block
                back()
            }
            return
        }

        // Friend reel playing. Swiping to another reel sends you back to the chat.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            !commentsOpen && now - friendReelStart > 1500
        ) {
            friendReelActive = false
            back()
        }
    }

    // ---------- Helpers ----------
    private fun isScrollDown(e: AccessibilityEvent): Boolean {
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false
        return if (Build.VERSION.SDK_INT >= 28) e.scrollDeltaY > 0 else e.scrollY > 0
    }

    /** Pushes the Home feed back to the very top. */
    private fun snapToTop(s: Scan) {
        val now = System.currentTimeMillis()
        if (now - lastSnap < 120) return
        lastSnap = now
        val list = s.feedList ?: return
        repeat(4) { list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
    }

    /** Swipes the feed up just far enough to push the reel off the top of the screen. */
    private fun skipReel(reel: Rect) {
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels
        if (reel.top > h * 0.9f) return // not really on screen yet
        val dy = (reel.bottom - (h * 0.12f).toInt()).coerceAtMost((h * 0.75f).toInt())
        if (dy < 50) return
        if (!throttle()) return
        val startY = h * 0.88f
        val path = Path().apply {
            moveTo(w * 0.5f, startY)
            lineTo(w * 0.5f, startY - dy)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Tap the Home tab if it's visible and not already selected; otherwise press Back. */
    private fun leave(s: Scan) {
        val tab = s.homeTab
        if (tab != null) {
            if (!s.homeSelected && throttle()) click(tab)
            return
        }
        back()
    }

    private fun back() {
        if (!throttle()) return
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Spacing between actions, plus a safety valve: too many actions in a row pauses the blocker briefly. */
    private fun throttle(): Boolean {
        val now = System.currentTimeMillis()
        if (now < pausedUntil) return false
        if (now - lastAction < 900) return false
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 6000) recent.removeFirst()
        if (recent.size >= 5) {
            pausedUntil = now + 10000
            recent.clear()
            return false
        }
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
        var feedBest = 0

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

            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                if (r.height() > screenH * 0.4f && r.height() > feedBest) {
                    feedBest = r.height()
                    s.feedList = n
                }
            }

            if (desc.startsWith("Reel by", true)) {
                n.getBoundsInScreen(r)
                if (r.height() > screenH * 0.15f) s.reelRects.add(Rect(r))
            }

            val isHome = id == "feed_tab" || desc.startsWith("Home", true) || text.equals("Home", true)
            val isReels = id == "clips_tab" || desc.startsWith("Reels", true)
            val isSearch = id == "search_tab" || desc.startsWith("Search and explore", true)
            if (isHome || isReels || isSearch) {
                n.getBoundsInScreen(r)
                if (r.top > screenH * 0.75) {
                    val sel = n.isSelected || n.parent?.isSelected == true
                    if (isHome && s.homeTab == null) {
                        s.homeTab = n
                        s.homeSelected = sel
                    }
                    if (isReels && sel) s.reelsTabSelected = true
                    if (isSearch && sel) s.searchTabSelected = true
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }

        walk(root, 0)
        return s
    }
}
