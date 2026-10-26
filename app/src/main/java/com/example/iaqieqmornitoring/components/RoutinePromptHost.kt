package com.example.iaqieqmornitoring.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.iaqieqmornitoring.utils.PromptAnswer
import com.example.iaqieqmornitoring.utils.RoutineManager

/**
 * The routine popup. It is placed once at the root of the app, so it appears on top of whichever
 * tab is open when temi reaches a stop. It cannot be dismissed by tapping outside or pressing Back:
 * only the two buttons (or the answer timeout) close it.
 */
@Composable
fun RoutinePromptHost() {
    val prompt by RoutineManager.prompt.collectAsState()
    val p = prompt ?: return

    val minutesText = if (p.snoozeMinutes == 1) "1 min" else "${p.snoozeMinutes} mins"

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        androidx.compose.material3.Card(
            shape = RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = ColorPanel)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "${p.routineName} · ${p.location}",
                    color = ColorMuted,
                    fontSize = 14.sp
                )
                Text(
                    p.text,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "No answer in ${p.secondsLeft}s counts as \"Come back in $minutesText\"",
                    color = ColorMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ActionButton(
                        text = "Yes",
                        onClick = { RoutineManager.answer(PromptAnswer.YES) },
                        color = Color(0xFF10B981),
                        modifier = Modifier.weight(1f).height(64.dp)
                    )
                    ActionButton(
                        text = "Come back in $minutesText",
                        onClick = { RoutineManager.answer(PromptAnswer.SNOOZE) },
                        color = Color(0xFF2563EB),
                        modifier = Modifier.weight(1f).height(64.dp)
                    )
                }
            }
        }
    }
}
