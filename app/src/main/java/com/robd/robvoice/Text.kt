package com.robd.robvoice

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs { fun get(c: Context): SharedPreferences = c.getSharedPreferences("robvoice", Context.MODE_PRIVATE) }

/**
 * Local-only text cleanup, applied after ASR. All original code.
 * Order: spoken commands -> filler removal -> user dictionary -> salon mode (optional) -> tidy spacing/caps.
 */
object PostProcessor {
    private val FILLERS = Regex("""(?i)(^|[\s,])(?:um+|uh+|erm+|er|ah+|hmm+|mm+|you know,|i mean,)(?=[\s,.!?]|$)""")
    private val STUTTER = Regex("""(?i)\b(\w+)(?:[\s,]+\1\b)+""")
    private val COMMANDS = listOf(
        Regex("""(?i)[,.]?\s*\bnew paragraph\b[,.]?\s*""") to "\n\n",
        Regex("""(?i)[,.]?\s*\bnew line\b[,.]?\s*""") to "\n",
        Regex("""(?i)\s*\bperiod\b(?=\s*$)""") to ".",
        Regex("""(?i)\s*\bquestion mark\b""") to "?",
        Regex("""(?i)\s*\bexclamation (?:mark|point)\b""") to "!",
    )

    /** Default dictionary: "heard => written" per line. Editable in the app. */
    const val DEFAULT_DICT = """no cloud voice => NoCloud Voice
sherpa onnx => sherpa-onnx
g mail => Gmail"""

    fun parseDict(s: String): List<Pair<Regex, String>> = s.lines().mapNotNull { l ->
        val p = l.split("=>"); if (p.size != 2 || p[0].isBlank()) null
        else Regex("""(?i)\b""" + Regex.escape(p[0].trim()).replace(" ", """\E[\s\-]+\Q""") + """\b""") to p[1].trim()
    }

    // The dictionary is parsed into regexes once and reused until the text changes (it used to be rebuilt on every dictation).
    @Volatile private var dictCache: Pair<String, List<Pair<Regex, String>>>? = null
    private fun dictFor(s: String): List<Pair<Regex, String>> = dictCache?.takeIf { it.first == s }?.second ?: parseDict(s).also { dictCache = s to it }
    private val TAGS = Regex("""\[[^\]]*\]"""); private val WS = Regex("\\s+"); private val SPACE_PUNCT = Regex("""[ \t]+([,.!?])""")
    private val MULTI_SPACE = Regex("""[ \t]{2,}"""); private val LEAD = Regex("""^[\s,]+"""); private val SENT_START = Regex("""(^|[.!?]\s+|\n)([a-z])""")

    fun process(c: Context, raw: String): String {
        val pr = Prefs.get(c)
        return processWith(raw, pr.getBoolean("commands", true), pr.getBoolean("fillers", true), pr.getString("dict", DEFAULT_DICT) ?: "", pr.getBoolean("salon", false))
    }

    /** Pure version (no Android Context) so it can be unit-tested on the JVM. */
    fun processWith(raw: String, commands: Boolean, fillers: Boolean, dict: String, salon: Boolean): String {
        var t = raw.replace(TAGS, " ").replace(WS, " ").trim()
        if (t.isEmpty()) return t
        if (commands) for ((r, v) in COMMANDS) t = t.replace(r, v)
        if (fillers) { t = t.replace(FILLERS, "$1").replace(STUTTER, "$1") }
        for ((r, v) in dictFor(dict)) t = t.replace(r, Regex.escapeReplacement(v))
        if (salon) t = Salon.shades(t)
        t = t.replace(SPACE_PUNCT, "$1").replace(MULTI_SPACE, " ").replace(LEAD, "").trim()
        t = t.replace(SENT_START) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t
    }
}

/** Hair-color shade normalizer (seven slash zero -> 7/0, eight oh one -> 8/01, one to one -> 1:1). Optional; off by default. */
object Salon {
    private val ONES = mapOf("zero" to 0, "oh" to 0, "o" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
    private fun lvl(w: String): Int? = w.toIntOrNull()?.takeIf { it in 1..12 } ?: ONES[w]?.takeIf { it in 1..12 }
    private fun dig(w: String): String? = w.toIntOrNull()?.takeIf { it in 0..9 && w.length == 1 }?.toString() ?: ONES[w]?.takeIf { it <= 9 }?.toString()
    private val UNIT = Regex("""^(ounces?|oz|grams?|g|parts?|minutes?|mins?|%|percent|vol|volume)$""")

    fun shades(input: String): String {
        val w = input.replace(Regex("""(\d)/(\d)"""), "$1 slash $2").replace(Regex("""[,;!?.]""")) { " ${it.value} " }.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lw = w.map { it.lowercase() }
        val out = ArrayList<String>(); var i = 0
        fun at(k: Int) = lw.getOrElse(k) { "" }
        while (i < w.size) {
            val x = at(i)
            if ((x == "1" || x == "one") && at(i + 1) == "to" && lvl(at(i + 2)) != null && lvl(at(i + 2))!! <= 4) {
                var b = lvl(at(i + 2))!!.toDouble(); var j = i + 3
                if (at(j) == "and" && at(j + 1) in setOf("a", "an") && at(j + 2) == "half") { b += 0.5; j += 3 }
                out += "1:" + (if (b % 1.0 == 0.0) b.toInt().toString() else b.toString()); i = j; continue
            }
            val L = lvl(x)
            if (L != null) {
                var j = i + 1; var sep = false
                if (at(j) in setOf("slash", "over", "stroke", "/", "point", "dot")) { sep = true; j++ }
                if (sep && Regex("""^\d\d$""").matches(at(j)) && !UNIT.matches(at(j + 1))) { out += "$L/${at(j)}"; i = j + 1; continue }
                val d1 = dig(at(j)); val zero = at(j) in setOf("oh", "o", "zero")
                if (d1 != null && (sep || zero)) {
                    var tone = d1; j++
                    val d2 = dig(at(j)); if (d2 != null && !(at(j) == "one" && at(j + 1) == "to")) { tone += d2; j++ }
                    if (!UNIT.matches(at(j))) { out += "$L/$tone"; i = j; continue }
                }
            }
            val m = Regex("""^([1-9])0([1-9])$""").find(x)
            if (m != null && !UNIT.matches(at(i + 1))) { out += "${m.groupValues[1]}/0${m.groupValues[2]}"; i++; continue }
            out += w[i]; i++
        }
        return out.joinToString(" ").replace(Regex(""" ([,;!?.])"""), "$1")
    }
}

object History {
    private const val MAX = 500
    fun add(c: Context, raw: String, text: String, app: String?, ms: Long) {
        val arr = all(c); val o = JSONObject().put("t", System.currentTimeMillis()).put("raw", raw).put("text", text).put("app", app ?: "").put("ms", ms)
        val n = JSONArray().put(o); for (k in 0 until minOf(arr.length(), MAX - 1)) n.put(arr.get(k))
        Prefs.get(c).edit().putString("history", n.toString()).apply()
    }
    fun all(c: Context): JSONArray = try { JSONArray(Prefs.get(c).getString("history", "[]")) } catch (e: Exception) { JSONArray() }
    fun clear(c: Context) = Prefs.get(c).edit().remove("history").apply()
}
