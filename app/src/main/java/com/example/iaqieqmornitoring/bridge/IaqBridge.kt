package com.example.iaqieqmornitoring.bridge

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** What an AI/voice app (MatePet) may ask this app to do. See docs/AI_BRIDGE.md. */
interface IaqBridgeHost {
    fun startMission(route: String?): String
    fun stopMission(): String
    fun startRoutine(name: String?): String
    fun stopRoutine(): String
    fun openTab(tab: String): String
    fun status(): JSONObject
}

object IaqBridge {
    @Volatile var host: IaqBridgeHost? = null
    @Volatile var message = ""
    @Volatile var msgId = 0L
    private val seq = AtomicLong()
    fun say(m: String) { message = m; msgId = seq.incrementAndGet() }
}

/** Receives commands; only apps holding the signature permission can reach it (see AndroidManifest). */
class IaqCommandReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val h = IaqBridge.host ?: return IaqBridge.say("The monitoring app is not ready yet.")
        val arg = i.getStringExtra("arg")?.takeIf { it.isNotBlank() }
        IaqBridge.say(runCatching {
            when (i.getStringExtra("cmd")) {
                "START_MISSION" -> h.startMission(arg)
                "STOP_MISSION" -> h.stopMission()
                "START_ROUTINE" -> h.startRoutine(arg)
                "STOP_ROUTINE" -> h.stopRoutine()
                "OPEN_TAB" -> h.openTab(arg ?: "dashboard")
                else -> "Unknown command."
            }
        }.getOrElse { "That failed: ${it.message}" })
    }
}

/** content://<applicationId>.bridge/status -> one row, column "json". */
class IaqStatusProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? {
        val h = IaqBridge.host ?: return null
        val j = h.status().put("msgId", IaqBridge.msgId).put("message", IaqBridge.message)
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(j.toString())) }
    }
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
