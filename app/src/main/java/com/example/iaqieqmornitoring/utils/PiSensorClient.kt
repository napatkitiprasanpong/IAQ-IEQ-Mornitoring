package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Receives the sensor readings that the Raspberry Pi publishes over MQTT.
 *
 * Accepted payloads on the configured topic:
 *   - JSON object:  {"temp":24.1,"humidity":55,"co2":620,"pm25":9,"pm10":14,"tvoc":0.2,"hcho":0.02,"light":310,"sound":42}
 *     (missing keys keep their previous value; see [SensorPayload] for accepted key names, and a
 *      nested "sensors" / "data" object is also accepted)
 *   - plain number on a per-sensor topic such as iaq/sensors/co2 (use topic "iaq/sensors/#")
 */
object PiSensorClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val RETRY_DELAY_MS = 5_000L

    private var client: MqttAsyncClient? = null
    private var generation = 0

    @Synchronized
    fun connect(host: String, port: Int, topic: String, user: String, pass: String) {
        disconnect()
        if (host.isBlank() || topic.isBlank()) {
            SensorRepository.setLink(
                SensorLinkState(SensorMode.MQTT, detail = "Broker host or topic is not set")
            )
            return
        }
        val uri = "tcp://$host:$port"
        SensorRepository.setLink(
            SensorLinkState(SensorMode.MQTT, connecting = true, detail = "Connecting to $host:$port…")
        )
        attempt(++generation, uri, host, port, topic, user, pass)
    }

    @Synchronized
    fun disconnect() {
        generation++
        val c = client
        client = null
        if (c != null) {
            try { c.disconnectForcibly(0, 100) } catch (e: Exception) { /* ignore */ }
            try { c.close(true) } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun attempt(gen: Int, uri: String, host: String, port: Int, topic: String, user: String, pass: String) {
        if (gen != generation) return

        fun retryLater(reason: String?) {
            if (gen != generation) return
            SensorRepository.setLink(
                SensorLinkState(
                    SensorMode.MQTT,
                    connecting = true,
                    detail = "Cannot reach $host:$port (${reason ?: "unknown error"}) — retrying"
                )
            )
            scope.launch {
                delay(RETRY_DELAY_MS)
                attempt(gen, uri, host, port, topic, user, pass)
            }
        }

        try {
            val clientId = "iaq-" + UUID.randomUUID().toString().take(8)
            val c = MqttAsyncClient(uri, clientId, MemoryPersistence())
            synchronized(this) {
                if (gen != generation) {
                    try { c.close() } catch (e: Exception) { /* ignore */ }
                    return
                }
                client = c
            }

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 8
                keepAliveInterval = 20
                if (user.isNotBlank()) {
                    userName = user
                    password = pass.toCharArray()
                }
            }

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (gen != generation) return
                    // The Pi may publish one JSON object on "topic" or one number per sensor on "topic/<name>":
                    // listen to both so either way works without changing settings.
                    val filters = if (topic.endsWith("#") || topic.contains("+")) arrayOf(topic)
                    else arrayOf(topic, topic.trimEnd('/') + "/#")
                    SensorRepository.setLink(
                            SensorLinkState(
                                SensorMode.MQTT,
                                connected = true,
                                detail = "Connected to $host:$port · ${filters.joinToString(", ")}",
                                fields = SensorRepository.link.value.fields,
                                messages = SensorRepository.link.value.messages,
                                lastTopic = SensorRepository.link.value.lastTopic,
                                lastPayload = SensorRepository.link.value.lastPayload,
                                problem = SensorRepository.link.value.problem
                            )
                        )
                    try {
                        c.subscribe(filters, IntArray(filters.size), null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                if (gen != generation) return
                                SensorRepository.updateLink {
                                    it.copy(
                                        connected = true,
                                        connecting = false,
                                        detail = "Connected to $host:$port · ${filters.joinToString(", ")}"
                                    )
                                }
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                if (gen != generation) return
                                SensorRepository.updateLink {
                                    it.copy(
                                        connected = true,
                                        connecting = false,
                                        detail = "Connected, but subscribing to $topic was refused (${exception?.message ?: "unknown"}) — check the broker's username/password and ACL"
                                    )
                                }
                            }
                        })
                    } catch (e: Exception) {
                        SensorRepository.setLink(
                            SensorLinkState(SensorMode.MQTT, detail = "Subscribe failed: ${e.message}")
                        )
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    if (gen != generation) return
                    SensorRepository.updateLink {
                        it.copy(
                            connected = false,
                            connecting = true,
                            detail = "Connection lost (${cause?.message ?: "unknown"}) — reconnecting"
                        )
                    }
                }

                override fun messageArrived(t: String?, message: MqttMessage?) {
                    if (gen != generation || message == null) return
                    try {
                        handleMessage(t ?: "", String(message.payload, Charsets.UTF_8))
                    } catch (e: Exception) {
                        // An exception here would make Paho close the connection, so report it instead.
                        SensorRepository.updateLink { it.copy(problem = "Error reading message: ${e.message}") }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // handled in connectComplete()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    try { c.close(true) } catch (e: Exception) { /* ignore */ }
                    retryLater(exception?.message)
                }
            })
        } catch (e: Exception) {
            retryLater(e.message)
        }
    }

    private fun handleMessage(topic: String, text: String) {
        val result = SensorPayload.parseDetailed(topic, text, SensorRepository.data.value)
        val shown = text.trim().replace(Regex("\\s+"), " ").take(120)
        if (result.data != null) {
            SensorRepository.publish(result.data, result.fields)
            SensorRepository.updateLink {
                it.copy(messages = it.messages + 1, lastTopic = topic, lastPayload = shown, problem = "")
            }
        } else {
            SensorRepository.updateLink {
                it.copy(messages = it.messages + 1, lastTopic = topic, lastPayload = shown, problem = result.problem)
            }
        }
    }
}

class SensorParseResult(val data: SensorData?, val fields: Set<String>, val problem: String)

/** Turns an MQTT payload into [SensorData]. Pure function, easy to unit test. */
object SensorPayload {

    /** Key names are compared in lower case with everything except letters and digits removed ("PM2.5" == "pm2_5"). */
    private val aliases: List<Pair<String, Set<String>>> = listOf(
        "TEMP" to setOf("temp", "temperature", "tempc", "temperaturec", "ambienttemp", "airtemp", "t"),
        "HUMIDITY" to setOf("humidity", "humid", "rh", "hum", "relativehumidity", "humiditypct", "humidityrh", "rhpct", "rhpercent", "humiditypercent"),
        "CO2" to setOf("co2", "eco2", "co2ppm", "carbondioxide"),
        "PM2.5" to setOf("pm25", "pm2", "pm25ugm3", "pm25atm", "pm25std", "pm25standard"),
        "PM10" to setOf("pm10", "pm10ugm3", "pm10atm", "pm10std", "pm10standard"),
        "TVOC" to setOf("tvoc", "voc", "tvocppb", "tvocmgm3"),
        "HCHO" to setOf("hcho", "formaldehyde", "ch2o", "hchomgm3"),
        "LIGHT" to setOf("light", "lux", "illuminance", "ambientlight", "lightlux"),
        "SOUND" to setOf("sound", "noise", "db", "dba", "dbspl", "noisedb", "soundlevel", "decibel", "sounddb", "noisedba")
    )

    private val byKey: Map<String, String> = HashMap<String, String>().apply {
        for ((field, names) in aliases) for (n in names) put(n, field)
    }

    private fun norm(k: String): String = k.lowercase().filter { it.isLetterOrDigit() }

    private val numberAtStart = Regex("^\\s*([-+]?\\d+(?:[.,]\\d+)?(?:[eE][-+]?\\d+)?)")

    private fun toFloat(raw: Any?): Float? {
        val f = when (raw) {
            is Number -> raw.toFloat()
            is String -> numberAtStart.find(raw)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()   // "24.1 C" works too
            else -> null
        } ?: return null
        return if (f.isNaN() || f.isInfinite()) null else f
    }

    /** Collects every known sensor value found anywhere in [node] (objects may be nested, e.g. {"bme280":{"temp":24}}). */
    private fun collect(node: Any?, out: MutableMap<String, Float>, depth: Int = 0) {
        if (depth > 4) return
        when (node) {
            is JSONObject -> {
                // {"name":"co2","value":612} style entry
                val label = (node.opt("name") ?: node.opt("sensor") ?: node.opt("type") ?: node.opt("id")) as? String
                val labelled = label?.let { byKey[norm(it)] }
                if (labelled != null) {
                    val v = toFloat(node.opt("value") ?: node.opt("val") ?: node.opt("reading"))
                    if (v != null) { if (!out.containsKey(labelled)) out[labelled] = v; return }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    val field = byKey[norm(k)]
                    if (field != null) {
                        val f = toFloat(if (v is JSONObject) (v.opt("value") ?: v.opt("val") ?: v.opt("reading")) else v)
                        if (f != null) { if (!out.containsKey(field)) out[field] = f; continue }
                    }
                    if (v is JSONObject || v is JSONArray) collect(v, out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
        }
    }

    /** Returns the merged reading and the set of sensors present in this message, or null if unusable. */
    fun parse(topic: String, text: String, previous: SensorData): Pair<SensorData, Set<String>>? {
        val r = parseDetailed(topic, text, previous)
        return if (r.data != null) r.data to r.fields else null
    }

    /** Like [parse], but says why a message was rejected. */
    fun parseDetailed(topic: String, text: String, previous: SensorData): SensorParseResult {
        val body = text.trim().trimStart('\uFEFF').trim()
        if (body.isEmpty()) return SensorParseResult(null, emptySet(), "Empty message")
        val values = HashMap<String, Float>()

        if (body.startsWith("{") || body.startsWith("[")) {
            val root: Any = try {
                if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
            } catch (e: JSONException) {
                return SensorParseResult(null, emptySet(), "Not valid JSON (${e.message?.take(60)})")
            }
            collect(root, values)
            if (values.isEmpty()) {
                return SensorParseResult(null, emptySet(), "JSON has none of the expected keys (temp, humidity, co2, pm25, pm10, tvoc, hcho, light, sound)")
            }
        } else {
            val number = toFloat(body)
                ?: return SensorParseResult(null, emptySet(), "Not JSON and not a number")
            val last = norm(topic.substringAfterLast('/'))
            val field = byKey[last]
                ?: return SensorParseResult(null, emptySet(), "Plain number on topic '$topic' — the last part of the topic must be a sensor name such as co2")
            values[field] = number
        }

        val merged = previous.copy(
            temp = values["TEMP"] ?: previous.temp,
            humidity = values["HUMIDITY"] ?: previous.humidity,
            co2 = values["CO2"] ?: previous.co2,
            pm25 = values["PM2.5"] ?: previous.pm25,
            pm10 = values["PM10"] ?: previous.pm10,
            tvoc = values["TVOC"] ?: previous.tvoc,
            hcho = values["HCHO"] ?: previous.hcho,
            light = values["LIGHT"] ?: previous.light,
            sound = values["SOUND"] ?: previous.sound
        )
        return SensorParseResult(merged, values.keys.toSet(), "")
    }
}
