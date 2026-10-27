package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Settings model for the "IAQ-IEQ routine"
// ---------------------------------------------------------------------------------------------

enum class RoutineType { FREE_STYLE, IAQ_IEQ }

/** What to collect at one stop: sample every [intervalS] seconds for [durationS] seconds. */
data class StopPlan(val location: String, val durationS: Int, val intervalS: Int) {
    /** Number of readings taken at this stop (the last one is taken when the duration is over). */
    val sampleCount: Int get() = (durationS / intervalS.coerceAtLeast(1)).coerceAtLeast(1)
}

val DURATION_OPTIONS_S = listOf(5, 30, 60, 300)
val INTERVAL_OPTIONS_S = listOf(1, 5, 30, 60)
const val DEFAULT_DURATION_S = 30
const val DEFAULT_INTERVAL_S = 5

fun secondsLabel(s: Int): String = if (s >= 60 && s % 60 == 0) "${s / 60} min" else "$s sec"

/** Intervals that make sense for a duration (an interval longer than the duration is useless). */
fun intervalsFor(durationS: Int): List<Int> =
    INTERVAL_OPTIONS_S.filter { it <= durationS }.ifEmpty { listOf(INTERVAL_OPTIONS_S.first()) }

// ---------------------------------------------------------------------------------------------
// Local files
// ---------------------------------------------------------------------------------------------

/**
 * One CSV per routine run, written stop by stop while the routine is running.
 *
 *   <app external files>/iaq_routines/pending/    not uploaded yet (or upload failed)
 *   <app external files>/iaq_routines/uploaded/   successfully uploaded
 *
 * Columns are exactly the columns of the Google Sheet (see [HEADER]).
 */
object IaqDataStore {

    val HEADER = listOf(
        "date", "time", "location", "interval_s", "duration_s",
        "temp_c", "humidity_pct", "co2_ppm", "pm25", "pm10", "tvoc", "hcho", "light_lux", "sound_db",
        "iaq", "level", "data_fresh", "routine", "run_id"
    )

    private fun root(): File {
        val ctx = AppSettings.appContext
        return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "iaq_routines")
    }

    fun pendingDir(): File = File(root(), "pending").apply { mkdirs() }
    fun uploadedDir(): File = File(root(), "uploaded").apply { mkdirs() }

    fun pendingFiles(): List<File> =
        pendingDir().listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    fun runId(startedAt: Long, routineName: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        return stamp + "_" + routineName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(30)
    }

    /** Creates the CSV for a run (with its header line) and returns it. */
    fun newRunFile(runId: String): File {
        val f = File(pendingDir(), "iaq_$runId.csv")
        f.writeText(HEADER.joinToString(",") + "\n")
        return f
    }

    fun appendRows(file: File, rows: List<List<String>>) {
        file.appendText(rows.joinToString("") { r -> r.joinToString(",") { csv(it) } + "\n" })
    }

    /** Removes a run file that never got a single reading. */
    fun discardIfEmpty(file: File) {
        if (file.exists() && file.readLines().count { it.isNotBlank() } <= 1) file.delete()
    }

    fun row(
        timeMs: Long, plan: StopPlan, s: SensorData, fresh: Boolean, routine: String, runId: String
    ): List<String> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMs))
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timeMs))
        val iaq = computeIAQ(s)
        return listOf(
            date, time, plan.location, plan.intervalS.toString(), plan.durationS.toString(),
            s.temp.toString(), s.humidity.toString(), s.co2.toString(), s.pm25.toString(),
            s.pm10.toString(), s.tvoc.toString(), s.hcho.toString(), s.light.toString(),
            s.sound.toString(), iaq.toString(), getLabel(getLevelFromScore(iaq.toFloat())),
            fresh.toString(), routine, runId
        )
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Reads a run file back (needed to retry uploads). The first line is the header. */
    fun readRows(file: File): List<List<String>> =
        file.readLines().drop(1).filter { it.isNotBlank() }.map { parseCsvLine(it) }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}

// ---------------------------------------------------------------------------------------------
// Google Sheets upload (through a Google Apps Script web app, see apps_script/Code.gs)
// ---------------------------------------------------------------------------------------------

data class UploadState(
    val busy: Boolean = false,
    val pendingFiles: Int = 0,
    val message: String = "",
    /** true = last attempt worked, false = it failed, null = nothing tried yet */
    val ok: Boolean? = null
)

data class UploadSummary(val ok: Boolean, val message: String)

object SheetsUploader {

    private const val CHUNK_ROWS = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(UploadState())
    val state: StateFlow<UploadState> = _state.asStateFlow()

    fun refreshPending() {
        _state.update { it.copy(pendingFiles = IaqDataStore.pendingFiles().size) }
    }

    /** Buttons on the Routine tab (run in the uploader's own scope, so leaving the tab does not cancel them). */
    fun testAsync() {
        scope.launch { test() }
    }

    fun uploadNowAsync() {
        // The routine is still writing its file; uploading it now would split the run in two.
        if (RoutineManager.state.value.isActive) {
            _state.update { it.copy(message = "A routine is running. Its data uploads automatically when it finishes.", ok = false) }
            return
        }
        scope.launch { uploadPending() }
    }

    suspend fun test(): UploadSummary = mutex.withLock {
        val url = AppSettings.sheetsUrl.value.trim()
        if (url.isBlank()) return@withLock finish(false, "Enter the Apps Script web app URL first.")
        _state.update { it.copy(busy = true, message = "Testing…") }
        val result = withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("action", "ping").put("token", AppSettings.sheetsToken.value.trim())
                val reply = JSONObject(post(url, body.toString()))
                if (reply.optBoolean("ok")) UploadSummary(true, "Connected. Sheet is ready.")
                else UploadSummary(false, "Rejected: ${reply.optString("error", "unknown error")}")
            } catch (e: Exception) {
                UploadSummary(false, "Could not reach Google: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        finish(result.ok, result.message)
    }

    /** Uploads every run file that is still in the pending folder. Safe to call at any time. */
    suspend fun uploadPending(): UploadSummary = try {
        uploadPendingLocked()
    } finally {
        // A routine that is stopped while uploading cancels this call; never leave the screen "busy".
        if (_state.value.busy) _state.update { it.copy(busy = false, message = "Upload interrupted. Data is kept on the tablet.", ok = false) }
        refreshPending()
    }

    private suspend fun uploadPendingLocked(): UploadSummary = mutex.withLock {
        val url = AppSettings.sheetsUrl.value.trim()
        val files = IaqDataStore.pendingFiles()
        if (files.isEmpty()) return@withLock finish(true, "Nothing waiting to upload.")
        if (url.isBlank()) {
            return@withLock finish(false, "Google Sheets URL not set. Data is kept on the tablet (${files.size} file(s)).")
        }
        _state.update { it.copy(busy = true, message = "Uploading ${files.size} file(s)…") }

        val token = AppSettings.sheetsToken.value.trim()
        var rowsSent = 0
        var failed = 0
        var lastError = ""
        withContext(Dispatchers.IO) {
            for (file in files) {
                try {
                    rowsSent += uploadFile(url, token, file)
                    val target = File(IaqDataStore.uploadedDir(), file.name)
                    if (!file.renameTo(target)) {
                        file.copyTo(target, overwrite = true)
                        file.delete()
                    }
                } catch (e: Exception) {
                    failed++
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
        }
        if (failed == 0) finish(true, "Uploaded $rowsSent rows to Google Sheets.")
        else finish(false, "Upload failed for $failed file(s): $lastError. Data is kept on the tablet and can be retried.")
    }

    /** Sends one run file in chunks; throws if any chunk is refused. Returns the number of rows sent. */
    private fun uploadFile(url: String, token: String, file: File): Int {
        val rows = IaqDataStore.readRows(file)
        val runId = file.nameWithoutExtension
        var sent = 0
        rows.chunked(CHUNK_ROWS).forEachIndexed { idx, chunk ->
            val arr = JSONArray()
            chunk.forEach { r -> arr.put(JSONArray(r)) }
            // "key" lets the sheet ignore a chunk it already has, so a retry never duplicates rows.
            val body = JSONObject()
                .put("action", "append").put("token", token)
                .put("key", "$runId:$idx").put("rows", arr)
            val text = post(url, body.toString())
            val reply = try { JSONObject(text) } catch (e: Exception) {
                throw IllegalStateException("unexpected reply from Google (check the web app is deployed for \"Anyone\")")
            }
            if (!reply.optBoolean("ok")) throw IllegalStateException(reply.optString("error", "refused"))
            sent += chunk.size
        }
        return sent
    }

    private fun post(urlText: String, body: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode            // Apps Script answers with a redirect; it is followed automatically
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } finally {
            conn?.disconnect()
        }
    }

    private fun finish(ok: Boolean, message: String): UploadSummary {
        _state.value = UploadState(
            busy = false,
            pendingFiles = IaqDataStore.pendingFiles().size,
            message = message,
            ok = ok
        )
        return UploadSummary(ok, message)
    }
}
