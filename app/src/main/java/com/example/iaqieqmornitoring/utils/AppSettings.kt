package com.example.iaqieqmornitoring.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One inspection route: an ordered list of temi saved-location names. */
data class MissionRoute(val name: String, val waypoints: List<String>)

/** IP address of the Raspberry Pi that publishes the sensor readings over MQTT. */
const val DEFAULT_PI_HOST = "10.2.115.198"

const val DEFAULT_ROUTINE_MESSAGE = "Please drink water"

/**
 * A scheduled routine. Every day at [hour]:[minute] temi drives the route [routeName] and, at each
 * stop, speaks [message] and shows a popup (Yes / Come back in N mins).
 * [lastRunDay] (yyyyMMdd) stops a routine from starting twice on the same day.
 */
data class Routine(
    val id: String,
    val name: String,
    val hour: Int,
    val minute: Int,
    val routeName: String,
    val message: String = DEFAULT_ROUTINE_MESSAGE,
    val enabled: Boolean = true,
    val lastRunDay: String = "",
    /** FREE_STYLE = the original reminder routine; IAQ_IEQ = collect sensor data at every stop. */
    val type: RoutineType = RoutineType.FREE_STYLE,
    /** IAQ_IEQ only: how long and how often to sample at each stop, in route order. */
    val plans: List<StopPlan> = emptyList()
) {
    val minuteOfDay: Int get() = hour * 60 + minute
}

/** A single persisted setting that the UI can observe through [flow]. */
class Setting<T : Any>(val key: String, val default: T) {
    private val _flow = MutableStateFlow(default)
    val flow: StateFlow<T> = _flow.asStateFlow()
    val value: T get() = _flow.value

    fun set(newValue: T) {
        _flow.value = newValue
        AppSettings.persist(key, newValue)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun load(p: SharedPreferences) {
        if (!p.contains(key)) return
        _flow.value = when (default) {
            is Int -> p.getInt(key, default) as T
            is Boolean -> p.getBoolean(key, default) as T
            is String -> (p.getString(key, default) ?: default) as T
            else -> default
        }
    }
}

object AppSettings {

    const val PREFS_NAME = "iaq_ieq_settings"
    /** Key of [autoStart] in the preferences file, read by BootReceiver before the app has started. */
    const val KEY_AUTO_START = "auto_start"
    private const val KEY_ROUTES = "routes_json"
    private const val KEY_ROUTINES = "routines_json"

    private var prefs: SharedPreferences? = null
    lateinit var appContext: Context
        private set

    val isInitialised: Boolean get() = prefs != null

    // ---- robot / mission ----
    val simulateRobot = Setting("simulate_robot", false)
    val selectedRoute = Setting("selected_route", "Route A")
    val dwellSeconds = Setting("dwell_seconds", 20)
    val arrivalTimeoutSeconds = Setting("arrival_timeout_s", 180)
    val retries = Setting("nav_retries", 1)
    val homeBase = Setting("home_base", "home base")
    val returnHome = Setting("return_home", true)

    // ---- raised hand -> temi follows the person (setting keys kept from the earlier wave version) ----
    val raiseHandFollowEnabled = Setting("wave_follow_enabled", false)
    val raiseHandFollowSeconds = Setting("wave_follow_seconds", 30)
    /** Tilt temi's tablet slowly up and down until the camera sees a person. */
    val findPersonEnabled = Setting("find_person_enabled", false)

    // ---- raise hand -> temi walks to the person (uses the tablet camera, not temi's depth sensors) ----
    val approachEnabled = Setting("approach_enabled", true)
    /** After arriving, hand over to temi's follow mode (works at close range). */
    val approachThenFollow = Setting("approach_then_follow", true)
    val approachStopDistanceCm = Setting("approach_stop_cm", 130)
    /** Switch on if temi turns away from you instead of towards you. */
    val approachInvertTurn = Setting("approach_invert_turn", false)
    /** Camera focal length in image widths x 1000 (set by the Calibrate button). 785 = a 65 degree wide lens. */
    val approachFocalPermille = Setting("approach_focal_permille", 785)

    // ---- start-up ----
    /** Start this app by itself when temi (the tablet) starts. */
    val autoStart = Setting(KEY_AUTO_START, false)

    // ---- Sign Language tab ----
    /** How the alphabet model expects pixel values: 0 = 0..1, 1 = -1..1, 2 = 0..255 (see SignClassifier). */
    val signInputMode = Setting("sign_input_mode", 0)

    // ---- routines (Routine tab) ----
    /** Minutes temi waits at home base before coming back after "Come back in N mins". */
    val routineSnoozeMinutes = Setting("routine_snooze_min", 5)
    /** Seconds the popup waits for an answer; no answer is treated like "Come back in N mins". */
    val routinePromptTimeoutSeconds = Setting("routine_prompt_timeout_s", 60)

    // ---- Google Sheets upload (IAQ-IEQ routine) ----
    /** URL of the deployed Google Apps Script web app (see apps_script/Code.gs). */
    val sheetsUrl = Setting("sheets_url", "")
    /** Shared secret; must equal SECRET in the Apps Script. */
    val sheetsToken = Setting("sheets_token", "")

    // ---- Raspberry Pi sensor link ----
    val simulateSensors = Setting("simulate_sensors", false)
    val mqttHost = Setting("mqtt_host", DEFAULT_PI_HOST)
    val mqttPort = Setting("mqtt_port", 1883)
    val mqttTopic = Setting("mqtt_topic", "iaq/sensors")
    val mqttUser = Setting("mqtt_user", "")
    val mqttPass = Setting("mqtt_pass", "")

    // ---- routes ----
    private val _routes = MutableStateFlow(defaultRoutes())
    val routes: StateFlow<List<MissionRoute>> = _routes.asStateFlow()

    // ---- routines ----
    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        listOf(
            simulateRobot, selectedRoute, dwellSeconds, arrivalTimeoutSeconds, retries,
            homeBase, returnHome, raiseHandFollowEnabled, raiseHandFollowSeconds, findPersonEnabled, approachEnabled, approachThenFollow, approachStopDistanceCm, approachInvertTurn, approachFocalPermille, signInputMode, autoStart, routineSnoozeMinutes, routinePromptTimeoutSeconds, simulateSensors, mqttHost, mqttPort, mqttTopic, mqttUser, mqttPass, sheetsUrl, sheetsToken
        ).forEach { it.load(p) }

        // Earlier versions defaulted to the host name below; the Pi now has a fixed address.
        if (mqttHost.value.trim() == "raspberrypi.local") mqttHost.set(DEFAULT_PI_HOST)

        p.getString(KEY_ROUTES, null)?.let { _routes.value = routesFromJson(it) }
        p.getString(KEY_ROUTINES, null)?.let { _routines.value = routinesFromJson(it) }
        ThresholdStore.init(p)
    }

    internal fun persist(key: String, value: Any) {
        val p = prefs ?: return
        val e = p.edit()
        when (value) {
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
    }

    // ---- route helpers ----
    fun updateRoutes(transform: (List<MissionRoute>) -> List<MissionRoute>) {
        val next = transform(_routes.value)
        val safe = if (next.isEmpty()) defaultRoutes() else next
        _routes.value = safe
        prefs?.edit()?.putString(KEY_ROUTES, routesToJson(safe))?.apply()
        if (safe.none { it.name == selectedRoute.value }) selectedRoute.set(safe.first().name)
    }

    // ---- routine helpers ----
    fun updateRoutines(transform: (List<Routine>) -> List<Routine>) {
        val next = transform(_routines.value)
        _routines.value = next
        prefs?.edit()?.putString(KEY_ROUTINES, routinesToJson(next))?.apply()
    }

    private fun routinesToJson(list: List<Routine>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("name", r.name)
                    .put("hour", r.hour).put("minute", r.minute)
                    .put("route", r.routeName).put("message", r.message)
                    .put("enabled", r.enabled).put("lastRunDay", r.lastRunDay)
                    .put("type", r.type.name)
                    .put(
                        "plans",
                        JSONArray().also { pa ->
                            r.plans.forEach { p ->
                                pa.put(
                                    JSONObject().put("location", p.location)
                                        .put("duration", p.durationS).put("interval", p.intervalS)
                                )
                            }
                        }
                    )
            )
        }
        return arr.toString()
    }

    private fun routinesFromJson(json: String): List<Routine> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Routine>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Routine(
                        id = o.getString("id"),
                        name = o.optString("name", "Routine"),
                        hour = o.optInt("hour", 8).coerceIn(0, 23),
                        minute = o.optInt("minute", 0).coerceIn(0, 59),
                        routeName = o.optString("route", ""),
                        message = o.optString("message", DEFAULT_ROUTINE_MESSAGE).ifBlank { DEFAULT_ROUTINE_MESSAGE },
                        enabled = o.optBoolean("enabled", true),
                        lastRunDay = o.optString("lastRunDay", ""),
                        type = try {
                            RoutineType.valueOf(o.optString("type", RoutineType.FREE_STYLE.name))
                        } catch (e: IllegalArgumentException) {
                            RoutineType.FREE_STYLE
                        },
                        plans = plansFromJson(o.optJSONArray("plans"))
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun plansFromJson(arr: JSONArray?): List<StopPlan> {
        if (arr == null) return emptyList()
        val out = ArrayList<StopPlan>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optInt("duration", DEFAULT_DURATION_S).coerceAtLeast(1)
            val iv = o.optInt("interval", DEFAULT_INTERVAL_S).coerceIn(1, d)
            out.add(StopPlan(o.optString("location", ""), d, iv))
        }
        return out
    }

    private fun defaultRoutes() = listOf(
        MissionRoute("Route A", emptyList()),
        MissionRoute("Route B", emptyList()),
        MissionRoute("Route C", emptyList())
    )

    private fun routesToJson(list: List<MissionRoute>): String {
        val arr = JSONArray()
        list.forEach { r ->
            val w = JSONArray()
            r.waypoints.forEach { w.put(it) }
            arr.put(JSONObject().put("name", r.name).put("waypoints", w))
        }
        return arr.toString()
    }

    private fun routesFromJson(json: String): List<MissionRoute> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<MissionRoute>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val names = ArrayList<String>()
                val w = o.optJSONArray("waypoints")
                if (w != null) for (j in 0 until w.length()) names.add(w.getString(j))
                out.add(MissionRoute(o.getString("name"), names))
            }
            if (out.isEmpty()) defaultRoutes() else out
        } catch (e: Exception) {
            defaultRoutes()
        }
    }
}
