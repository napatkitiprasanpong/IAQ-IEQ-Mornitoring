package com.example.iaqieqmornitoring.bridge

import com.example.iaqieqmornitoring.utils.*
import org.json.JSONArray
import org.json.JSONObject

class IaqBridgeHostImpl : IaqBridgeHost {
    private val tabs = mapOf(
        "dashboard" to "home", "analytics" to "analytics", "mission" to "mission", "routine" to "routine",
        "settings" to "settings", "startup" to "startup", "sign language" to "signlanguage",
        "camera and depth" to "camera", "depth" to "camera", "camera" to "camera")

    private fun clean(s: String?) = s?.lowercase()?.trim()?.removePrefix("the ")?.trim()

    override fun startMission(route: String?): String {
        val routes = AppSettings.routes.value
        if (routes.isEmpty()) return "There are no routes set up. Add one in Settings."
        val q = clean(route)
        val r = if (q.isNullOrEmpty()) routes.first()
        else routes.firstOrNull { it.name.equals(q, true) }
            ?: routes.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
            ?: return "I can't find a route called $route. Routes are: ${routes.joinToString { it.name }}."
        return MissionManager.start(r.name) ?: "Starting mission on ${r.name}."
    }

    override fun stopMission(): String =
        if (MissionManager.state.value.isActive) { MissionManager.stop(); "Stopping the mission." } else "No mission is running."

    override fun startRoutine(name: String?): String {
        val rs = AppSettings.routines.value
        if (rs.isEmpty()) return "There are no routines set up."
        val q = clean(name)
        val r = if (q.isNullOrEmpty()) rs.first()
        else rs.firstOrNull { it.name.equals(q, true) } ?: rs.firstOrNull { it.name.lowercase().contains(q) }
            ?: return "I can't find a routine called $name."
        val label = r.name.ifBlank { "%02d:%02d".format(r.hour, r.minute) }
        return RoutineManager.runNow(r.id) ?: "Starting routine $label."
    }

    override fun stopRoutine(): String =
        if (RoutineManager.state.value.isActive) { RoutineManager.stop(); "Stopping the routine." } else "No routine is running."

    override fun openTab(tab: String): String {
        val route = tabs[tab.lowercase().trim()] ?: return "I don't know a tab called $tab."
        BridgeNav.request(route); return "Opening the $tab tab."
    }

    private fun f(x: Float) = if (x.isNaN() || x.isInfinite()) 0.0 else x.toDouble()

    override fun status(): JSONObject {
        val d = SensorRepository.data.value; val iaq = computeIAQ(d)
        val m = MissionManager.state.value; val rt = RoutineManager.state.value
        return JSONObject()
            .put("iaq", iaq).put("level", getLabel(getLevelFromScore(iaq.toFloat()))).put("fresh", SensorRepository.isFresh())
            .put("sensors", JSONObject().put("temp", f(d.temp)).put("humidity", f(d.humidity)).put("co2", f(d.co2))
                .put("pm25", f(d.pm25)).put("pm10", f(d.pm10)).put("tvoc", f(d.tvoc)).put("hcho", f(d.hcho))
                .put("light", f(d.light)).put("sound", f(d.sound)))
            .put("mission", JSONObject().put("state", if (m.isActive) "running" else "idle").put("route", m.routeName)
                .put("stop", (m.currentIndex + 1).coerceAtLeast(0)).put("total", m.stops.size))
            .put("routine", JSONObject().put("state", if (rt.isActive) "running" else "idle").put("phase", rt.phase.name))
            .put("routes", JSONArray(AppSettings.routes.value.map { it.name }))
    }
}
