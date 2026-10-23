package com.example.iaqieqmornitoring.screens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.SignLayout
import com.example.iaqieqmornitoring.utils.SignOverlay
import com.example.iaqieqmornitoring.utils.SignRecognizer
import com.example.iaqieqmornitoring.utils.SignStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max

private val SlateText = Color(0xFF94A3B8)
private val GreenOk = Color(0xFF00FFAA)
private val AmberWarn = Color(0xFFFFC107)
private val RedErr = Color(0xFFFF5252)
private val BlueOn = Color(0xFF2563EB)

// ==========================
// 🤟 SIGN LANGUAGE SCREEN
// ==========================
/**
 * Full-screen camera with a caption bar. One sign (word) at a time is recognised and, if "Speak" is on,
 * said aloud by temi. See [SignRecognizer] for how a sign is delimited and accepted.
 */
@Composable
fun SignLanguageScreen(nav: NavController) {

    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }

    val state by SignRecognizer.state.collectAsState()
    val speak by SignRecognizer.speakEnabled.collectAsState()
    val flip by SignRecognizer.flipInput.collectAsState()

    // ---- camera permission (asked once when the tab opens) ----
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---- models are loaded when the tab opens and freed when it closes ----
    LaunchedEffect(Unit) {
        val appContext = context.applicationContext
        withContext(Dispatchers.Default) { SignRecognizer.prepare(appContext) }
    }
    DisposableEffect(Unit) {
        onDispose { SignRecognizer.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {

        TopBar()

        Row(modifier = Modifier.fillMaxSize()) {

            Sidebar(nav)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
            ) {

                // ---- camera ----
                when {
                    !hasPermission -> CenterMessage(
                        title = "Camera access needed",
                        body = if (permissionDenied)
                            "Permission was denied. Allow Camera for this app in Android Settings > Apps, " +
                                "then reopen this tab."
                        else "Allow camera access to recognise signs."
                    ) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant camera access")
                        }
                    }

                    owner == null -> CenterMessage(
                        title = "Camera unavailable",
                        body = "The screen could not attach to the activity lifecycle."
                    )

                    else -> SignCameraView(
                        owner = owner,
                        flipInput = flip,
                        overlay = state.overlay,
                        signing = state.status == SignStatus.SIGNING,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // ---- model problem (wrong Android version, missing files, ...) ----
                state.error?.let { msg ->
                    CenterMessage(title = "Sign recognition unavailable", body = msg)
                }

                // ---- status badge (top left) ----
                val (badgeText, badgeColor) = when (state.status) {
                    SignStatus.IDLE -> "STARTING" to SlateText
                    SignStatus.LOADING -> "LOADING MODELS" to AmberWarn
                    SignStatus.READY -> "READY" to GreenOk
                    SignStatus.SIGNING -> "SIGNING" to GreenOk
                    SignStatus.THINKING -> "THINKING" to AmberWarn
                    SignStatus.ERROR -> "ERROR" to RedErr
                }
                Badge(
                    text = badgeText,
                    color = badgeColor,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                )

                // ---- toggles (top right) ----
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TogglePill(label = if (speak) "Speak: ON" else "Speak: OFF", on = speak) {
                        SignRecognizer.speakEnabled.value = !speak
                    }
                    TogglePill(label = "Flip input", on = flip) {
                        SignRecognizer.flipInput.value = !flip
                    }
                }

                // ---- caption bar (bottom) ----
                CaptionBar(
                    caption = state.caption,
                    confidence = state.confidence,
                    message = state.message,
                    guesses = state.guesses.joinToString("  \u00b7  ") {
                        "${SignRecognizer.pretty(it.label)} ${(it.confidence * 100).toInt()}%"
                    },
                    history = state.history,
                    stats = if (state.fps > 0) "${state.fps} fps \u00b7 ${state.inferenceMs} ms" else "",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
        }
    }
}

// ==========================
// 💬 CAPTION BAR
// ==========================
@Composable
private fun CaptionBar(
    caption: String,
    confidence: Float,
    message: String,
    guesses: String,
    history: List<String>,
    stats: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = caption.ifBlank { "\u2014" },
                    color = if (caption.isBlank()) SlateText else Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (caption.isNotBlank()) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${(confidence * 100).toInt()}%",
                        color = GreenOk,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            Text(
                text = message.ifBlank { "Raise your hand and sign one word." },
                color = SlateText,
                fontSize = 14.sp
            )
            if (guesses.isNotBlank()) {
                Text(text = "Top guesses: $guesses", color = Color(0xFF64748B), fontSize = 12.sp)
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            if (history.isNotEmpty()) {
                Text(
                    text = history.joinToString("  "),
                    color = Color(0xFFCBD5E1),
                    fontSize = 16.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2
                )
            }
            if (stats.isNotBlank()) {
                Text(text = stats, color = Color(0xFF64748B), fontSize = 12.sp)
            }
        }
    }
}

// ==========================
// 📷 CAMERA (CameraX) + LANDMARK OVERLAY
// ==========================
@Composable
private fun SignCameraView(
    owner: LifecycleOwner,
    flipInput: Boolean,
    overlay: SignOverlay?,
    signing: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var frontCamera by remember { mutableStateOf(true) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER   // the preview fills the whole panel
        }
    }

    DisposableEffect(owner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> return@addListener
                }
                frontCamera = selector == CameraSelector.DEFAULT_FRONT_CAMERA

                @Suppress("DEPRECATION")
                val pv = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                pv.setSurfaceProvider(previewView.surfaceProvider)
                preview = pv

                // Newest frame only, RGBA so it converts to a Bitmap
                @Suppress("DEPRECATION")
                val ia = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                ia.setAnalyzer(analysisExecutor, SignRecognizer.createAnalyzer())
                analysis = ia

                p.unbindAll()
                p.bindToLifecycle(owner, selector, pv, ia)
            } catch (_: Exception) {
                // The screen simply stays black; there is nothing more useful to show here.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            analysis?.let { provider?.unbind(it) }
            preview?.let { provider?.unbind(it) }
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // The preview shows the front camera mirrored, the analysed frame is not mirrored unless "Flip input"
        // is on, so the overlay is mirrored exactly when exactly one of the two applies.
        LandmarkOverlay(
            overlay = overlay,
            mirrored = frontCamera != flipInput,
            signing = signing,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private val HandChains = listOf(
    listOf(0, 1, 2, 3, 4), listOf(0, 5, 6, 7, 8), listOf(0, 9, 10, 11, 12),
    listOf(0, 13, 14, 15, 16), listOf(0, 17, 18, 19, 20), listOf(5, 9, 13, 17)
)
private val ArmChains = listOf(listOf(11, 12), listOf(11, 13, 15), listOf(12, 14, 16))

/** Draws both hands and the arms on top of the preview (which is cropped to fill, FILL_CENTER). */
@Composable
private fun LandmarkOverlay(
    overlay: SignOverlay?,
    mirrored: Boolean,
    signing: Boolean,
    modifier: Modifier = Modifier
) {
    if (overlay == null) return
    Canvas(modifier = modifier) {
        val iw = overlay.imageWidth.toFloat()
        val ih = overlay.imageHeight.toFloat()
        val scale = max(size.width / iw, size.height / ih)
        val dw = iw * scale
        val dh = ih * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f

        fun at(index: Int): Offset? {
            val x = overlay.landmarks[index * 3]
            val y = overlay.landmarks[index * 3 + 1]
            if (x.isNaN() || y.isNaN()) return null
            val xx = if (mirrored) 1f - x else x
            return Offset(ox + xx * dw, oy + y * dh)
        }

        fun chain(start: Int, chain: List<Int>, color: Color, width: Float) {
            for (i in 0 until chain.size - 1) {
                val a = at(start + chain[i]) ?: continue
                val b = at(start + chain[i + 1]) ?: continue
                drawLine(color, a, b, strokeWidth = width)
            }
        }

        val handColor = if (signing) GreenOk else Color(0xFF38BDF8)
        for (start in listOf(SignLayout.LEFT_HAND_START, SignLayout.RIGHT_HAND_START)) {
            for (c in HandChains) chain(start, c, handColor, 4f)
            for (i in 0 until SignLayout.HAND_COUNT) {
                at(start + i)?.let { drawCircle(handColor, radius = 5f, center = it) }
            }
        }
        for (c in ArmChains) chain(SignLayout.POSE_START, c, Color(0xFF38BDF8).copy(alpha = 0.7f), 3f)
    }
}

// ==========================
// 🧩 SMALL PIECES
// ==========================
@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TogglePill(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) BlueOn else Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun BoxScope.CenterMessage(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B1220).copy(alpha = 0.92f))
            .padding(24.dp)
            .widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = SlateText, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(modifier = Modifier.height(14.dp))
            action()
        }
    }
}

// ==========================
// 🔧 HELPERS
// ==========================
private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}
