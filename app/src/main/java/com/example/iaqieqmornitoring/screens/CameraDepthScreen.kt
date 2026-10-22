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
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.DepthCameraSource
import com.example.iaqieqmornitoring.utils.DepthFrame
import com.example.iaqieqmornitoring.utils.DepthStatus
import com.example.iaqieqmornitoring.utils.GestureState
import com.example.iaqieqmornitoring.utils.Kp
import com.example.iaqieqmornitoring.utils.PoseResult
import com.example.iaqieqmornitoring.utils.TemiRepository
import com.example.iaqieqmornitoring.utils.GestureEngine
import java.util.Locale
import java.util.concurrent.Executors

private val PanelColor = Color(0xFF0B1220)
private val SlateText = Color(0xFF94A3B8)
private val GreenOk = Color(0xFF00FFAA)
private val AmberWarn = Color(0xFFFFC107)
private val RedErr = Color(0xFFFF5252)

// ==========================
// 🎥 CAMERA & DEPTH SCREEN
// ==========================
@Composable
fun CameraDepthScreen(nav: NavController) {

    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }

    val simulate by AppSettings.simulateRobot.flow.collectAsState()
    val depth by DepthCameraSource.state.collectAsState()
    val gesture by GestureEngine.state.collectAsState()
    val following by TemiRepository.following.collectAsState()
    val raiseHandFollow by AppSettings.raiseHandFollowEnabled.flow.collectAsState()
    val findPerson by AppSettings.findPersonEnabled.flow.collectAsState()

    // pose model + follow mode only live while this tab is on screen
    DisposableEffect(Unit) {
        onDispose { GestureEngine.stop() }
    }

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

    // ---- depth stream follows the screen: runs while visible, released when hidden ----
    DisposableEffect(owner, simulate, hasPermission) {
        val appContext = context.applicationContext
        val lifecycle = owner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    DepthCameraSource.start(appContext, simulate, hasPermission)
                Lifecycle.Event.ON_STOP -> DepthCameraSource.stop()
                else -> Unit
            }
        }
        if (lifecycle != null) {
            lifecycle.addObserver(observer) // replays ON_START if the screen is already visible
        } else {
            DepthCameraSource.start(appContext, simulate, hasPermission)
        }
        onDispose {
            lifecycle?.removeObserver(observer)
            DepthCameraSource.stop()
        }
    }

    var flipRgb by remember { mutableStateOf(false) }
    var rgbLive by remember { mutableStateOf(false) }
    var rgbError by remember { mutableStateOf<String?>(null) }

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
                    .padding(20.dp)
            ) {

                // ---- header ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Camera & Depth",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (following) {
                        TogglePill(label = "Stop following", on = true, activeColor = Color(0xFFDC2626)) {
                            TemiRepository.stopFollowing()
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    TogglePill(label = "Find person", on = findPerson) {
                        AppSettings.findPersonEnabled.set(!findPerson)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    TogglePill(label = "Raise hand \u2192 follow me", on = raiseHandFollow) {
                        AppSettings.raiseHandFollowEnabled.set(!raiseHandFollow)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    TogglePill(label = "Flip RGB view", on = flipRgb) { flipRgb = !flipRgb }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ---- the two live views ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {

                    // RGB camera
                    val (rgbBadge, rgbColor) = when {
                        gesture.tPose && following -> "STOPPING" to RedErr
                        following -> "FOLLOWING" to AmberWarn
                        gesture.handRaised -> "HAND RAISED" to GreenOk
                        findPerson && gesture.scanning -> "SCANNING" to AmberWarn
                        findPerson && gesture.personVisible -> "PERSON FOUND" to GreenOk
                        !hasPermission -> "NO PERMISSION" to AmberWarn
                        rgbError != null -> "ERROR" to RedErr
                        rgbLive -> "LIVE" to GreenOk
                        else -> "STARTING" to SlateText
                    }
                    PanelCard(
                        title = "RGB Camera",
                        subtitle = "What temi's tablet camera sees",
                        badgeText = rgbBadge,
                        badgeColor = rgbColor,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        when {
                            !hasPermission -> CenterMessage(
                                title = "Camera access needed",
                                body = if (permissionDenied)
                                    "Permission was denied. Allow Camera for this app in Android " +
                                        "Settings > Apps, then reopen this tab."
                                else
                                    "Allow camera access to show the live view."
                            ) {
                                Button(onClick = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }) { Text("Grant camera access") }
                            }

                            owner == null -> CenterMessage(
                                title = "Camera unavailable",
                                body = "The screen could not attach to the activity lifecycle."
                            )

                            else -> {
                                RgbCameraView(
                                    owner = owner,
                                    flip = flipRgb,
                                    gesture = gesture,
                                    onStatus = { live, error ->
                                        rgbLive = live
                                        rgbError = error
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                rgbError?.let { msg ->
                                    CenterMessage(title = "Camera problem", body = msg)
                                }
                                val info = gesture.error
                                    ?: gesture.pose?.let { "Pose ${gesture.fps} fps \u00b7 ${it.inferenceMs} ms \u00b7 tilt ${gesture.tiltDeg}\u00b0" }
                                if (info != null) {
                                    Text(
                                        text = info,
                                        color = if (gesture.error != null) RedErr else SlateText,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Depth
                    val frame = depth.frame
                    val showFrame = frame != null &&
                        (depth.status == DepthStatus.STREAMING || depth.status == DepthStatus.SIMULATED)
                    val (depthBadge, depthColor) = when (depth.status) {
                        DepthStatus.STREAMING -> "LIVE DEPTH" to GreenOk
                        DepthStatus.SIMULATED -> "SIMULATED" to AmberWarn
                        DepthStatus.STARTING -> "STARTING" to SlateText
                        DepthStatus.UNAVAILABLE -> "NOT AVAILABLE" to AmberWarn
                        DepthStatus.NO_PERMISSION -> "NO PERMISSION" to AmberWarn
                        DepthStatus.ERROR -> "ERROR" to RedErr
                        DepthStatus.IDLE -> "IDLE" to SlateText
                    }
                    PanelCard(
                        title = "Depth View",
                        subtitle = if (depth.status == DepthStatus.SIMULATED)
                            "Synthetic test scene" else "Distance to everything in front of temi",
                        badgeText = depthBadge,
                        badgeColor = depthColor,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        footerVisible = showFrame,
                        footer = { DepthLegend() }
                    ) {
                        if (showFrame && frame != null) {
                            DepthImage(frame)
                        } else {
                            val body = when (depth.status) {
                                DepthStatus.UNAVAILABLE ->
                                    depth.message + "\n\nTurn on \"Simulated robot\" in Settings to " +
                                        "preview this view with a synthetic scene."
                                DepthStatus.NO_PERMISSION ->
                                    "Allow camera access to read the depth camera."
                                else -> depth.message.ifBlank { "Waiting for depth data..." }
                            }
                            CenterMessage(
                                title = when (depth.status) {
                                    DepthStatus.UNAVAILABLE -> "No depth camera"
                                    DepthStatus.ERROR -> "Depth camera problem"
                                    else -> "Depth view"
                                },
                                body = body
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ---- distance read-outs ----
                val frame = depth.frame
                val live = frame != null &&
                    (depth.status == DepthStatus.STREAMING || depth.status == DepthStatus.SIMULATED)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DistanceCard("Left", if (live) frame?.leftMm else null, Modifier.weight(1f))
                    DistanceCard("Ahead", if (live) frame?.aheadMm else null, Modifier.weight(1f))
                    DistanceCard("Right", if (live) frame?.rightMm else null, Modifier.weight(1f))
                    DistanceCard("Crosshair", if (live) frame?.centerMm else null, Modifier.weight(1f))
                    StatCard(
                        "Valid depth",
                        if (live && frame != null) "${frame.coveragePercent}%" else "--",
                        Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Left / Ahead / Right = nearest obstacle in each third of the area between " +
                        "the dashed lines. Crosshair = distance at the centre of the image.",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ==========================
// 📷 RGB CAMERA (CameraX)
// ==========================
@Composable
private fun RgbCameraView(
    owner: LifecycleOwner,
    flip: Boolean,
    gesture: GestureState,
    onStatus: (live: Boolean, error: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var frontCamera by remember { mutableStateOf(true) }

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView-based, so the view can be mirrored and clipped like any other view
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    DisposableEffect(owner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var stateData: LiveData<CameraState>? = null

        val observer = Observer<CameraState> { s ->
            val err = s.error
            when {
                err != null -> onStatus(false, describeCameraError(err.code))
                s.type == CameraState.Type.OPEN -> onStatus(true, null)
                else -> onStatus(false, null)
            }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> null
                }
                if (selector == null) {
                    onStatus(false, "No camera was found on this device.")
                    return@addListener
                }
                frontCamera = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                @Suppress("DEPRECATION")
                val pv = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                pv.setSurfaceProvider(previewView.surfaceProvider)
                preview = pv

                // Second stream for the pose model: always the newest frame, RGBA so it converts to a Bitmap.
                @Suppress("DEPRECATION")
                val ia = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                ia.setAnalyzer(analysisExecutor, GestureEngine.createAnalyzer(context))
                analysis = ia

                p.unbindAll()
                val camera = p.bindToLifecycle(owner, selector, pv, ia)
                val data = camera.cameraInfo.cameraState
                stateData = data
                data.observe(owner, observer)
            } catch (e: Exception) {
                onStatus(false, "Could not start the camera: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            stateData?.removeObserver(observer)
            analysis?.clearAnalyzer()
            analysis?.let { provider?.unbind(it) }
            preview?.let { provider?.unbind(it) }
            analysisExecutor.shutdown()
        }
    }

    // Preview and skeleton overlay sit in one box so "Flip RGB view" mirrors both together.
    Box(modifier = modifier.graphicsLayer { scaleX = if (flip) -1f else 1f }) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        PoseOverlay(
            pose = gesture.pose,
            handRaised = gesture.handRaised,
            tPose = gesture.tPose,
            mirrored = frontCamera, // PreviewView shows the front camera mirrored, the analysis frame is not
            modifier = Modifier.fillMaxSize()
        )
    }
}

private val SkeletonEdges = listOf(
    0 to 1, 0 to 2, 1 to 3, 2 to 4, 5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10,
    5 to 11, 6 to 12, 11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16
)

/** Draws the detected body keypoints on top of the camera preview (which is letter-boxed, FIT_CENTER). */
@Composable
private fun PoseOverlay(pose: PoseResult?, handRaised: Boolean, tPose: Boolean, mirrored: Boolean, modifier: Modifier = Modifier) {
    if (pose == null) return
    Canvas(modifier = modifier) {
        val imgAspect = pose.imageWidth.toFloat() / pose.imageHeight.toFloat()
        val boxAspect = size.width / size.height
        val dispW = if (imgAspect > boxAspect) size.width else size.height * imgAspect
        val dispH = if (imgAspect > boxAspect) size.width / imgAspect else size.height
        val left = (size.width - dispW) / 2f
        val top = (size.height - dispH) / 2f

        fun at(i: Int): Offset? {
            val k = pose.points[i]
            if (k.score < 0.3f) return null
            val x = if (mirrored) 1f - k.x else k.x
            return Offset(left + x * dispW, top + k.y * dispH)
        }

        val base = when {
            tPose -> Color(0xFFFF5252)
            handRaised -> Color(0xFF00FFAA)
            else -> Color(0xFF38BDF8)
        }
        for ((a, b) in SkeletonEdges) {
            val pa = at(a) ?: continue
            val pb = at(b) ?: continue
            val armOfRaise = handRaised && a in setOf(Kp.L_SHOULDER, Kp.R_SHOULDER, Kp.L_ELBOW, Kp.R_ELBOW)
            drawLine(base, pa, pb, strokeWidth = if (armOfRaise) 6f else 3f)
        }
        for (i in pose.points.indices) {
            val pt = at(i) ?: continue
            val isHand = i == Kp.L_WRIST || i == Kp.R_WRIST
            drawCircle(
                color = if (isHand && handRaised) Color(0xFFFFC107) else base,
                radius = if (isHand) 9f else 5f,
                center = pt
            )
        }
    }
}

private fun describeCameraError(code: Int): String = when (code) {
    1 -> "Too many cameras are open at once. Close other camera apps."   // ERROR_MAX_CAMERAS_IN_USE
    2 -> "The camera is being used by another app."                      // ERROR_CAMERA_IN_USE
    5 -> "The camera is disabled by device policy."                      // ERROR_CAMERA_DISABLED
    6 -> "The camera hardware reported a fatal error."                   // ERROR_CAMERA_FATAL_ERROR
    else -> "Camera error (code $code)."
}

// ==========================
// 🌈 DEPTH IMAGE + OVERLAY
// ==========================
@Composable
private fun BoxScope.DepthImage(frame: DepthFrame) {

    val image = remember(frame) { frame.bitmap.asImageBitmap() }

    Box(
        modifier = Modifier.matchParentSize(),
        contentAlignment = Alignment.Center
    ) {
        // The box has the same aspect ratio as the sensor, so the overlay lines up with the pixels.
        Box(modifier = Modifier.aspectRatio(frame.width.toFloat() / frame.height.toFloat())) {

            Image(
                bitmap = image,
                contentDescription = "Depth map",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                val guide = Color.White.copy(alpha = 0.35f)
                val top = size.height * DepthCameraSource.BAND_TOP
                val bottom = size.height * DepthCameraSource.BAND_BOTTOM

                // left / ahead / right dividers
                listOf(1f / 3f, 2f / 3f).forEach { f ->
                    drawLine(
                        color = guide,
                        start = Offset(size.width * f, top),
                        end = Offset(size.width * f, bottom),
                        strokeWidth = 2f,
                        pathEffect = dash
                    )
                }
                // obstacle band
                listOf(top, bottom).forEach { y ->
                    drawLine(
                        color = guide,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2f,
                        pathEffect = dash
                    )
                }
                // crosshair
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Color.White, radius = 14f, center = c, style = Stroke(width = 3f))
                drawLine(Color.White, Offset(c.x - 26f, c.y), Offset(c.x + 26f, c.y), strokeWidth = 3f)
                drawLine(Color.White, Offset(c.x, c.y - 26f), Offset(c.x, c.y + 26f), strokeWidth = 3f)
            }

            frame.centerMm?.let { mm ->
                Text(
                    text = formatMeters(mm),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun DepthLegend() {
    val colors = remember { List(9) { Color(DepthCameraSource.colorAtFraction(it / 8f)) } }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatMeters(DepthCameraSource.NEAR_MM),
                color = SlateText,
                fontSize = 12.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(colors))
            )
            Text(
                formatMeters(DepthCameraSource.FAR_MM) + "+",
                color = SlateText,
                fontSize = 12.sp
            )
        }
        Text(
            "Warm = near   Cool = far   Dark = no reading",
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ==========================
// 🧱 SMALL BUILDING BLOCKS
// ==========================
@Composable
private fun PanelCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    modifier: Modifier = Modifier,
    footerVisible: Boolean = false,
    footer: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PanelColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = SlateText, fontSize = 12.sp)
                }
                StatusBadge(badgeText, badgeColor)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black),
                content = content
            )

            if (footerVisible) {
                Spacer(modifier = Modifier.height(8.dp))
                footer()
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun TogglePill(
    label: String,
    on: Boolean,
    activeColor: Color = Color(0xFF2563EB),
    onClick: () -> Unit
) {
    val bg = if (on) activeColor else Color(0xFF1E293B)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun CenterMessage(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = SlateText, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
private fun DistanceCard(label: String, mm: Int?, modifier: Modifier = Modifier) {
    val color = if (mm == null) Color(0xFF64748B) else Color(DepthCameraSource.colorForMm(mm))
    StatCard(
        label = label,
        value = if (mm == null) "--" else formatMeters(mm),
        modifier = modifier,
        valueColor = color
    )
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PanelColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = SlateText, fontSize = 13.sp)
            Text(value, color = valueColor, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

private fun formatMeters(mm: Int): String = String.format(Locale.US, "%.2f m", mm / 1000f)

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
