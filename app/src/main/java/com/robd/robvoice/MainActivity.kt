package com.robd.robvoice

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Setup + settings + history. Plain framework widgets, no extra libraries. */
class MainActivity : Activity() {
    companion object { @Volatile var visible = false }
    private lateinit var col: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var modelStatus: TextView
    private var tryBar: TalkBar? = null
    private lateinit var floatStatus: TextView
    private lateinit var floatBtn: Button
    private lateinit var diag: TextView
    @Volatile private var downloading = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(col) })
        TalkBar.padForNavBar(col, pad)
        build()
    }

    override fun onResume() { super.onResume(); visible = true; refresh() }
    override fun onPause() { visible = false; tryBar?.cancel(); super.onPause() }

    private fun h(t: String) = col.addView(TextView(this).apply { text = t; textSize = 18f; setPadding(0, 28, 0, 6); setTypeface(typeface, android.graphics.Typeface.BOLD) })
    private fun p(t: String) = TextView(this).apply { text = t; textSize = 14f }.also { col.addView(it) }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }.also { col.addView(it) }
    private fun sw(t: String, key: String, def: Boolean) = col.addView(Switch(this).apply { text = t; isChecked = Prefs.get(context).getBoolean(key, def)
        setOnCheckedChangeListener { _, v -> Prefs.get(context).edit().putBoolean(key, v).apply() } })

    private fun build() {
        p("NoCloud Voice: hold-to-talk dictation that runs entirely on this phone. Audio is never uploaded; the internet is only used once to download the speech model.")
        h("1. Microphone")
        btn("Grant microphone permission") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        h("2. Speech model (one-time download, then offline)")
        val group = RadioGroup(this)
        Models.ALL.forEachIndexed { i, m -> group.addView(RadioButton(this).apply { id = 1000 + i; text = "${m.label}\n~${m.approxMb} MB, license ${m.license}"; isChecked = Models.selected(context).id == m.id }) }
        group.setOnCheckedChangeListener { _, id -> Prefs.get(this).edit().putString("model", Models.ALL[id - 1000].id).apply(); refresh() }
        col.addView(group)
        modelStatus = p("")
        btn("Download selected model (use Wi-Fi)") { download() }
        btn("Delete model and re-download") { if (downloading) toast("Download already running") else { Models.delete(this, Models.selected(this)); refresh(); download() } }
        h("3. Turn on the keyboard")
        p("Enable \"NoCloud Voice\" in the list, then pick it. Tip: in Gboard you can keep Gboard as main keyboard and switch to NoCloud Voice with the keyboard-switch button.")
        btn("Open keyboard settings (enable NoCloud Voice)") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btn("Switch keyboard now") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        h("4. Floating button (no keyboard switching)")
        p("A small round bubble floats over every app. Tap it once to start talking - no holding. Tap the green check (or the waveform) when done, and the words are typed into the text box you're in. Tap X to throw it away. Drag the bubble anywhere; it remembers the spot.")
        floatStatus = p("")
        btn("a) Allow notifications") { if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) else toast("Not needed on this Android version") }
        btn("b) Allow \"Display over other apps\"") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
        p("c) Allow restricted settings (needed once because NoCloud Voice wasn't installed from the Play Store): tap the button below, tap the three dots at the top right, then \"Allow restricted settings\" and confirm with your fingerprint/PIN. If you don't see that option, do step d first, get the \"Restricted setting\" pop-up, then come back here.")
        btn("c) Open NoCloud Voice App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        btn("d) Turn on accessibility: \"NoCloud Voice floating button\"") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        p("Accessibility is what lets the bubble type into other apps (it's the same method other floating dictation apps use). Without it, the words are copied to the clipboard instead and you paste them.")
        p("Seeing TWO mic bubbles? The faded one is Android's own accessibility shortcut button, not NoCloud Voice. To remove it: Settings > Accessibility > NoCloud Voice floating button > turn OFF \"NoCloud Voice floating button shortcut\" (keep the main switch ON). The NoCloud Voice bubble is the dark blue one with a white ring.")
        floatBtn = btn("") { toggleFloat() }
        sw("Hide the bubble when I'm not typing in a text box", "float_only_typing", false)
        sw("Show live words while I talk (preview; final text is re-checked when you tap the check)", "live_words", true)
        diag = p("")
        p("Test insert: tap this box, then tap the floating bubble, say something, tap the check. The words should appear here by themselves, and the line above says which method worked.")
        col.addView(EditText(this).apply { hint = "Test insert box"; minLines = 2 })
        btn("Refresh status") { refresh() }
        h("Try it (hold-to-talk test)")
        p("Test right here, no keyboard switch needed: hold the bar below, talk, release.")
        val tryBox = EditText(this).apply { hint = "Your words appear here"; minLines = 3; showSoftInputOnFocus = false }
        col.addView(tryBox)
        tryBar = TalkBar(this, onText = { t -> tryBox.text.append((if (tryBox.text.isNotEmpty()) " " else "") + t); refresh() },
            onNeedSetup = { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) else download() },
            appPkg = { packageName }).apply { setBackgroundColor(android.graphics.Color.parseColor("#111418")); setPadding(16, 8, 16, 16) }
        col.addView(tryBar)
        h("Cleanup (all local)")
        sw("Remove filler words (um, uh, repeated words)", "fillers", true)
        sw("Voice commands (\"new line\", \"new paragraph\", \"question mark\")", "commands", true)
        sw("Salon mode: shades like \"seven oh one\" -> 7/01, \"one to one\" -> 1:1", "salon", false)
        h("Custom dictionary")
        p("One per line:  heard => written")
        val dict = EditText(this).apply { setText(Prefs.get(context).getString("dict", PostProcessor.DEFAULT_DICT)); minLines = 6; textSize = 13f }
        col.addView(dict)
        btn("Save dictionary") { Prefs.get(this).edit().putString("dict", dict.text.toString()).apply(); toast("Saved") }
        h("History (tap to copy)")
        btn("Clear history") { History.clear(this); refresh() }
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; col.addView(historyBox)
    }

    private fun refresh() {
        if (!::modelStatus.isInitialized) return
        tryBar?.refresh()
        val m = Models.selected(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!downloading) modelStatus.text = when (Models.state(this, m)) { "ready" -> "Model ready (offline, verified)."; "incomplete" -> "Model incomplete - tap Download to finish, or Delete model and re-download."; else -> "Model not downloaded yet." } + if (mic) "  Mic: granted." else "  Mic: NOT granted."
        val ok = { b: Boolean -> if (b) "\u2705" else "\u274C" }
        val notif = android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val a11y = DictateA11y.connected && DictateA11y.instance != null && DictateA11y.isEnabled(this)
        floatStatus.text = "${ok(mic)} Microphone\n${ok(Models.isReady(this, m))} Speech model\n${ok(notif)} Notifications\n${ok(Settings.canDrawOverlays(this))} Display over other apps\n${ok(a11y)} Accessibility (types into other apps)\n${ok(FloatService.running)} Floating button running"
        floatStatus.setTextColor(if (mic && notif && a11y && Settings.canDrawOverlays(this) && FloatService.running) android.graphics.Color.parseColor("#1B7F3B") else android.graphics.Color.DKGRAY)
        diag.text = "Accessibility: " + (if (a11y) "CONNECTED" else if (DictateA11y.isEnabled(this)) "listed as on but NOT connected - turn \"Use NoCloud Voice floating button\" off and on again" else "NOT connected - tap step d") +
            "\n" + (Prefs.get(this).getString("last_report", null) ?: DictateA11y.lastReport) + (Prefs.get(this).getString("last_timing", null)?.let { "\n$it" } ?: "")
        diag.setTextColor(if (a11y) android.graphics.Color.parseColor("#1B7F3B") else android.graphics.Color.parseColor("#C62828"))
        floatBtn.text = if (FloatService.running) "Stop floating button" else "Start floating button"
        historyBox.removeAllViews()
        val arr = History.all(this); val fmt = SimpleDateFormat("MMM d h:mm a", Locale.US)
        for (i in 0 until minOf(arr.length(), 100)) { val o = arr.getJSONObject(i); val txt = o.getString("text")
            historyBox.addView(TextView(this).apply { text = "${fmt.format(Date(o.getLong("t")))}  ${o.optString("app")}  ${o.optLong("ms")} ms\n$txt"; setPadding(0, 12, 0, 12); textSize = 14f
                setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NoCloud Voice", txt)); toast("Copied") } }) }
        if (arr.length() == 0) historyBox.addView(TextView(this).apply { text = "Nothing yet." })
    }

    private fun download() {
        if (downloading) { toast("Download already running"); return }
        val m = Models.selected(this); modelStatus.text = "Starting download..."; downloading = true
        thread {
            try { Models.download(this, m) { f, pct -> runOnUiThread { modelStatus.text = (if (f.startsWith("checking")) "Verifying ${f.removePrefix("checking ")}" else "Downloading $f") + ": $pct%  (keep the app open)" } }
                runOnUiThread { modelStatus.text = "Loading model..." }
                val t0 = System.currentTimeMillis(); Asr.get(this)
                runOnUiThread { downloading = false; toast("Model ready"); refresh(); modelStatus.append("  Loaded in ${System.currentTimeMillis() - t0} ms.") }
            } catch (e: Throwable) { runOnUiThread { downloading = false; refresh(); modelStatus.text = "Problem: ${e.message}"; tryBar?.refresh() } }
        }
    }

    private fun toggleFloat() {
        if (FloatService.running) { Prefs.get(this).edit().putBoolean("float_on", false).apply(); FloatService.stop(this); col.postDelayed({ refresh() }, 300); return }
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
            !Settings.canDrawOverlays(this) -> { toast("First allow Display over other apps"); startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))); return }
        }
        Prefs.get(this).edit().putBoolean("float_on", true).apply(); FloatService.start(this)
        col.postDelayed({ refresh(); if (!FloatService.running) toast("Couldn't start the bubble - check the list above") }, 600)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(c, p, r); refresh() }
}
