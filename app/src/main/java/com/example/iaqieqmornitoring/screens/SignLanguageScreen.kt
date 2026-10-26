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
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.SignClassifier
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
    val inputMode by AppSettings.signInputMode.flow.collectAsState()

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
                    SignStatus.LOADING -> "LOADING MODEL" to AmberWarn
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
                    TogglePill(label = "Flip hand", on = flip) {
                        SignRecognizer.flipInput.value = !flip
                    }
                    TogglePill(
                        label = when (inputMode) {
                            SignClassifier.INPUT_MINUS_ONE_ONE -> "Input -1..1"
                            SignClassifier.INPUT_RAW_255 -> "Input 0..255"
                            else -> "Input 0..1"
                        },
                        on = false
                    ) {
                        AppSettings.signInputMode.set((inputMode + 1) % 3)
                    }
                }

                // ---- caption bar (bottom) ----
                CaptionBar(
                    caption = state.word.ifBlank { state.caption },
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
                text = message.ifBlank { "Raise a hand and spell with letters." },
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
            mirrored = frontCamera,   // the hand picture is cut from the unmirrored frame; only the preview is mirrored
            signing = signing,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Draws the square cut out around the hand and the forearm on top of the preview (cropped to fill, FILL_CENTER). */
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

        fun at(x: Float, y: Float): Offset {
            val xx = if (mirrored) 1f - x else x
            return Offset(ox + xx * dw, oy + y * dh)
        }

        val color = if (signing) GreenOk else Color(0xFF38BDF8)
        val a = at(overlay.box[0], overlay.box[1])
        val b = at(overlay.box[2], overlay.box[3])
        drawRect(
            color = color,
            topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
            size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
        )
        val elbow = at(overlay.arm[0], overlay.arm[1])
        val wrist = at(overlay.arm[2], overlay.arm[3])
        drawLine(color.copy(alpha = 0.7f), elbow, wrist, strokeWidth = 3f)
        drawCircle(color, radius = 6f, center = wrist)
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
