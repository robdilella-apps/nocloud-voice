package com.robd.robvoice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One model file with its exact size and SHA-256 (from the Hugging Face API tree listing, 2026-10-27). sha256 = null for small non-LFS text files (size-checked only). */
data class MFile(val name: String, val size: Long, val sha256: String?)

/** Model catalog. Files are fetched once from Hugging Face (public mirrors of the sherpa-onnx exports), verified, then used offline. */
data class ModelSpec(val id: String, val label: String, val repo: String, val files: List<MFile>, val approxMb: Int, val license: String)

object Models {
    val PARAKEET = ModelSpec(
        "parakeet-v2", "NVIDIA Parakeet TDT 0.6B v2 (int8) - best English accuracy, punctuation + caps built in",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        listOf(MFile("encoder.int8.onnx", 652184296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            MFile("decoder.int8.onnx", 7257753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            MFile("joiner.int8.onnx", 1739080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            MFile("tokens.txt", 9384, null)), 662, "CC-BY-4.0 (NVIDIA)")
    val MOONSHINE = ModelSpec(
        "moonshine-base", "Moonshine Base EN (int8) - smaller + faster fallback, slightly less accurate",
        "csukuangfj/sherpa-onnx-moonshine-base-en-int8",
        listOf(MFile("preprocess.onnx", 14077290, "ffa630d395c5ccf76f5d4954be5b882df76aaf6491519ec01fd82ea7a3819fb2"),
            MFile("encode.int8.onnx", 50311494, "7e38770f776f2e5583a53b052936005df2ba5c833d7e09c2a5fd796b94bf73e2"),
            MFile("uncached_decode.int8.onnx", 122120451, "c01f4b35093bcac20d352d23a75a539e772964579f9d024a90e5e6f09cae9987"),
            MFile("cached_decode.int8.onnx", 99983837, "2db74e51cedf64a8b1be3c8192e0bb5e4923af0e90bd9e87f8e8771873f8ea03"),
            MFile("tokens.txt", 436688, null)), 287, "MIT (Useful Sensors)")
    val ALL = listOf(PARAKEET, MOONSHINE)

    fun dir(ctx: Context, m: ModelSpec) = File(ctx.filesDir, "models/${m.id}")
    private fun okMarker(ctx: Context, m: ModelSpec) = File(dir(ctx, m), ".verified")

    /** Ready = every file present with the EXACT expected size AND the post-download hash check passed (marker). */
    fun isReady(ctx: Context, m: ModelSpec) = okMarker(ctx, m).exists() && m.files.all { File(dir(ctx, m), it.name).length() == it.size }

    /** "ready" | "incomplete" (some files present but wrong/unverified) | "missing" */
    fun state(ctx: Context, m: ModelSpec): String = when {
        isReady(ctx, m) -> "ready"
        dir(ctx, m).listFiles()?.any { it.name != ".verified" } == true -> "incomplete"
        else -> "missing"
    }

    fun delete(ctx: Context, m: ModelSpec) { Asr.unload(m.id); dir(ctx, m).deleteRecursively() }

    fun selected(ctx: Context): ModelSpec = ALL.firstOrNull { it.id == Prefs.get(ctx).getString("model", PARAKEET.id) } ?: PARAKEET

    private fun sha256(f: File, progress: (Int) -> Unit): String {
        val md = java.security.MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 20); var done = 0L; var last = -1
        f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n
            val p = (done * 100 / f.length()).toInt(); if (p != last) { last = p; progress(p) } } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Opens the raw-file URL (huggingface .../resolve/main/<file>), following redirects to the CDN, with optional Range. */
    private fun open(m: ModelSpec, f: MFile, from: Long): HttpURLConnection {
        var url = URL("https://huggingface.co/${m.repo}/resolve/main/${f.name}")
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            return c
        }
        throw RuntimeException("Too many redirects for ${f.name}")
    }

    /**
     * Blocking download with progress. Each file -> <name>.part, resumed only if the server answers 206 with a
     * Content-Range starting exactly where the .part ends; otherwise restarts from 0. After download: exact size check,
     * SHA-256 check, then atomic rename. Bad files are deleted. Throws on any mismatch.
     */
    fun download(ctx: Context, m: ModelSpec, progress: (String, Int) -> Unit) {
        val d = dir(ctx, m).apply { mkdirs() }
        okMarker(ctx, m).delete()
        for (f in m.files) {
            val out = File(d, f.name)
            if (out.exists() && out.length() != f.size) out.delete()
            if (out.exists() && f.sha256 != null && sha256(out) { p -> progress("checking ${f.name}", p) } != f.sha256) out.delete()
            if (!out.exists()) {
                val part = File(d, "${f.name}.part")
                if (part.length() > f.size) part.delete()
                var from = if (part.exists()) part.length() else 0L
                val c = open(m, f, from)
                val code = c.responseCode
                val ctype = c.contentType ?: ""
                if (ctype.contains("text/html")) { c.disconnect(); throw RuntimeException("Got a web page instead of ${f.name}") }
                val append = from > 0 && code == 206 && (c.getHeaderField("Content-Range") ?: "").startsWith("bytes $from-")
                when {
                    append -> {}
                    code == 200 -> from = 0
                    else -> { c.disconnect(); part.delete(); throw RuntimeException("HTTP $code for ${f.name} - tap Download again") }
                }
                var done = from; var last = -1
                c.inputStream.use { inp -> java.io.FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                        val pct = (done * 100 / f.size).toInt(); if (pct != last) { last = pct; progress(f.name, pct) } }
                    o.fd.sync() } }
                if (part.length() != f.size) {
                    val got = part.length(); if (got > f.size) part.delete()
                    throw RuntimeException("${f.name} incomplete ($got of ${f.size} bytes) - tap Download again to resume")
                }
                if (f.sha256 != null) {
                    val h = sha256(part) { p -> progress("checking ${f.name}", p) }
                    if (h != f.sha256) { part.delete(); throw RuntimeException("${f.name} failed checksum - deleted, tap Download again") }
                }
                if (!part.renameTo(out)) throw RuntimeException("rename failed for ${f.name}")
            }
        }
        if (!m.files.all { File(d, it.name).length() == it.size }) throw RuntimeException("Model incomplete after download")
        okMarker(ctx, m).writeText(System.currentTimeMillis().toString())
    }
}

/** Holds one loaded recognizer for the whole process (IME + app share it). */
object Asr {
    @Volatile private var rec: OfflineRecognizer? = null
    @Volatile private var loadedId: String? = null

    @Synchronized fun get(ctx: Context): OfflineRecognizer? {
        val m = Models.selected(ctx)
        if (rec != null && loadedId == m.id) return rec
        if (!Models.isReady(ctx, m)) return null
        rec?.release()
        val d = Models.dir(ctx, m).absolutePath
        val threads = 4
        val mc = when (m.id) {
            Models.MOONSHINE.id -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(preprocessor = "$d/preprocess.onnx", encoder = "$d/encode.int8.onnx",
                    uncachedDecoder = "$d/uncached_decode.int8.onnx", cachedDecoder = "$d/cached_decode.int8.onnx"),
                tokens = "$d/tokens.txt", numThreads = threads, provider = "cpu")
            else -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = "$d/encoder.int8.onnx", decoder = "$d/decoder.int8.onnx", joiner = "$d/joiner.int8.onnx"),
                tokens = "$d/tokens.txt", numThreads = threads, provider = "cpu", modelType = "nemo_transducer")
        }
        try { rec = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = mc, decodingMethod = "greedy_search")) }
        catch (t: Throwable) {
            rec = null; loadedId = null; Models.delete(ctx, m)
            throw IllegalStateException("Model files were damaged and have been deleted. Open NoCloud Voice and tap Download to get them again.")
        }
        loadedId = m.id
        return rec
    }

    @Synchronized fun unload(id: String) { if (loadedId == id) { rec?.release(); rec = null; loadedId = null } }

    fun transcribe(ctx: Context, samples: FloatArray): String {
        val r = get(ctx) ?: throw IllegalStateException("Model not downloaded yet – open the NoCloud Voice app")
        val s = r.createStream()
        try { s.acceptWaveform(samples, 16000); r.decode(s); return r.getResult(s).text.trim() } finally { s.release() }
    }
}
