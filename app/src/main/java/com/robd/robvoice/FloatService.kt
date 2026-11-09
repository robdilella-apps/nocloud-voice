package com.robd.robvoice

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Floating dictation pill. Idle: small draggable bubble. Tap = start recording (no holding).
 * While recording: [X cancel] [live waveform] [check done]; tapping the waveform also finishes.
 * Window is FLAG_NOT_FOCUSABLE so the target app's text field keeps focus.
 */
class FloatService : Service() {
    companion object {
        const val STOP = "com.robd.robvoice.STOP"
        @Volatile var running = false; private set
        /** The one and only bubble window in this process. Any older one is removed before a new one is added. */
        private var attached: View? = null
        private var attachedWm: WindowManager? = null
        private var attachedCard: View? = null
        private fun detach() {
            attached?.let { v -> try { attachedWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
            attachedCard?.let { v -> try { attachedWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
            attached = null; attachedCard = null; attachedWm = null }
        fun start(c: Context) {
            if (!Prefs.get(c).getBoolean("float_on", false)) return
            if (!Settings.canDrawOverlays(c) || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            if (running) return // already showing: never start a second bubble
            try { c.startForegroundService(Intent(c, FloatService::class.java)) } catch (_: Throwable) {}
        }
        fun stop(c: Context) = c.stopService(Intent(c, FloatService::class.java))
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var idle: View
    private lateinit var active: LinearLayout
    private lateinit var wave: Wave
    private lateinit var checkV: View
    private lateinit var xV: View
    private lateinit var spin: android.widget.ProgressBar
    private lateinit var card: TextView
    private lateinit var cardLp: WindowManager.LayoutParams
    @Volatile private var gen = 0          // bumps on every start/finish/cancel so stale preview results are dropped
    @Volatile private var previewBusy = false
    @Volatile private var previewMs = 0L
    private val rec = Recorder()
    private var pipe: Pipeline? = null
    private var busy = false
    private var failed = false
    private val dp get() = resources.displayMetrics.density
    private val BLUE = Color.parseColor("#1E5AA8")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("float", "Floating button", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 1, Intent(this, FloatService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "float").setSmallIcon(R.drawable.ic_mic).setContentTitle("NoCloud Voice floating button is on")
            .setContentText("Tap the bubble to dictate. Audio stays on the phone.").setOngoing(true).setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build()).build()
        try { if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(7, n) }
        catch (t: Throwable) { failed = true; stopSelf(); return } // Android blocks mic services started from the background: open the app and tap Start.
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        TalkBar.worker.execute { try { Asr.get(this) } catch (_: Throwable) {} } // load the model once, keep it warm
        DictateA11y.onFocusChange = { ui.post { applyIdleLook() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == STOP) { Prefs.get(this).edit().putBoolean("float_on", false).apply(); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false; DictateA11y.onFocusChange = null
        pipe?.dead = true; pipe = null
        if (rec.isRunning) rec.stop()
        if (::root.isInitialized) { if (attached === root) detach() else try { wm.removeViewImmediate(root) } catch (_: Throwable) {} }
        super.onDestroy()
    }

    private fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    private fun buildViews() {
        root = FrameLayout(this)
        idle = Glyph(this, Glyph.MIC).apply { background = GradientDrawable().apply { cornerRadius = 999f; setColor(BLUE); setStroke((2 * dp).toInt(), Color.WHITE) }
            contentDescription = "Start dictation" }
        root.addView(idle, FrameLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        active = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.parseColor("#F2121820")) }; visibility = View.GONE }
        val s = (36 * dp).toInt()
        xV = Glyph(this, Glyph.X).apply { background = circle(Color.parseColor("#3A4350")); contentDescription = "Cancel"; setOnClickListener { cancel() } }
        active.addView(xV, LinearLayout.LayoutParams(s, s))
        wave = Wave(this).apply { setOnClickListener { finish() } }
        active.addView(wave, LinearLayout.LayoutParams((110 * dp).toInt(), s).apply { leftMargin = (8 * dp).toInt(); rightMargin = (8 * dp).toInt() })
        checkV = Glyph(this, Glyph.CHECK).apply { background = circle(Color.parseColor("#14A37F")); contentDescription = "Done, type the text"; setOnClickListener { finish() } }
        active.addView(checkV, LinearLayout.LayoutParams(s, s))
        spin = android.widget.ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = circle(Color.parseColor("#0E7A5F")); setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt()); visibility = View.GONE; contentDescription = "Transcribing" }
        active.addView(spin, LinearLayout.LayoutParams(s, s))
        root.addView(active, FrameLayout.LayoutParams(-2, -2))
        idle.setOnTouchListener(Drag())

        lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        placeFromPrefs()
        // Preview / status card: separate window that can't be touched or focused, so taps go straight to the app below.
        card = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 3; setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor("#E6121820")) }; visibility = View.GONE }
        cardLp = WindowManager.LayoutParams((280 * dp).toInt(), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        detach()
        wm.addView(root, lp); attached = root; attachedWm = wm
        wm.addView(card, cardLp); attachedCard = card
        applyIdleLook()
    }

    /** Position stored as a fraction of the screen so it survives rotation. */
    private fun placeFromPrefs() {
        val m = resources.displayMetrics; val p = Prefs.get(this)
        lp.x = (p.getFloat("fx", 0.92f) * m.widthPixels).toInt().coerceIn(0, m.widthPixels - (44 * dp).toInt())
        lp.y = (p.getFloat("fy", 0.45f) * m.heightPixels).toInt().coerceIn(0, m.heightPixels - (44 * dp).toInt())
    }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); if (::root.isInitialized) { placeFromPrefs(); clampForWidth(); try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {} } }

    private fun clampForWidth() { val w = resources.displayMetrics.widthPixels; root.measure(0, 0); lp.x = lp.x.coerceIn(0, maxOf(0, w - root.measuredWidth)) }

    private fun applyIdleLook() {
        if (rec.isRunning || busy) { root.visibility = View.VISIBLE; root.alpha = 1f; return }
        val a11y = DictateA11y.instance != null
        val onlyTyping = Prefs.get(this).getBoolean("float_only_typing", false)
        val focused = DictateA11y.editFocused
        root.visibility = if (onlyTyping && a11y && !focused) View.GONE else View.VISIBLE
        root.alpha = if (!a11y || focused) 1f else 0.45f
        idle.scaleX = if (!a11y || focused) 1f else 0.8f; idle.scaleY = idle.scaleX
    }

    private inner class Drag : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> { val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (moved || Math.hypot(dx.toDouble(), dy.toDouble()) > 12 * dp) { moved = true; lp.x = (ox + dx).toInt(); lp.y = (oy + dy).toInt(); wm.updateViewLayout(root, lp) } }
                MotionEvent.ACTION_UP -> {
                    if (moved) { val m = resources.displayMetrics
                        Prefs.get(this@FloatService).edit().putFloat("fx", lp.x.toFloat() / m.widthPixels).putFloat("fy", lp.y.toFloat() / m.heightPixels).apply() }
                    else { v.performClick(); begin() }
                }
            }
            return true
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showCard(t: String?) {
        if (t == null) { card.visibility = View.GONE; return }
        card.text = t; card.visibility = View.VISIBLE
        val m = resources.displayMetrics; root.measure(0, 0); card.measure(View.MeasureSpec.makeMeasureSpec(cardLp.width, View.MeasureSpec.EXACTLY), 0)
        cardLp.x = (lp.x + root.measuredWidth - cardLp.width).coerceIn(0, maxOf(0, m.widthPixels - cardLp.width))
        val above = lp.y - card.measuredHeight - (8 * dp).toInt()
        cardLp.y = if (above > (40 * dp)) above else lp.y + root.measuredHeight + (8 * dp).toInt()
        try { wm.updateViewLayout(card, cardLp) } catch (_: Throwable) {}
    }
    private fun tail(t: String, max: Int = 110) = if (t.length <= max) t else "\u2026" + t.substring(t.length - max).substringAfter(' ')

    private fun begin() {
        if (busy) return
        if (rec.isRunning) { finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { toast("NoCloud Voice needs the microphone - open the app"); return }
        if (!Models.isReady(this, Models.selected(this))) { toast("Speech model not downloaded - open NoCloud Voice"); return }
        idle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        try { rec.start() } catch (t: Throwable) { toast("Mic busy: ${t.message}"); return }
        gen++; pipe?.dead = true; pipe = Pipeline(this, rec, TalkBar.worker)
        checkV.visibility = View.VISIBLE; spin.visibility = View.GONE; xV.isEnabled = true; xV.alpha = 1f; wave.isEnabled = true
        idle.visibility = View.GONE; active.visibility = View.VISIBLE; wave.reset(); applyIdleLook(); relayout(); tick()
        if (Prefs.get(this).getBoolean("live_words", true)) { showCard("Listening\u2026"); val g = gen; ui.postDelayed({ preview(g) }, 1200) } else showCard(null)
        TalkBar.worker.execute { try { Asr.get(this) } catch (_: Throwable) {} } // make sure the model is warm
    }

    /** Approximate live words: re-decode the last ~8 s with the same on-device model, at most one pass at a time, about every 1.2 s. */
    private fun preview(g: Int) {
        if (g != gen || !rec.isRunning) return
        if (!previewBusy) {
            previewBusy = true
            val (snap, cut) = rec.snapshot(8f)
            TalkBar.worker.execute {
                val t0 = SystemClock.uptimeMillis()
                val txt = try { if (g != gen || snap.size < 8000) null else Asr.transcribe(this, snap) } catch (_: Throwable) { null }
                previewMs = SystemClock.uptimeMillis() - t0; previewBusy = false
                ui.post { if (g == gen && rec.isRunning && !txt.isNullOrBlank()) showCard(tail(if (cut) "\u2026" + txt.substringAfter(' ') else txt)) }
            }
        }
        ui.postDelayed({ preview(g) }, maxOf(1500L, previewMs * 3)) // back off on slow phones/models to save battery and leave CPU for the real decode
    }

    private fun relayout() { clampForWidth(); try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {} }
    private fun tick() { if (!rec.isRunning) return; wave.push(rec.level); pipe?.poll(); ui.postDelayed({ tick() }, 60) }
    private fun showIdle() { active.visibility = View.GONE; idle.visibility = View.VISIBLE; busy = false; placeFromPrefs(); relayout(); applyIdleLook() }

    private fun cancel() { if (busy) return; gen++; pipe?.dead = true; pipe = null; if (rec.isRunning) rec.stop(); showCard(null); showIdle() }

    /** Result flash on the card, then back to idle. */
    private fun done(msg: String, ok: Boolean) {
        card.background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor(if (ok) "#E60E7A5F" else "#E6455A64")) }
        showCard(msg); wave.thinking = false; showIdle()
        ui.postDelayed({ card.background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor("#E6121820")) }; if (!rec.isRunning && !busy) showCard(null) }, if (ok) 1100 else 2200)
    }

    private fun finish() {
        if (!rec.isRunning || busy) return
        // 1) Instant feedback, same frame, before any heavy work.
        busy = true; gen++
        checkV.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        checkV.visibility = View.GONE; spin.visibility = View.VISIBLE; xV.isEnabled = false; xV.alpha = 0.35f; wave.isEnabled = false
        wave.thinking = true; wave.invalidate()
        val shown = card.text?.toString()?.takeIf { card.visibility == View.VISIBLE && !it.startsWith("Listening") }
        showCard("Transcribing\u2026" + (shown?.let { "\n$it" } ?: ""))
        // 2) Stop the mic and transcribe off the main thread.
        val pkg = DictateA11y.instance?.focusedPkg()
        val tTap = SystemClock.uptimeMillis()
        ui.post {
            val p = pipe ?: Pipeline(this, rec, TalkBar.worker); pipe = null
            val secs = p.seconds; val rest = p.stopAndRemainder()
            TalkBar.worker.execute {
                val t0 = SystemClock.uptimeMillis()
                val r: Any = try { if (rest == null) "" else { val raw = p.finishOnWorker(rest); raw to PostProcessor.process(this, raw) } } catch (t: Throwable) { t }
                val t1 = SystemClock.uptimeMillis(); val ms = t1 - tTap
                Prefs.get(this).edit().putString("last_timing", "Last dictation: %.1f s recorded, %.1f s decoded after you tapped done. Text ready %d ms after the tap (%d ms decoding, %d ms waiting for earlier work).".format(secs, (rest?.size ?: 0) / 16000f, ms, t1 - t0, t0 - tTap)).apply()
                ui.post {
                    when (r) {
                        is Pair<*, *> -> { val txt = r.second as String
                            if (txt.isEmpty()) done("Didn't catch that", false) else {
                                TalkBar.worker.execute { try { History.add(this, r.first as String, txt, pkg, ms) } catch (_: Throwable) {} } // after the text is in, off the UI thread
                                val a = DictateA11y.instance
                                if (a == null || !DictateA11y.connected) {
                                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("NoCloud Voice", txt))
                                    DictateA11y.report(this, "Accessibility is NOT connected, so the text was only copied to the clipboard.")
                                    done("Copied - accessibility is off", false)
                                    Toast.makeText(this, "Accessibility is off - opening settings. Turn on \"NoCloud Voice floating button\". (Text is on the clipboard.)", Toast.LENGTH_LONG).show()
                                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                                } else a.insert(txt) { how -> if (how == "copied") done("Copied - long-press to paste", false) else done("\u2713 Inserted", true) }
                            } }
                        is Throwable -> done("Error: ${r.message}", false)
                        else -> done("Too short", false)
                    }
                }
            }
        }
    }

    /** Original line-art icons drawn in code. */
    class Glyph(c: Context, private val kind: Int) : View(c) {
        companion object { const val MIC = 0; const val X = 1; const val CHECK = 2 }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        init { isClickable = true }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val u = minOf(w, h); val cx = w / 2; val cy = h / 2
            p.strokeWidth = u * 0.09f
            when (kind) {
                X -> { val d = u * 0.18f; cv.drawLine(cx - d, cy - d, cx + d, cy + d, p); cv.drawLine(cx + d, cy - d, cx - d, cy + d, p) }
                CHECK -> { val pa = Path().apply { moveTo(cx - u * 0.2f, cy + u * 0.01f); lineTo(cx - u * 0.05f, cy + u * 0.16f); lineTo(cx + u * 0.22f, cy - u * 0.14f) }; cv.drawPath(pa, p) }
                else -> { p.style = Paint.Style.FILL; cv.drawRoundRect(cx - u * 0.1f, cy - u * 0.25f, cx + u * 0.1f, cy + u * 0.08f, u * 0.1f, u * 0.1f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.06f
                    cv.drawArc(cx - u * 0.18f, cy - u * 0.12f, cx + u * 0.18f, cy + u * 0.17f, 0f, 180f, false, p); cv.drawLine(cx, cy + u * 0.17f, cx, cy + u * 0.26f, p) }
            }
        }
    }

    /** Live level bars (scrolling). Shows a sweeping dot pattern while transcribing. */
    class Wave(c: Context) : View(c) {
        private val n = 22; private val lv = FloatArray(n); private var k = 0; var thinking = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7FC8FF"); strokeCap = Paint.Cap.ROUND }
        init { isClickable = true; contentDescription = "Recording - tap to finish" }
        fun reset() { lv.fill(0f); k = 0; invalidate() }
        fun push(l: Float) { lv[k % n] = minOf(1f, Math.sqrt(l.toDouble()).toFloat() * 1.4f); k++; invalidate() }
        override fun onDraw(cv: Canvas) {
            val step = width.toFloat() / n; p.strokeWidth = step * 0.55f; val mid = height / 2f
            for (i in 0 until n) { val v = if (thinking) 0.15f + 0.15f * ((i + (SystemClock.uptimeMillis() / 80).toInt()) % 6) / 5f else lv[(k + i) % n]
                val hh = maxOf(p.strokeWidth / 2, v * height * 0.45f); val x = step * (i + 0.5f); cv.drawLine(x, mid - hh, x, mid + hh, p) }
            if (thinking) postInvalidateDelayed(80)
        }
    }
}
