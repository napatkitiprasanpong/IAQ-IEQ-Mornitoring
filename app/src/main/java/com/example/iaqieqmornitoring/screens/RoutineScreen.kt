package com.example.iaqieqmornitoring.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.iaqieqmornitoring.components.ColorField
import com.example.iaqieqmornitoring.components.ColorMuted
import com.example.iaqieqmornitoring.components.ColorOk
import com.example.iaqieqmornitoring.components.ColorWarn
import com.example.iaqieqmornitoring.components.DarkDropdown
import com.example.iaqieqmornitoring.components.LabeledField
import com.example.iaqieqmornitoring.components.SectionCard
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.Stepper
import com.example.iaqieqmornitoring.components.StatusPill
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.DEFAULT_DURATION_S
import com.example.iaqieqmornitoring.utils.DEFAULT_INTERVAL_S
import com.example.iaqieqmornitoring.utils.DURATION_OPTIONS_S
import com.example.iaqieqmornitoring.utils.MissionRoute
import com.example.iaqieqmornitoring.utils.RoutineType
import com.example.iaqieqmornitoring.utils.SheetsUploader
import com.example.iaqieqmornitoring.utils.StopPlan
import com.example.iaqieqmornitoring.utils.intervalsFor
import com.example.iaqieqmornitoring.utils.secondsLabel
import com.example.iaqieqmornitoring.utils.DEFAULT_ROUTINE_MESSAGE
import com.example.iaqieqmornitoring.utils.MissionManager
import com.example.iaqieqmornitoring.utils.Routine
import com.example.iaqieqmornitoring.utils.RoutineManager
import com.example.iaqieqmornitoring.utils.RoutinePhase
import com.example.iaqieqmornitoring.utils.TempMission
import com.example.iaqieqmornitoring.utils.TemiRepository
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeText(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

private fun parseTime(text: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
}

private fun countdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun RoutineScreen(nav: NavController) {

    val routines by AppSettings.routines.collectAsState()
    val routes by AppSettings.routes.collectAsState()
    val snoozeMinutes by AppSettings.routineSnoozeMinutes.flow.collectAsState()
    val promptTimeout by AppSettings.routinePromptTimeoutSeconds.flow.collectAsState()
    val state by RoutineManager.state.collectAsState()
    val mission by MissionManager.state.collectAsState()
    val robot by TemiRepository.state.collectAsState()

    // Ticks once a second so the timers count down on screen.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    var showForm by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }

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

                // ------------------------ LEFT: live status ------------------------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    SectionCard("Routine status") {
                        val (label, color) = when (state.phase) {
                            RoutinePhase.IDLE -> "Idle" to ColorMuted
                            RoutinePhase.GOING -> "Driving" to ColorAccent
                            RoutinePhase.ASKING -> "Waiting for answer" to ColorWarn
                            RoutinePhase.SAMPLING -> "Collecting data" to ColorAccent
                            RoutinePhase.RETURNING -> "Returning to home base" to ColorAccent
                            RoutinePhase.WAITING -> "Waiting at home base" to ColorWarn
                            RoutinePhase.UPLOADING -> "Uploading to Google Sheets" to ColorWarn
                            RoutinePhase.DONE -> "Done" to ColorOk
                            RoutinePhase.STOPPED -> "Stopped" to ColorMuted
                            RoutinePhase.FAILED -> "Failed" to ColorBad
                        }
                        StatusPill(label, color)

                        if (state.routineName.isNotBlank()) {
                            Text(state.routineName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (state.phase != RoutinePhase.IDLE) {
                            Text(state.message, color = Color.White, fontSize = 14.sp)
                        }
                        state.error?.let { Text(it, color = ColorBad, fontSize = 13.sp) }
                        runError?.let { Text(it, color = ColorBad, fontSize = 13.sp) }

                        if (state.isActive) {
                            ActionButton(
                                text = "Stop routine",
                                onClick = { RoutineManager.stop() },
                                color = Color(0xFFEF4444),
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            )
                        }
                    }

                    SectionCard("Temp missions") {
                        if (state.tempMissions.isEmpty()) {
                            Text(
                                "None. A temp mission is created each time someone answers “Come back in $snoozeMinutes min”.",
                                color = ColorMuted,
                                fontSize = 13.sp
                            )
                        }
                        state.tempMissions.forEach { TempMissionRow(it, nowMs) }
                    }

                    SectionCard("History") {
                        if (state.log.isEmpty()) {
                            Text("Nothing yet.", color = ColorMuted, fontSize = 13.sp)
                        }
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        state.log.takeLast(12).reversed().forEach {
                            Text(
                                "${fmt.format(Date(it.timeMs))}  ${it.text}",
                                color = ColorMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // ------------------------ RIGHT: schedule ------------------------
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    SectionCard("Routines") {
                        Text(
                            "Free style routine: every day at the set time temi drives the route and, at each stop, " +
                                "speaks the message and shows a popup.\n" +
                                "IAQ-IEQ routine: at each stop temi collects sensor data for the time you choose, " +
                                "saves it on the tablet, and uploads everything to Google Sheets at the end.\n" +
                                "The app has to be open for a routine to start.",
                            color = ColorMuted,
                            fontSize = 13.sp
                        )

                        if (routines.isEmpty()) {
                            Text("No routines yet.", color = Color.White, fontSize = 14.sp)
                        }

                        routines.forEach { r ->
                            RoutineRow(
                                routine = r,
                                canRun = !state.isActive && !mission.isActive && robot.ready,
                                onToggle = { on ->
                                    AppSettings.updateRoutines { list ->
                                        list.map { if (it.id == r.id) it.copy(enabled = on) else it }
                                    }
                                },
                                onRun = { runError = RoutineManager.runNow(r.id) },
                                onDelete = {
                                    AppSettings.updateRoutines { list -> list.filter { it.id != r.id } }
                                }
                            )
                        }

                        if (!showForm) {
                            ActionButton(
                                text = "Add routine",
                                onClick = { showForm = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            )
                        } else {
                            NewRoutineForm(
                                routes = routes,
                                onCancel = { showForm = false },
                                onSave = { routine ->
                                    AppSettings.updateRoutines { it + routine }
                                    showForm = false
                                }
                            )
                        }
                    }

                    SectionCard("Routine settings") {
                        Stepper(
                            label = "Come back after",
                            value = snoozeMinutes,
                            onChange = { AppSettings.routineSnoozeMinutes.set(it) },
                            min = 1, max = 60, suffix = " min"
                        )
                        Stepper(
                            label = "Popup waits for an answer",
                            value = promptTimeout,
                            onChange = { AppSettings.routinePromptTimeoutSeconds.set(it) },
                            min = 10, max = 300, step = 10, suffix = " s"
                        )
                        Text(
                            "If nobody answers in time, it counts as “Come back in $snoozeMinutes min”. " +
                                "Locations, home base, retries and the navigation timeout come from Settings.",
                            color = ColorMuted,
                            fontSize = 12.sp
                        )
                    }

                    SheetsCard()
                }
            }
        }
    }
}

@Composable
private fun TempMissionRow(t: TempMission, nowMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C33), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(t.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "→ ${t.location}" + if (t.round > 1) " · round ${t.round}" else "",
                color = ColorMuted,
                fontSize = 12.sp
            )
        }
        Text(
            if (t.dueAtMs == 0L) "timer starts at home base" else "in ${countdown(t.dueAtMs - nowMs)}",
            color = ColorWarn,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    canRun: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C33), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            timeText(routine.hour, routine.minute),
            color = if (routine.enabled) ColorAccent else ColorMuted,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                routine.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                (if (routine.type == RoutineType.IAQ_IEQ) "IAQ-IEQ routine" else "Free style routine") +
                    " · Route: ${routine.routeName.ifBlank { "—" }}",
                color = ColorMuted,
                fontSize = 12.sp
            )
            if (routine.type == RoutineType.IAQ_IEQ) {
                Text(
                    routine.plans.joinToString(" · ") {
                        "${it.location} ${secondsLabel(it.durationS)}/${secondsLabel(it.intervalS)}"
                    }.ifBlank { "—" },
                    color = ColorMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("“${routine.message}”", color = ColorMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(
            checked = routine.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ColorAccent)
        )
        Spacer(Modifier.width(10.dp))
        ActionButton(text = "Run now", onClick = onRun, enabled = canRun, color = Color(0xFF10B981))
        Spacer(Modifier.width(8.dp))
        ActionButton(text = "Delete", onClick = onDelete, color = Color(0xFFEF4444))
    }
}

private const val FREE_STYLE_LABEL = "Free style routine"
private const val IAQ_IEQ_LABEL = "IAQ-IEQ routine"

@Composable
private fun NewRoutineForm(
    routes: List<MissionRoute>,
    onCancel: () -> Unit,
    onSave: (Routine) -> Unit
) {
    var type by remember { mutableStateOf(RoutineType.FREE_STYLE) }
    var name by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("08:00") }
    var route by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(DEFAULT_ROUTINE_MESSAGE) }

    val routeNames = routes.map { it.name }
    val chosenRoute = route.ifBlank { routeNames.firstOrNull() ?: "" }
    val waypoints = routes.firstOrNull { it.name == chosenRoute }?.waypoints ?: emptyList()
    val parsed = parseTime(time)

    // One plan (duration + interval) per stop of the chosen route; reset when another route is picked.
    val plans = remember(chosenRoute, waypoints) {
        waypoints.map { StopPlan(it, DEFAULT_DURATION_S, DEFAULT_INTERVAL_S) }.toMutableStateList()
    }
    val isIaq = type == RoutineType.IAQ_IEQ

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorField, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("New routine", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        Text("Type", color = ColorMuted, fontSize = 13.sp)
        DarkDropdown(
            selected = if (isIaq) IAQ_IEQ_LABEL else FREE_STYLE_LABEL,
            options = listOf(FREE_STYLE_LABEL, IAQ_IEQ_LABEL),
            onSelect = { type = if (it == IAQ_IEQ_LABEL) RoutineType.IAQ_IEQ else RoutineType.FREE_STYLE }
        )

        LabeledField(label = "Name (optional)", value = name, onChange = { name = it }, modifier = Modifier.fillMaxWidth())
        LabeledField(
            label = "Time every day (24 h, HH:MM)",
            value = time,
            onChange = { time = it },
            isError = parsed == null,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Mission (route)", color = ColorMuted, fontSize = 13.sp)
        DarkDropdown(selected = chosenRoute, options = routeNames, onSelect = { route = it })

        if (!isIaq) {
            LabeledField(
                label = "What temi says and shows at each stop",
                value = message,
                onChange = { message = it },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("Data collection at each stop", color = ColorMuted, fontSize = 13.sp)
            if (plans.isEmpty()) {
                Text("This route has no locations yet. Add some in Settings.", color = ColorWarn, fontSize = 12.sp)
            }
            plans.forEachIndexed { i, plan ->
                PlanRow(index = i + 1, plan = plan, onChange = { plans[i] = it })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                text = "Save",
                enabled = parsed != null && chosenRoute.isNotBlank() && (!isIaq || plans.isNotEmpty()),
                onClick = {
                    val (h, m) = parsed ?: return@ActionButton
                    val defaultName = (if (isIaq) "IAQ-IEQ " else "Routine ") + timeText(h, m)
                    onSave(
                        Routine(
                            id = System.currentTimeMillis().toString(),
                            name = name.trim().ifBlank { defaultName },
                            hour = h,
                            minute = m,
                            routeName = chosenRoute,
                            message = message.trim().ifBlank { DEFAULT_ROUTINE_MESSAGE },
                            type = type,
                            plans = if (isIaq) plans.toList() else emptyList()
                        )
                    )
                },
                color = Color(0xFF10B981)
            )
            ActionButton(text = "Cancel", onClick = onCancel, color = Color(0xFF475569))
        }
    }
}

/** Duration + interval pickers for one stop of an IAQ-IEQ routine. */
@Composable
private fun PlanRow(index: Int, plan: StopPlan, onChange: (StopPlan) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C33), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("$index. ${plan.location}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Collect for", color = ColorMuted, fontSize = 12.sp)
                DarkDropdown(
                    selected = secondsLabel(plan.durationS),
                    options = DURATION_OPTIONS_S.map { secondsLabel(it) },
                    onSelect = { label ->
                        val d = DURATION_OPTIONS_S.firstOrNull { secondsLabel(it) == label } ?: plan.durationS
                        val allowed = intervalsFor(d)
                        onChange(plan.copy(durationS = d, intervalS = if (plan.intervalS in allowed) plan.intervalS else allowed.last()))
                    }
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reading every", color = ColorMuted, fontSize = 12.sp)
                DarkDropdown(
                    selected = secondsLabel(plan.intervalS),
                    options = intervalsFor(plan.durationS).map { secondsLabel(it) },
                    onSelect = { label ->
                        val iv = intervalsFor(plan.durationS).firstOrNull { secondsLabel(it) == label } ?: plan.intervalS
                        onChange(plan.copy(intervalS = iv))
                    }
                )
            }
        }
        Text("→ ${plan.sampleCount} readings at this stop", color = ColorMuted, fontSize = 12.sp)
    }
}

/** Google Sheets connection (URL + token), connection test and manual upload of data still on the tablet. */
@Composable
private fun SheetsCard() {
    val url by AppSettings.sheetsUrl.flow.collectAsState()
    val token by AppSettings.sheetsToken.flow.collectAsState()
    val up by SheetsUploader.state.collectAsState()
    val routine by RoutineManager.state.collectAsState()

    LaunchedEffect(Unit) { SheetsUploader.refreshPending() }

    SectionCard("Google Sheets upload (IAQ-IEQ routine)") {
        Text(
            "Paste the web app URL from the Google Apps Script (apps_script/Code.gs) and the same secret token " +
                "you put in that script. Readings go to the “iaq-ieq data” folder in your Google Drive.",
            color = ColorMuted,
            fontSize = 12.sp
        )
        LabeledField(
            label = "Apps Script web app URL",
            value = url,
            onChange = { AppSettings.sheetsUrl.set(it.trim()) },
            modifier = Modifier.fillMaxWidth()
        )
        LabeledField(
            label = "Secret token",
            value = token,
            onChange = { AppSettings.sheetsToken.set(it.trim()) },
            password = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                text = "Test connection",
                onClick = { SheetsUploader.testAsync() },
                enabled = !up.busy && url.isNotBlank(),
                color = Color(0xFF475569)
            )
            ActionButton(
                text = "Upload pending now",
                onClick = { SheetsUploader.uploadNowAsync() },
                enabled = !up.busy && url.isNotBlank() && up.pendingFiles > 0 && !routine.isActive,
                color = Color(0xFF10B981)
            )
        }
        Text(
            "Waiting on the tablet: ${up.pendingFiles} file(s)",
            color = if (up.pendingFiles > 0) ColorWarn else ColorMuted,
            fontSize = 12.sp
        )
        if (up.message.isNotBlank()) {
            Text(
                up.message,
                color = when (up.ok) { true -> ColorOk; false -> ColorBad; null -> ColorMuted },
                fontSize = 12.sp
            )
        }
    }
}
