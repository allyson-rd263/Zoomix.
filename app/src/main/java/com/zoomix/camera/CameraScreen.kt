package com.zoomix.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

private const val MAX_ZOOM = 50f

@Composable
fun CameraScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tier = remember { GlassConfig.resolve(ctx, SecureStore.glassMode(ctx)) }

    val previewView = remember { PreviewView(ctx) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomT by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try {
                if (future.isDone) future.get().unbindAll()
            } catch (_: Exception) {
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

    fun capture() {
        val dir = File(ctx.cacheDir, "pending").apply { mkdirs() }
        val name = "IMG_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(dir, "$name.jpg")
        val extra = digital
        val mainExecutor = ContextCompat.getMainExecutor(ctx)
        status = "Tirando foto…"
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        if (extra > 1.01f) ImageUtils.applyDigitalCrop(file, extra)
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
                        mainExecutor.execute {
                            status = if (hasKey) "Foto na fila: melhorando com IA…"
                            else "Foto salva (sem chave de IA configurada)"
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
        }

        GlassPanel(
            tier,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Zoomix", fontWeight = FontWeight.Bold)
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
