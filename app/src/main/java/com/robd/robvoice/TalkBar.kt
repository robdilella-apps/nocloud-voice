package com.robd.robvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Shared hold-to-talk bar (used by the keyboard and the in-app "Try it" box).
 * Always visible: if mic permission or model is missing it turns orange and a tap runs [onNeedSetup].
 * Fixed high-contrast colors so it looks the same in light and dark mode.
 */
class TalkBar(ctx: Context, private val onText: (String) -> Unit, private val onNeedSetup: () -> Unit, private val appPkg: () -> String? = { null }) : LinearLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val rec = Recorder()
    private var pipe: Pipeline? = null
    private val dp = resources.displayMetrics.density
    val status = TextView(ctx)
    private val bar = TextView(ctx)
    private val bg = GradientDrawable().apply { cornerRadius = 20 * dp }
    private var downAt = 0L
    private var latched = false
    private var busy = false

    init {
        orientation = VERTICAL
        status.apply { gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.WHITE); setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt()) }
        bar.apply { gravity = Gravity.CENTER; textSize = 26f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Color.WHITE); background = bg
            isClickable = true; contentDescription = "Hold to talk"; setOnTouchListener { v, e -> touch(v, e); true } }
        addView(status, LayoutParams(-1, -2))
        addView(bar, LayoutParams(-1, (150 * dp).toInt()))
        refresh()
    }

    fun ready(): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && Models.isReady(context, Models.selected(context))

    fun refresh() {
        if (busy || rec.isRunning) return
        when {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> idle("MIC PERMISSION MISSING", "TAP TO SET UP", "#E65100")
            !Models.isReady(context, Models.selected(context)) -> idle(if (Models.state(context, Models.selected(context)) == "incomplete") "MODEL INCOMPLETE - tap to re-download" else "SPEECH MODEL NOT DOWNLOADED", "TAP TO SET UP", "#E65100")
            else -> idle("READY - on-device. Hold, or tap once for hands-free", "HOLD TO TALK", "#1565C0")
        }
    }
    private fun idle(s: String, b: String, c: String) { status.text = s; bar.text = b; bg.setColor(Color.parseColor(c)) }

    private fun touch(v: View, e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (busy) return
                if (latched) { latched = false; finish(); return }
                if (!ready()) { refresh(); onNeedSetup(); return }
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                downAt = SystemClock.uptimeMillis(); rec.start(); pipe?.dead = true; pipe = Pipeline(context, rec, worker)
                bar.text = "LISTENING... RELEASE TO TYPE"; bg.setColor(Color.parseColor("#C62828")); pulse()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!rec.isRunning || latched) return
                if (SystemClock.uptimeMillis() - downAt < 350) { latched = true; bar.text = "HANDS-FREE - TAP TO STOP"; return }
                finish()
            }
        }
    }

    private fun pulse() { if (!rec.isRunning) return; pipe?.poll(); status.text = "Listening " + "|".repeat((rec.level * 30).toInt().coerceIn(1, 20)); ui.postDelayed({ pulse() }, 120) }

    fun cancel() { pipe?.dead = true; pipe = null; if (rec.isRunning) rec.stop(); latched = false; busy = false; refresh() }

    private fun finish() {
        val tTap = SystemClock.uptimeMillis()
        val p = pipe ?: Pipeline(context, rec, worker); pipe = null
        val rest = if (p.seconds < 0.7f) { rec.stop(); null } else p.stopAndRemainder(); busy = true
        bar.text = "TRANSCRIBING..."; bg.setColor(Color.parseColor("#455A64")); status.text = "Transcribing on-device..."
        val pkg = appPkg()
        worker.execute {
            val r: Any = try { if (rest == null) "" else { val raw = p.finishOnWorker(rest); raw to PostProcessor.process(context, raw) } } catch (t: Throwable) { t }
            val ms = SystemClock.uptimeMillis() - tTap
            ui.post {
                busy = false; refresh()
                when (r) {
                    is Pair<*, *> -> { val txt = r.second as String
                        if (txt.isNotEmpty()) { onText(txt); val raw = r.first as String; worker.execute { try { History.add(context, raw, txt, pkg, ms) } catch (_: Throwable) {} }; status.text = "Typed in $ms ms (on-device)" } else status.text = "Didn't catch that" }
                    is Throwable -> { refresh(); status.text = "Error: ${r.message}" }
                    else -> status.text = "Too short - hold a bit longer"
                }
            }
        }
    }

    companion object {
        val worker = Executors.newSingleThreadExecutor()
        /** Pad a view by the nav-bar / gesture-bar height so nothing hides under it (edge-to-edge on Android 15+). */
        fun padForNavBar(v: View, base: Int) {
            v.setOnApplyWindowInsetsListener { view, ins ->
                val b = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.navigationBars()).bottom else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
                view.setPadding(base, base, base, base + b); ins }
            v.requestApplyInsets()
        }
    }
}
