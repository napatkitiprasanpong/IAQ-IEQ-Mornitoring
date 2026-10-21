package com.example.iaqieqmornitoring.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.ActionButton
import com.example.iaqieqmornitoring.components.ColorAccent
import com.example.iaqieqmornitoring.components.ColorBad
import com.example.iaqieqmornitoring.components.ColorMuted
import com.example.iaqieqmornitoring.components.ColorOk
import com.example.iaqieqmornitoring.components.ColorWarn
import com.example.iaqieqmornitoring.components.DarkDropdown
import com.example.iaqieqmornitoring.components.SectionCard
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.StatusPill
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.MissionManager
import com.example.iaqieqmornitoring.utils.MissionStopResult
import com.example.iaqieqmornitoring.utils.MissionStatus
import com.example.iaqieqmornitoring.utils.SensorRepository
import com.example.iaqieqmornitoring.utils.StopOutcome
import com.example.iaqieqmornitoring.utils.TemiRepository
import com.example.iaqieqmornitoring.utils.computeIAQ
import com.example.iaqieqmornitoring.utils.getColorFromLevel
import com.example.iaqieqmornitoring.utils.getLabel
import com.example.iaqieqmornitoring.utils.getLevelFromScore

@Composable
fun MissionScreen(nav: NavController) {

    val routes by AppSettings.routes.collectAsState()
    val selected by AppSettings.selectedRoute.flow.collectAsState()
    val dwell by AppSettings.dwellSeconds.flow.collectAsState()
    val returnHome by AppSettings.returnHome.flow.collectAsState()
    val mission by MissionManager.state.collectAsState()
    val robot by TemiRepository.state.collectAsState()
    val sensor by SensorRepository.data.collectAsState()
    val link by SensorRepository.link.collectAsState()

    val route = routes.firstOrNull { it.name == selected } ?: routes.firstOrNull()
    val running = mission.isActive
    val canStart = route != null && route.waypoints.isNotEmpty() && robot.ready

    // Show the live mission if it belongs to the selected route, otherwise the planned stops.
    val rows: List<MissionStopResult> =
        if (mission.stops.isNotEmpty() && mission.routeName == route?.name) mission.stops
        else route?.waypoints?.mapIndexed { i, w -> MissionStopResult(i, w, StopOutcome.PENDING) } ?: emptyList()

    val iaq = computeIAQ(sensor)
    val iaqColor = getColorFromLevel(getLevelFromScore(iaq.toFloat()))
    val iaqLabel = getLabel(getLevelFromScore(iaq.toFloat()))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {

        TopBar()

        Row(modifier = Modifier.fillMaxSize()) {

            Sidebar(nav)

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ------------------------ LEFT: control ------------------------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    SectionCard("Mission Control") {

                        StatusPill(
                            text = when {
                                robot.simulated -> "Simulated robot"
                                robot.ready -> "temi connected"
                                else -> "temi not connected"
                            },
                            color = when {
                                robot.simulated -> ColorWarn
                                robot.ready -> ColorOk
                                else -> ColorBad
                            }
                        )

                        Text("Inspection route", color = ColorMuted, fontSize = 13.sp)

                        DarkDropdown(
                            selected = route?.name ?: "",
                            options = routes.map { it.name },
                            onSelect = { AppSettings.selectedRoute.set(it) },
                            enabled = !running
                        )

                        Text(
                            if (route == null || route.waypoints.isEmpty())
                                "This route has no locations yet. Add them in Settings → Routes."
                            else
                                "${route.waypoints.size} stops · ${dwell}s sampling per stop" +
                                    if (returnHome) " · returns to home base" else "",
                            color = ColorMuted,
                            fontSize = 13.sp
                        )

                        ActionButton(
                            text = if (running) "Stop Mission" else "Start Mission",
                            onClick = {
                                if (running) MissionManager.stop()
                                else if (route != null) MissionManager.start(route.name)
                            },
                            color = if (running) Color(0xFFEF4444) else Color(0xFF10B981),
                            enabled = running || canStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        )

                        mission.error?.let {
                            Text(it, color = ColorBad, fontSize = 13.sp)
                        }
                        if (mission.status != MissionStatus.IDLE) {
                            Text(
                                mission.message,
                                color = when (mission.status) {
                                    MissionStatus.FAILED -> ColorBad
                                    MissionStatus.COMPLETED -> ColorOk
                                    else -> Color.White
                                },
                                fontSize = 14.sp
                            )
                        }
                        mission.savedTo?.let {
                            Text("Results saved to:\n$it", color = ColorMuted, fontSize = 11.sp)
                        }
                    }

                    SectionCard("Live Readings") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("IAQ score", color = ColorMuted, fontSize = 13.sp)
                                Text(
                                    "$iaq",
                                    color = iaqColor,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(iaqLabel, color = iaqColor, fontSize = 14.sp)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Temp  ${"%.1f".format(sensor.temp)} °C", color = Color.White, fontSize = 14.sp)
                                Text("Humidity  ${"%.0f".format(sensor.humidity)} %", color = Color.White, fontSize = 14.sp)
                                Text("CO₂  ${"%.0f".format(sensor.co2)} ppm", color = Color.White, fontSize = 14.sp)
                                Text("PM2.5  ${"%.1f".format(sensor.pm25)} µg/m³", color = Color.White, fontSize = 14.sp)
                            }
                        }
                        Text(
                            link.detail,
                            color = if (link.connected) ColorMuted else ColorWarn,
                            fontSize = 12.sp
                        )
                    }
                }

                // ------------------------ RIGHT: progress / results ------------------------
                Column(
                    modifier = Modifier
                        .weight(1.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionCard(
                        if (mission.stops.isNotEmpty() && mission.routeName == route?.name)
                            "Progress · ${mission.routeName}"
                        else "Planned stops"
                    ) {
                        if (rows.isEmpty()) {
                            Text("No stops to show.", color = ColorMuted, fontSize = 14.sp)
                        }
                        rows.forEach { stop ->
                            StopRow(
                                stop = stop,
                                isCurrent = running && mission.currentIndex == stop.index,
                                dwellRemaining = if (mission.currentIndex == stop.index) mission.dwellRemaining else 0
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopRow(stop: MissionStopResult, isCurrent: Boolean, dwellRemaining: Int) {

    val color = when (stop.outcome) {
        StopOutcome.PENDING -> Color.Gray
        StopOutcome.NAVIGATING -> ColorAccent
        StopOutcome.SAMPLING -> ColorWarn
        StopOutcome.DONE -> ColorOk
        StopOutcome.FAILED -> ColorBad
    }
    val label = when (stop.outcome) {
        StopOutcome.PENDING -> "Pending"
        StopOutcome.NAVIGATING -> "Driving…"
        StopOutcome.SAMPLING -> if (dwellRemaining > 0) "Sampling ${dwellRemaining}s" else "Sampling"
        StopOutcome.DONE -> "Done"
        StopOutcome.FAILED -> "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) Color(0xFF111C33) else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("${stop.index + 1}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                stop.location,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val r = stop.readings
            if (stop.outcome == StopOutcome.DONE && r != null) {
                Text(
                    "IAQ ${stop.iaq} · ${"%.1f".format(r.temp)} °C · ${"%.0f".format(r.humidity)} % · " +
                        "CO₂ ${"%.0f".format(r.co2)} · PM2.5 ${"%.1f".format(r.pm25)}",
                    color = ColorMuted,
                    fontSize = 12.sp
                )
            }
            if (stop.note.isNotBlank()) {
                Text(
                    stop.note,
                    color = if (stop.outcome == StopOutcome.FAILED) ColorBad else ColorWarn,
                    fontSize = 12.sp
                )
            }
        }

        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
