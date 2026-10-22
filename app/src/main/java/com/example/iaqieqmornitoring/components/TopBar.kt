package com.example.iaqieqmornitoring.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.iaqieqmornitoring.utils.SensorRepository
import com.example.iaqieqmornitoring.utils.TemiRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.example.iaqieqmornitoring.R

// =======================
// 🕒 TIME CHIP - time of the last sensor update
// =======================
@Composable
fun TimeChip() {
    val sensor by SensorRepository.data.collectAsState()
    val text = remember(sensor.timestampMs) {
        if (sensor.timestampMs == 0L) "--:--:--"
        else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sensor.timestampMs))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B).copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(10.dp),
        modifier = Modifier
            .border(
                1.dp,
                Color(0xFF334155),
                RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    "Updated",
                    color = Color(0xFF64748B),
                    fontSize = 14.sp
                )

                Text(
                    text,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// =======================
// 🔋 BATTERY CHIP - temi's battery
// =======================
@Composable
fun BatteryChip() {
    val robot by TemiRepository.state.collectAsState()
    val pct = robot.batteryPercent

    val tint = when {
        pct == null -> Color(0xFF64748B)
        pct < 15 -> Color(0xFFFF5252)
        pct < 30 -> Color(0xFFFFC107)
        else -> Color(0xFF00FFAA)
    }
    val icon = when {
        robot.charging -> Icons.Default.BatteryChargingFull
        pct == null -> Icons.Default.BatteryUnknown
        pct < 15 -> Icons.Default.BatteryAlert
        else -> Icons.Default.BatteryFull
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B).copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(10.dp),
        modifier = Modifier
            .border(
                1.dp,
                Color(0xFF334155),
                RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(26.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                if (pct == null) "--%" else "$pct%",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// =======================
// 🔥 TOP BAR (PRO VERSION)
// =======================
@Composable
fun TopBar() {
    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF000000),
                            Color(0xFF020617),
                            Color(0xFF0F172A)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            // =======================
            // 🔥 LEFT: LOGO + TITLE
            // =======================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {

                Image(
                    painter = painterResource(id = R.drawable.university_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(100.dp)
                        .border(
                            1.dp,
                            Color(0xFF334155),
                            RoundedCornerShape(50)
                        )
                )

                Spacer(modifier = Modifier.width(20.dp))

                Column {
                    Text(
                        "leslieornot-test",
                        color = Color.White,
                        fontSize = 45.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        "Indoor Air Quality System",
                        color = Color(0xFF94A3B8),
                        fontSize = 14.sp
                    )
                }
            }

            // =======================
            // 🔥 RIGHT: STATUS
            // =======================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimeChip()
                BatteryChip()
            }
        }

        // =======================
        // 🔥 DIVIDER (Glow Line)
        // =======================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFF334155),
                            Color(0xFF64748B),
                            Color(0xFF334155),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}