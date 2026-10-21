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
                    try {
                        c.subscribe(topic, 0)
                        SensorRepository.setLink(
                            SensorLinkState(
                                SensorMode.MQTT,
                                connected = true,
                                detail = "Connected to $host:$port · $topic",
                                fields = SensorRepository.link.value.fields
                            )
                        )
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
                    handleMessage(t ?: "", String(message.payload, Charsets.UTF_8).trim())
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
        val parsed = SensorPayload.parse(topic, text, SensorRepository.data.value) ?: return
        SensorRepository.publish(parsed.first, parsed.second)
    }
}

/** Turns an MQTT payload into [SensorData]. Pure function, easy to unit test. */
object SensorPayload {

    private val aliases: List<Pair<String, List<String>>> = listOf(
        "TEMP" to listOf("temp", "temperature", "temp_c", "t"),
        "HUMIDITY" to listOf("humidity", "humid", "rh", "hum"),
        "CO2" to listOf("co2", "eco2"),
        "PM2.5" to listOf("pm25", "pm2_5", "pm2.5", "pm_25"),
        "PM10" to listOf("pm10", "pm_10"),
        "TVOC" to listOf("tvoc", "voc"),
        "HCHO" to listOf("hcho", "formaldehyde", "ch2o"),
        "LIGHT" to listOf("light", "lux", "illuminance"),
        "SOUND" to listOf("sound", "noise", "db", "noise_db")
    )

    /** Returns the merged reading and the set of sensors present in this message, or null if unusable. */
    fun parse(topic: String, text: String, previous: SensorData): Pair<SensorData, Set<String>>? {
        val values = HashMap<String, Float>()

        if (text.startsWith("{")) {
            val root = try { JSONObject(text) } catch (e: JSONException) { return null }
            val obj = root.optJSONObject("sensors") ?: root.optJSONObject("data") ?: root

            val lower = HashMap<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                lower[k.lowercase()] = obj.opt(k)
            }
            for ((field, names) in aliases) {
                for (n in names) {
                    val raw = lower[n] ?: continue
                    val f = when (raw) {
                        is Number -> raw.toFloat()
                        is String -> raw.toFloatOrNull()
                        else -> null
                    } ?: continue
                    if (!f.isNaN() && !f.isInfinite()) {
                        values[field] = f
                        break
                    }
                }
            }
        } else {
            val number = text.toFloatOrNull() ?: return null
            if (number.isNaN() || number.isInfinite()) return null
            val last = topic.substringAfterLast('/').lowercase()
            val field = aliases.firstOrNull { last in it.second }?.first ?: return null
            values[field] = number
        }

        if (values.isEmpty()) return null

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
        return merged to values.keys.toSet()
    }
}
