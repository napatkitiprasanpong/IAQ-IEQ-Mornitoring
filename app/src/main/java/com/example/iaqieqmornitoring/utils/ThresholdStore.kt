package com.example.iaqieqmornitoring.utils

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the 4 breakpoints per sensor. Defaults are the original table; the Settings screen can
 * override them and the overrides are persisted.
 */
object ThresholdStore {

    val defaults: Map<String, List<Float>> = linkedMapOf(
        "TEMP" to listOf(18f, 25f, 30f, 35f),
        "HUMIDITY" to listOf(30f, 50f, 70f, 85f),
        "CO2" to listOf(400f, 600f, 1000f, 2500f),
        "PM2.5" to listOf(12f, 35f, 55f, 150f),
        "PM10" to listOf(20f, 50f, 100f, 200f),
        "TVOC" to listOf(0.3f, 0.5f, 1.0f, 3.0f),
        "HCHO" to listOf(0.03f, 0.05f, 0.1f, 0.2f),
        "LIGHT" to listOf(100f, 300f, 500f, 1000f),
        "SOUND" to listOf(30f, 50f, 70f, 90f)
    )

    private const val KEY = "threshold_overrides_json"
    private var prefs: SharedPreferences? = null

    private val _overrides = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val overrides: StateFlow<Map<String, List<Float>>> = _overrides.asStateFlow()

    fun get(title: String): List<Float> {
        val key = if (title == "HUMID") "HUMIDITY" else title
        return _overrides.value[key] ?: defaults[key] ?: listOf(0f, 25f, 50f, 75f)
    }

    fun isCustom(title: String): Boolean = _overrides.value.containsKey(title)

    /** Returns an error message, or null if the values are valid. */
    fun validate(values: List<Float>): String? {
        if (values.size != 4) return "Four values are required"
        if (values.any { it.isNaN() || it.isInfinite() }) return "Enter valid numbers"
        for (i in 1..3) if (values[i] <= values[i - 1]) return "Values must be strictly increasing"
        return null
    }

    /** Returns an error message, or null on success. */
    fun set(title: String, values: List<Float>): String? {
        validate(values)?.let { return it }
        _overrides.value = _overrides.value + (title to values)
        persist()
        return null
    }

    fun reset(title: String) {
        _overrides.value = _overrides.value - title
        persist()
    }

    fun resetAll() {
        _overrides.value = emptyMap()
        persist()
    }

    fun init(p: SharedPreferences) {
        prefs = p
        val json = p.getString(KEY, null) ?: return
        try {
            val o = JSONObject(json)
            val out = HashMap<String, List<Float>>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = o.getJSONArray(k)
                val list = ArrayList<Float>()
                for (i in 0 until arr.length()) list.add(arr.getDouble(i).toFloat())
                if (validate(list) == null && defaults.containsKey(k)) out[k] = list
            }
            _overrides.value = out
        } catch (e: Exception) {
            _overrides.value = emptyMap()
        }
    }

    private fun persist() {
        val o = JSONObject()
        _overrides.value.forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { arr.put(it.toDouble()) }
            o.put(k, arr)
        }
        prefs?.edit()?.putString(KEY, o.toString())?.apply()
    }
}
