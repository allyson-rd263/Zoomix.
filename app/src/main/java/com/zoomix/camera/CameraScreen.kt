package com.zoomix.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_ZOOM = 50f

/** Modos que só aparecem se o fabricante do aparelho oferecer (CameraX Extensions). */
private enum class CamMode(val label: String, val ext: Int) {
    PADRAO("Padrão", ExtensionMode.NONE),
    HDR("HDR", ExtensionMode.HDR),
    NOITE("Noite", ExtensionMode.NIGHT)
}

@Composable
fun CameraScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val tier = remember { GlassConfig.resolve(ctx, SecureStore.glassMode(ctx)) }

    // FIT_CENTER + prévia em 4:3: a prévia mostra exatamente o enquadramento da foto (sem cortes
    // escondidos) e o sensor é aproveitado por inteiro. COMPATIBLE para o zoom digital escalar bem.
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var extManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    var available by remember { mutableStateOf(listOf(CamMode.PADRAO)) }
    var mode by remember { mutableStateOf(CamMode.PADRAO) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var zoomT by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var tapPos by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(Unit) {
        val pf = ProcessCameraProvider.getInstance(ctx)
        pf.addListener({
            val p = pf.get()
            provider = p
            val ef = ExtensionsManager.getInstanceAsync(ctx, p)
            ef.addListener({
                try {
                    val m = ef.get()
                    extManager = m
                    available = CamMode.values().filter {
                        it == CamMode.PADRAO ||
                            m.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, it.ext)
                    }
                } catch (_: Exception) {
                }
            }, mainExecutor)
        }, mainExecutor)
        onDispose {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(provider, extManager, mode) {
        val p = provider ?: return@LaunchedEffect

        fun bind(m: CamMode, highRes: Boolean) {
            val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val previewSel = ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()
            val capSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            if (highRes) {
                capSel.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            }
            val preview = Preview.Builder().setResolutionSelector(previewSel).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(capSel.build())
                .build()
            val em = extManager
            val selector = if (m == CamMode.PADRAO || em == null) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                em.getExtensionEnabledCameraSelector(CameraSelector.DEFAULT_BACK_CAMERA, m.ext)
            }
            p.unbindAll()
            camera = p.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture = capture
        }

        try {
            bind(mode, highRes = true)
        } catch (e: Exception) {
            try {
                bind(mode, highRes = false)
            } catch (e2: Exception) {
                status = "Modo ${mode.label} indisponível neste aparelho"
                if (mode != CamMode.PADRAO) mode = CamMode.PADRAO
            }
        }
    }

    // Zoom de 1x a 50x em escala logarítmica. Até o limite do hardware o zoom é da câmera;
    // acima disso vira zoom digital (ampliação/recorte).
    val hwMax = (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f).coerceAtLeast(1f)
    val userZoom = MAX_ZOOM.pow(zoomT)
    val hwZoom = min(userZoom, hwMax)
    val digital = userZoom / hwZoom

    LaunchedEffect(camera, hwZoom) {
        camera?.cameraControl?.setZoomRatio(hwZoom)
    }

    // Compensação de exposição (só se o aparelho suportar).
    val expState = camera?.cameraInfo?.exposureState
    val evRange = expState?.exposureCompensationRange
    val evSupported = expState?.isExposureCompensationSupported == true &&
        evRange != null && evRange.lower < evRange.upper
    val evStep = expState?.exposureCompensationStep?.toFloat() ?: 0f
    var evIndex by remember(camera) { mutableStateOf(0) }

    fun capture() {
        val cap = imageCapture ?: return
        val dir = File(ctx.cacheDir, "pending").apply { mkdirs() }
        val name = "IMG_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(dir, "$name.jpg")
        val extra = digital
        // Com zoom digital, a captura inteira do sensor fica guardada intacta em "_full".
        val raw = if (extra > 1.01f) File(dir, "${name}_full.jpg") else file
        status = "Tirando foto…"
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val (w, h) = ImageUtils.sizeOf(raw)
                        val sensor = CameraDiag.sensorPixels(ctx)
                        if (raw != file) ImageUtils.applyDigitalCrop(raw, file, extra)
                        val hasKey = !SecureStore.apiKey(ctx, SecureStore.provider(ctx)).isNullOrBlank()
                        val builder = OneTimeWorkRequestBuilder<EnhanceWorker>()
                            .setInputData(workDataOf(EnhanceWorker.KEY_PATH to file.absolutePath))
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        if (hasKey) {
                            builder.setConstraints(
                                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                            )
                        }
                        WorkManager.getInstance(ctx).enqueue(builder.build())
                        val info = String.format(java.util.Locale.US, "Captura %dx%d (%.1f MP)", w, h, w * h / 1e6) +
                            (sensor?.let { " · sensor ${it.first}x${it.second}" } ?: "")
                        mainExecutor.execute {
                            status = info + if (hasKey) "\nMelhorando com IA em segundo plano…"
                            else "\nSem chave de IA: salva sem melhoria."
                        }
                    } catch (e: Exception) {
                        mainExecutor.execute { status = "Erro ao processar a foto: ${e.message}" }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute { status = "Erro ao tirar a foto: ${exception.message}" }
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = digital
                    scaleY = digital
                }
            )
            // Camada transparente que recebe o toque para focar/medir exposição naquele ponto.
            Box(
                Modifier.fillMaxSize().pointerInput(camera, digital) {
                    detectTapGestures { off ->
                        val cam = camera ?: return@detectTapGestures
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val x = cx + (off.x - cx) / digital
                        val y = cy + (off.y - cy) / digital
                        val point = previewView.meteringPointFactory.createPoint(x, y)
                        val action = FocusMeteringAction.Builder(
                            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        cam.cameraControl.startFocusAndMetering(action)
                        tapPos = off
                    }
                }
            )
            val tp = tapPos
            if (tp != null) {
                Box(
                    Modifier
                        .offset { IntOffset((tp.x - 36.dp.toPx()).roundToInt(), (tp.y - 36.dp.toPx()).roundToInt()) }
                        .size(72.dp)
                        .border(2.dp, Color.White, CircleShape)
                )
                LaunchedEffect(tp) {
                    delay(1500)
                    tapPos = null
                }
            }
        }

        GlassPanel(
            tier,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Zoomix", fontWeight = FontWeight.Bold)
                if (available.size > 1) {
                    available.forEach { m ->
                        if (m == mode) Button(onClick = { mode = m }) { Text(m.label) }
                        else OutlinedButton(onClick = { mode = m }) { Text(m.label) }
                    }
                }
                TextButton(onClick = onOpenSettings) { Text("Ajustes") }
            }
        }

        GlassPanel(
            tier,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val label = String.format(java.util.Locale.US, "%.1fx", userZoom) +
                    if (digital > 1.01f) "  (digital)" else ""
                Text(label, fontWeight = FontWeight.SemiBold)
                Slider(value = zoomT, onValueChange = { zoomT = it }, valueRange = 0f..1f)
                if (evSupported && evRange != null) {
                    Text(String.format(java.util.Locale.US, "Exposição %+.1f EV", evIndex * evStep))
                    Slider(
                        value = evIndex.toFloat(),
                        onValueChange = {
                            evIndex = it.roundToInt()
                            camera?.cameraControl?.setExposureCompensationIndex(evIndex)
                        },
                        valueRange = evRange.lower.toFloat()..evRange.upper.toFloat()
                    )
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { capture() }
                )
                if (status.isNotEmpty()) Text(status)
            }
        }
    }
}
