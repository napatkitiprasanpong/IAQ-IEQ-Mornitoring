package com.example.iaqieqmornitoring.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.ActionButton
import com.example.iaqieqmornitoring.components.ColorBad
import com.example.iaqieqmornitoring.components.ColorField
import com.example.iaqieqmornitoring.components.ColorMuted
import com.example.iaqieqmornitoring.components.ColorOk
import com.example.iaqieqmornitoring.components.ColorWarn
import com.example.iaqieqmornitoring.components.DarkDropdown
import com.example.iaqieqmornitoring.components.LabeledField
import com.example.iaqieqmornitoring.components.SectionCard
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.StatusPill
import com.example.iaqieqmornitoring.components.Stepper
import com.example.iaqieqmornitoring.components.ToggleRow
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.MissionManager
import com.example.iaqieqmornitoring.utils.MissionRoute
import com.example.iaqieqmornitoring.utils.SensorRepository
import com.example.iaqieqmornitoring.utils.TemiRepository
import com.example.iaqieqmornitoring.utils.ThresholdStore

@Composable
fun SettingsScreen(nav: NavController) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {

        TopBar()

        Row(modifier = Modifier.fillMaxSize()) {

            Sidebar(nav)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        RobotCard()
                        RoutesCard()
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        SensorLinkCard()
                        ThresholdsCard()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// temi robot + mission behaviour
// ---------------------------------------------------------------------------------------------
@Composable
private fun RobotCard() {
    val robot by TemiRepository.state.collectAsState()
    val simulate by AppSettings.simulateRobot.flow.collectAsState()
    val dwell by AppSettings.dwellSeconds.flow.collectAsState()
    val timeout by AppSettings.arrivalTimeoutSeconds.flow.collectAsState()
    val retries by AppSettings.retries.flow.collectAsState()
    val homeBase by AppSettings.homeBase.flow.collectAsState()
    val returnHome by AppSettings.returnHome.flow.collectAsState()
    val autoStart by AppSettings.autoStart.flow.collectAsState()
    var kioskNote by remember { mutableStateOf<String?>(null) }
    val mission by MissionManager.state.collectAsState()

    SectionCard("temi Robot & Mission") {

        StatusPill(
            text = when {
                robot.simulated -> "Simulated robot (not a real temi)"
                robot.ready -> "Connected to temi · ${robot.locations.size} saved locations"
                else -> "temi not connected"
            },
            color = when {
                robot.simulated -> ColorWarn
                robot.ready -> ColorOk
                else -> ColorBad
            }
        )

        ToggleRow("Simulated robot (for testing without temi)", simulate) {
            AppSettings.simulateRobot.set(it)
            TemiRepository.applyMode()
        }

        Stepper("Sampling time at each stop", dwell, { AppSettings.dwellSeconds.set(it) }, 0, 300, 5, " s")
        Stepper("Give up on a stop after", timeout, { AppSettings.arrivalTimeoutSeconds.set(it) }, 30, 900, 30, " s")
        Stepper("Retries when navigation fails", retries, { AppSettings.retries.set(it) }, 0, 5, 1, "")

        ToggleRow("Return to home base after the mission", returnHome) { AppSettings.returnHome.set(it) }

        ToggleRow("Start this app automatically when temi starts", autoStart) { on ->
            AppSettings.autoStart.set(on)
            kioskNote = if (on) {
                // Also ask temi's launcher to open this app at start-up (temi shows a confirmation).
                if (TemiRepository.requestKioskApp()) "Confirm on temi's screen if it asks."
                else "Saved. Connect to temi and switch this on again to also register with temi's launcher."
            } else null
        }
        (kioskNote ?: TemiRepository.isKioskApp()?.let {
            if (it) "temi's launcher will open this app when it starts." else "Not registered as temi's start-up app yet."
        })?.let { Text(it, color = ColorMuted, fontSize = 12.sp) }

        LabeledField(
            label = "Home base location name",
            value = homeBase,
            onChange = { AppSettings.homeBase.set(it) },
            modifier = Modifier.fillMaxWidth()
        )

        ActionButton(
            text = "Refresh locations from temi",
            onClick = { TemiRepository.refresh() },
            enabled = !mission.isActive,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------------------------
// routes
// ---------------------------------------------------------------------------------------------
@Composable
private fun RoutesCard() {
    val routes by AppSettings.routes.collectAsState()
    val selected by AppSettings.selectedRoute.flow.collectAsState()
    val robot by TemiRepository.state.collectAsState()
    val mission by MissionManager.state.collectAsState()

    val route = routes.firstOrNull { it.name == selected } ?: routes.first()

    var nameText by remember(route.name) { mutableStateOf(route.name) }
    var manualLocation by remember { mutableStateOf("") }

    fun setWaypoints(list: List<String>) {
        AppSettings.updateRoutes { all -> all.map { if (it.name == route.name) it.copy(waypoints = list) else it } }
    }

    val nameTaken = nameText != route.name && routes.any { it.name.equals(nameText.trim(), ignoreCase = true) }

    SectionCard("Routes & Locations") {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DarkDropdown(
                selected = route.name,
                options = routes.map { it.name },
                onSelect = { AppSettings.selectedRoute.set(it) },
                modifier = Modifier.weight(1f)
            )
            ActionButton("New", onClick = {
                var n = routes.size + 1
                while (routes.any { it.name == "Route $n" }) n++
                val name = "Route $n"
                AppSettings.updateRoutes { it + MissionRoute(name, emptyList()) }
                AppSettings.selectedRoute.set(name)
            })
            ActionButton(
                "Delete",
                onClick = { AppSettings.updateRoutes { all -> all.filter { it.name != route.name } } },
                color = Color(0xFFEF4444),
                enabled = routes.size > 1 && !mission.isActive
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LabeledField(
                label = "Route name",
                value = nameText,
                onChange = { nameText = it },
                modifier = Modifier.weight(1f),
                isError = nameTaken
            )
            ActionButton(
                "Rename",
                onClick = {
                    val newName = nameText.trim()
                    val old = route.name
                    AppSettings.updateRoutes { all -> all.map { if (it.name == old) it.copy(name = newName) else it } }
                    AppSettings.selectedRoute.set(newName)
                },
                enabled = nameText.isNotBlank() && nameText.trim() != route.name && !nameTaken && !mission.isActive
            )
        }

        Text("Stops, in driving order", color = ColorMuted, fontSize = 13.sp)

        if (route.waypoints.isEmpty()) {
            Text("No stops yet. Add temi locations below.", color = ColorWarn, fontSize = 13.sp)
        }

        route.waypoints.forEachIndexed { i, w ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorField, RoundedCornerShape(10.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}.  $w", color = Color.White, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val l = route.waypoints.toMutableList()
                        l.add(i - 1, l.removeAt(i))
                        setWaypoints(l)
                    },
                    enabled = i > 0
                ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", tint = Color.White) }
                IconButton(
                    onClick = {
                        val l = route.waypoints.toMutableList()
                        l.add(i + 1, l.removeAt(i))
                        setWaypoints(l)
                    },
                    enabled = i < route.waypoints.lastIndex
                ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", tint = Color.White) }
                IconButton(
                    onClick = { setWaypoints(route.waypoints.filterIndexed { idx, _ -> idx != i }) }
                ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = ColorBad) }
            }
        }

        if (robot.locations.isNotEmpty()) {
            DarkDropdown(
                selected = "",
                placeholder = "Add a saved temi location…",
                options = robot.locations,
                onSelect = { setWaypoints(route.waypoints + it) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "temi has not reported any saved locations. Type the exact location name instead:",
                color = ColorMuted,
                fontSize = 12.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabeledField(
                    label = "Location name",
                    value = manualLocation,
                    onChange = { manualLocation = it },
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    "Add",
                    onClick = {
                        setWaypoints(route.waypoints + manualLocation.trim())
                        manualLocation = ""
                    },
                    enabled = manualLocation.isNotBlank()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Raspberry Pi sensor link
// ---------------------------------------------------------------------------------------------
@Composable
private fun SensorLinkCard() {
    val simulate by AppSettings.simulateSensors.flow.collectAsState()
    val host by AppSettings.mqttHost.flow.collectAsState()
    val port by AppSettings.mqttPort.flow.collectAsState()
    val topic by AppSettings.mqttTopic.flow.collectAsState()
    val user by AppSettings.mqttUser.flow.collectAsState()
    val pass by AppSettings.mqttPass.flow.collectAsState()
    val link by SensorRepository.link.collectAsState()

    var portText by remember { mutableStateOf(port.toString()) }

    SectionCard("Raspberry Pi Sensors (MQTT)") {

        StatusPill(
            text = link.detail,
            color = when {
                link.mode.name == "SIMULATED" -> ColorWarn
                link.connected -> ColorOk
                link.connecting -> ColorWarn
                else -> ColorBad
            }
        )

        if (!simulate && link.connected) {
            Text(
                if (link.messages == 0) "No message received yet on this topic."
                else "Messages: ${link.messages} · last topic: ${link.lastTopic}\n" +
                    "Last payload: ${link.lastPayload}" +
                    (if (link.problem.isNotEmpty()) "\nProblem: ${link.problem}" else "\nSensors seen: ${link.fields.size}/${SensorRepository.ALL_FIELDS.size}"),
                color = if (link.problem.isNotEmpty() || link.messages == 0) ColorWarn else ColorMuted,
                fontSize = 11.sp
            )
        }

        ToggleRow("Simulated sensors (for testing without the Pi)", simulate) {
            AppSettings.simulateSensors.set(it)
            SensorRepository.applySettings()
        }

        if (!simulate) {
            LabeledField(
                label = "Broker host / IP address",
                value = host,
                onChange = { AppSettings.mqttHost.set(it) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(
                    label = "Port",
                    value = portText,
                    onChange = {
                        portText = it
                        it.toIntOrNull()?.let { p -> if (p in 1..65535) AppSettings.mqttPort.set(p) }
                    },
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
                LabeledField(
                    label = "Topic",
                    value = topic,
                    onChange = { AppSettings.mqttTopic.set(it) },
                    modifier = Modifier.weight(2f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(
                    label = "Username (optional)",
                    value = user,
                    onChange = { AppSettings.mqttUser.set(it) },
                    modifier = Modifier.weight(1f)
                )
                LabeledField(
                    label = "Password",
                    value = pass,
                    onChange = { AppSettings.mqttPass.set(it) },
                    password = true,
                    modifier = Modifier.weight(1f)
                )
            }
            ActionButton(
                text = "Save & Reconnect",
                onClick = { SensorRepository.applySettings() },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "The Pi should publish JSON to the topic, e.g.\n" +
                    "{\"temp\":24.1,\"humidity\":55,\"co2\":620,\"pm25\":9,\"pm10\":14,\n" +
                    " \"tvoc\":0.2,\"hcho\":0.02,\"light\":310,\"sound\":42}",
                color = ColorMuted,
                fontSize = 11.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// IAQ thresholds
// ---------------------------------------------------------------------------------------------
private fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

@Composable
private fun ThresholdsCard() {
    val overrides by ThresholdStore.overrides.collectAsState()
    var sensorKey by remember { mutableStateOf("CO2") }

    val current = overrides[sensorKey] ?: ThresholdStore.defaults[sensorKey] ?: listOf(0f, 25f, 50f, 75f)
    val fields = remember(sensorKey, overrides) { current.map { fmt(it) }.toMutableStateList() }
    var error by remember(sensorKey) { mutableStateOf<String?>(null) }

    SectionCard("IAQ Thresholds") {

        Text(
            "Four increasing breakpoints per sensor define its five quality bands.",
            color = ColorMuted,
            fontSize = 13.sp
        )

        DarkDropdown(
            selected = sensorKey + if (ThresholdStore.isCustom(sensorKey)) "  (customised)" else "",
            options = ThresholdStore.defaults.keys.toList(),
            onSelect = { sensorKey = it },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEachIndexed { i, text ->
                LabeledField(
                    label = "Break ${i + 1}",
                    value = text,
                    onChange = { fields[i] = it },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        error?.let { Text(it, color = ColorBad, fontSize = 13.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                "Save",
                onClick = {
                    val values = fields.map { it.trim().replace(',', '.').toFloatOrNull() }
                    error = if (values.any { it == null }) "Enter valid numbers"
                    else ThresholdStore.set(sensorKey, values.map { it!! })
                },
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                "Reset to default",
                onClick = {
                    ThresholdStore.reset(sensorKey)
                    error = null
                },
                color = Color(0xFF475569),
                enabled = ThresholdStore.isCustom(sensorKey),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
