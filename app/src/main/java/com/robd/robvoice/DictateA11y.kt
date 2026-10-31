package com.robd.robvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches which text field has focus (so the floating button can light up) and types dictated text into it.
 * Only touches the focused text field, and only when you tap the check mark.
 */
class DictateA11y : AccessibilityService() {
    companion object {
        @Volatile var instance: DictateA11y? = null
        @Volatile var connected = false; private set
        @Volatile var editFocused = false; private set
        var onFocusChange: ((Boolean) -> Unit)? = null
        /** Plain-words record of the last insert attempt, shown in the app. */
        @Volatile var lastReport = "No dictation inserted yet."
        fun isEnabled(c: Context): Boolean {
            val cn = ComponentName(c, DictateA11y::class.java)
            val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
        }
        fun report(c: Context, s: String) { lastReport = s; Prefs.get(c).edit().putString("last_report", s).apply() }
    }
    private val ui = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastNode: AccessibilityNodeInfo? = null   // last editable node seen in another app
    private var lastNodeAt = 0L

    override fun onServiceConnected() {
        instance = this; connected = true
        // Make sure the runtime config matches what we need, whatever the XML said.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            packageNames = null; notificationTimeout = 50
        }
        try { FloatService.start(this) } catch (_: Throwable) {}
    }
    override fun onUnbind(i: Intent?): Boolean { connected = false; instance = null; setFocused(false); return super.onUnbind(i) }
    override fun onDestroy() { connected = false; instance = null; setFocused(false); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString()
        if (pkg == packageName && !MainActivity.visible) return // ignore our own bubble
        val src = try { e.source } catch (_: Throwable) { null }
        if (src != null && src.isEditable && (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)) {
            lastNode = src; lastNodeAt = System.currentTimeMillis(); lastPkg = pkg
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) { if (pkg != null && pkg != "com.android.systemui") lastPkg = pkg; setFocused(findTarget(false) != null) }
    }
    private fun setFocused(f: Boolean) { if (f != editFocused) { editFocused = f; onFocusChange?.invoke(f) } }
    fun focusedPkg(): String? = lastPkg

    private fun ok(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? = n?.takeIf { it.isEditable && (it.packageName?.toString() != packageName || MainActivity.visible) }

    /** Focused editable node: input focus -> accessibility focus -> walk windows -> cached last editable. */
    fun findTarget(useCache: Boolean = true): AccessibilityNodeInfo? {
        ok(try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null })?.let { return it }
        ok(try { findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) } catch (_: Throwable) { null })?.let { return it }
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { roots += it } } } catch (_: Throwable) {}
        rootInActiveWindow?.let { roots.add(0, it) }
        for (r in roots) { walk(r, 0)?.let { return it } }
        if (useCache) lastNode?.let { n -> if (System.currentTimeMillis() - lastNodeAt < 10 * 60_000 && n.refresh() && n.isEditable) return n }
        return null
    }
    private fun walk(n: AccessibilityNodeInfo, d: Int): AccessibilityNodeInfo? {
        if (d > 40) return null
        if (n.isEditable && n.isFocused) return ok(n)
        for (i in 0 until n.childCount) { val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue; walk(c, d + 1)?.let { return it } }
        return null
    }

    /** Returns "typed", "pasted", "pasted-retry" or "copied". Text is always also left on the clipboard. */
    fun insert(text: String, done: (String) -> Unit) {
        val n = findTarget()
        val pkg = n?.packageName?.toString() ?: lastPkg ?: "?"
        if (n == null) { clip(text); report(this, "Last target: $pkg. Text box found: NO. Method: none - copied to clipboard (tap the text box first, then the bubble)."); done("copied"); return }
        val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        var s = n.textSelectionStart; var en = n.textSelectionEnd
        if (s < 0 || en < 0 || s > cur.length || en > cur.length) { s = cur.length; en = cur.length }
        if (s > en) { val t = s; s = en; en = t }
        val before = cur.substring(0, s); val after = cur.substring(en)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace() && text.firstOrNull()?.isLetterOrDigit() == true) " " else ""
        val ins = lead + text; val merged = before + ins + after
        clip(ins)
        val setOk = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged) })
        n.refresh()
        val now = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        if (setOk && (now == merged || now.contains(ins.trim()))) {
            val c = before.length + ins.length
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, c); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, c) })
            report(this, "Last target: $pkg. Text box found: yes. Method: typed directly (set text) - worked."); done("typed"); return
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            report(this, "Last target: $pkg. Text box found: yes. Method: paste (set text ${if (setOk) "was ignored" else "refused"})."); done("pasted"); return
        }
        ui.postDelayed({
            val m = findTarget() ?: n
            m.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val p = m.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            report(this, "Last target: $pkg. Text box found: yes. Method: " + if (p) "paste after retry - worked." else "set text and paste both refused by this app - left on clipboard, long-press to paste.")
            done(if (p) "pasted-retry" else "copied")
        }, 250)
    }
    private fun clip(t: String) = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NoCloud Voice", t))
}
