package com.robd.robvoice

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout

/** NoCloud Voice keyboard: a tall full-width HOLD TO TALK bar + small edit row. Text goes in via commitText (works in any app). */
class RobVoiceIme : InputMethodService() {
    private val ui = Handler(Looper.getMainLooper())
    private var talk: TalkBar? = null
    private var lastInserted = ""

    override fun onCreate() { super.onCreate(); TalkBar.worker.execute { try { Asr.get(this) } catch (_: Throwable) {} } }

    // Never use fullscreen/extract mode (it can hide our view in landscape) and always show the view.
    override fun onEvaluateFullscreenMode() = false
    override fun onEvaluateInputViewShown(): Boolean { super.onEvaluateInputViewShown(); return true }

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density; val pad = (8 * dp).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#111418")) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, w: Float, repeat: Boolean = false, f: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2A2F36"))
            if (repeat) setOnTouchListener(Repeat(f)) else setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); f() }
            row.addView(this, LinearLayout.LayoutParams(0, (48 * dp).toInt(), w).apply { setMargins(3, 3, 3, 3) })
        }
        key("ABC", 1.2f) { if (!switchToPreviousInputMethod()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        key("undo", 1.2f) { undoLast() }
        key("space", 3f) { currentInputConnection?.commitText(" ", 1) }
        key("del", 1.2f, repeat = true) { backspace() }
        key("enter", 1.3f) { enter() }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        val t = TalkBar(this, onText = { insert(it) },
            onNeedSetup = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            appPkg = { currentInputEditorInfo?.packageName })
        talk = t
        root.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
        TalkBar.padForNavBar(root, pad)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) { super.onStartInputView(info, restarting); talk?.refresh() }
    override fun onFinishInputView(finishingInput: Boolean) { talk?.cancel(); super.onFinishInputView(finishingInput) }

    private fun insert(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace() && text.first().isLetterOrDigit()) " " else ""
        lastInserted = lead + text; ic.commitText(lastInserted, 1)
    }
    private fun undoLast() { val ic = currentInputConnection ?: return
        if (lastInserted.isNotEmpty() && ic.getTextBeforeCursor(lastInserted.length, 0)?.toString() == lastInserted) { ic.deleteSurroundingText(lastInserted.length, 0); lastInserted = "" } }
    private fun backspace() { val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1) }
    private fun enter() { val ic = currentInputConnection ?: return; val ei = currentInputEditorInfo
        val act = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (ei != null && act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) ic.performEditorAction(act)
        else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) } }

    private inner class Repeat(val f: () -> Unit) : View.OnTouchListener {
        private val r = object : Runnable { override fun run() { f(); ui.postDelayed(this, 60) } }
        override fun onTouch(v: View, e: MotionEvent): Boolean { when (e.action) {
            MotionEvent.ACTION_DOWN -> { f(); ui.postDelayed(r, 400) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(r) }; return true }
    }
}
