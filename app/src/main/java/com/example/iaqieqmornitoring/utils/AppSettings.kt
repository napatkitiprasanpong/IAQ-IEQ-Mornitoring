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

    private const val PREFS_NAME = "iaq_ieq_settings"
    private const val KEY_ROUTES = "routes_json"

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

    // ---- Raspberry Pi sensor link ----
    val simulateSensors = Setting("simulate_sensors", false)
    val mqttHost = Setting("mqtt_host", "raspberrypi.local")
    val mqttPort = Setting("mqtt_port", 1883)
    val mqttTopic = Setting("mqtt_topic", "iaq/sensors")
    val mqttUser = Setting("mqtt_user", "")
    val mqttPass = Setting("mqtt_pass", "")

    // ---- routes ----
    private val _routes = MutableStateFlow(defaultRoutes())
    val routes: StateFlow<List<MissionRoute>> = _routes.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        listOf(
            simulateRobot, selectedRoute, dwellSeconds, arrivalTimeoutSeconds, retries,
            homeBase, returnHome, raiseHandFollowEnabled, raiseHandFollowSeconds, findPersonEnabled, simulateSensors, mqttHost, mqttPort, mqttTopic, mqttUser, mqttPass
        ).forEach { it.load(p) }

        p.getString(KEY_ROUTES, null)?.let { _routes.value = routesFromJson(it) }
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
