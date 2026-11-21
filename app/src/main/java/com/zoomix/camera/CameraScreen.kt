package com.zoomix.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

private const val PHOTO_MAX_ZOOM = 50f
private const val VIDEO_MAX_ZOOM = 10f
private const val LIVE_CLIP_MS = 2500L

private enum class Cam(val label: String) {
    FOTO("Foto"),
    LIVE("Live"),
    VIDEO("Vídeo")
}

private fun stamp(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Coloca a foto na fila da IA. Devolve true se há chave configurada (e, portanto, vai para a IA). */
private fun enqueueEnhance(ctx: Context, file: File): Boolean {
    val hasKey = !SecureStore.apiKey(ctx, SecureStore.provider(ctx)).isNullOrBlank()
    val builder = OneTimeWorkRequestBuilder<EnhanceWorker>()
        .setInputData(workDataOf(EnhanceWorker.KEY_PATH to file.absolutePath))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    if (hasKey) {
        builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    }
    WorkManager.getInstance(ctx).enqueue(builder.build())
    return hasKey
}

@Composable
fun CameraScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val tier = remember { GlassConfig.resolve(ctx, SecureStore.glassMode(ctx)) }
    val strong = remember { GlassConfig.isStrong(ctx) }

    val previewView = remember { PreviewView(ctx).apply { keepScreenOn = true } }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    val workExecutor = remember { Executors.newSingleThreadExecutor() }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var cam by remember { mutableStateOf(Cam.FOTO) }
    var zoomT by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recSeconds by remember { mutableStateOf(0) }
    var liveBusy by remember { mutableStateOf(false) }
    var horizonOn by remember { mutableStateOf(SecureStore.horizon(ctx)) }
    val tilt by rememberTilt(horizonOn && cam == Cam.FOTO)
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { audioGranted = it }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({ provider = future.get() }, mainExecutor)
        onDispose {
            try {
                if (future.isDone) future.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    // Liga os casos de uso de cada modo: Foto = prévia + foto; Live = prévia + foto + vídeo;
    // Vídeo = prévia + vídeo. Se o aparelho não aguentar o modo, volta para Foto.
    LaunchedEffect(provider, cam) {
        val p = provider ?: return@LaunchedEffect
        recording?.stop()
        recording = null
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        fun newPreview() = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        fun newPhoto() = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        fun newVideo(live: Boolean, stabilize: Boolean): VideoCapture<Recorder> {
            val order = if (live) {
                listOf(Quality.FHD, Quality.HD, Quality.SD)
            } else {
                when (SecureStore.videoQuality(ctx)) {
                    "hd" -> listOf(Quality.HD, Quality.SD)
                    "fhd" -> listOf(Quality.FHD, Quality.HD, Quality.SD)
                    else -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                }
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(order, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .build()
            val b = VideoCapture.Builder(recorder)
            if (stabilize) b.setVideoStabilizationEnabled(true)
            return b.build()
        }

        try {
            p.unbindAll()
            when (cam) {
                Cam.FOTO -> {
                    val ic = newPhoto()
                    camera = p.bindToLifecycle(lifecycleOwner, selector, newPreview(), ic)
                    imageCapture = ic
                    videoCapture = null
                }
                Cam.LIVE -> {
                    val ic = newPhoto()
                    val vc = newVideo(live = true, stabilize = false)
                    camera = p.bindToLifecycle(lifecycleOwner, selector, newPreview(), ic, vc)
                    imageCapture = ic
                    videoCapture = vc
                }
                Cam.VIDEO -> {
                    val vc = try {
                        newVideo(live = false, stabilize = true).also {
                            camera = p.bindToLifecycle(lifecycleOwner, selector, newPreview(), it)
                        }
                    } catch (e: Exception) {
                        p.unbindAll()
                        newVideo(live = false, stabilize = false).also {
                            camera = p.bindToLifecycle(lifecycleOwner, selector, newPreview(), it)
                        }
                    }
                    imageCapture = null
                    videoCapture = vc
                }
            }
        } catch (e: Exception) {
            status = "O modo ${cam.label} não está disponível neste aparelho"
            if (cam != Cam.FOTO) cam = Cam.FOTO
        }
    }

    // Zoom em escala logarítmica. Foto: até 50x (acima do limite do hardware vira zoom digital).
    // Live e Vídeo: só zoom óptico, no máximo 10x, para vídeo e foto ficarem iguais e sem borrar.
    val hwMax = (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f).coerceAtLeast(1f)
    val maxZoom = if (cam == Cam.FOTO) PHOTO_MAX_ZOOM else min(hwMax, VIDEO_MAX_ZOOM)
    val userZoom = maxZoom.pow(zoomT)
    val hwZoom = min(userZoom, hwMax)
    val digital = userZoom / hwZoom

    LaunchedEffect(camera, hwZoom) {
        camera?.cameraControl?.setZoomRatio(hwZoom)
    }

    fun takePhoto() {
        val cap = imageCapture ?: return
        val dir = File(ctx.cacheDir, "pending").apply { mkdirs() }
        val file = File(dir, "IMG_${stamp()}.jpg")
        val extra = digital
        val roll = if (horizonOn && abs(tilt) in 0.5f..30f) tilt else 0f
        status = "Tirando foto…"
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        if (extra > 1.01f || roll != 0f) ImageUtils.processStill(file, extra, roll)
                        val hasKey = enqueueEnhance(ctx, file)
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

    // Live: foto + clipe de ~2,5 s gravado a partir do clique, juntados em uma Motion Photo.
    fun takeLive() {
        val cap = imageCapture ?: return
        val vc = videoCapture ?: return
        if (liveBusy) return
        liveBusy = true
        val dir = File(ctx.cacheDir, "pending").apply { mkdirs() }
        val name = "LIVE_${stamp()}"
        val still = File(dir, "$name.jpg")
        val clip = File(dir, "$name.mp4")
        val stillDone = CountDownLatch(1)
        val stillOk = AtomicBoolean(false)
        status = "Gravando Live…"

        val rec = vc.output
            .prepareRecording(ctx, FileOutputOptions.Builder(clip).build())
            .start(mainExecutor) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    val failed = ev.hasError()
                    workExecutor.execute {
                        try {
                            stillDone.await(10, TimeUnit.SECONDS)
                            if (failed || !stillOk.get()) {
                                mainExecutor.execute { status = "Não foi possível gravar a Live" }
                            } else {
                                val motion = MotionPhoto.build(still.readBytes(), clip.readBytes())
                                Gallery.saveJpeg(ctx, motion, "ZOOMIX_$name")
                                mainExecutor.execute { status = "Live salva na galeria" }
                            }
                        } catch (e: Exception) {
                            mainExecutor.execute { status = "Erro ao salvar a Live: ${e.message}" }
                        } finally {
                            still.delete()
                            clip.delete()
                            mainExecutor.execute { liveBusy = false }
                        }
                    }
                }
            }

        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(still).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    stillOk.set(true)
                    stillDone.countDown()
                }

                override fun onError(exception: ImageCaptureException) {
                    stillDone.countDown()
                }
            }
        )
        Handler(Looper.getMainLooper()).postDelayed({ rec.stop() }, LIVE_CLIP_MS)
    }

    fun toggleRecording() {
        val vc = videoCapture ?: return
        val current = recording
        if (current != null) {
            current.stop()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ZOOMIX_VID_${stamp()}")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Zoomix")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        var pending = vc.output.prepareRecording(ctx, options)
        if (SecureStore.micOn(ctx) && audioGranted) pending = pending.withAudioEnabled()
        recSeconds = 0
        status = "Gravando…"
        recording = pending.start(mainExecutor) { ev ->
            when (ev) {
                is VideoRecordEvent.Status ->
                    recSeconds = (ev.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recSeconds = 0
                    status = if (ev.hasError()) "Erro ao gravar o vídeo (código ${ev.error})"
                    else "Vídeo salvo na galeria"
                }
                else -> {}
            }
        }
    }

    fun onShutter() {
        when (cam) {
            Cam.FOTO -> takePhoto()
            Cam.LIVE -> takeLive()
            Cam.VIDEO -> toggleRecording()
        }
    }

    fun selectMode(m: Cam) {
        cam = m
        if (m == Cam.VIDEO && SecureStore.micOn(ctx) && !audioGranted) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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

        // Linha de nível do horizon lock: fica reta quando o celular está nivelado.
        if (horizonOn && cam == Cam.FOTO) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(140.dp)
                    .height(2.dp)
                    .rotate(-tilt)
                    .background(if (abs(tilt) < 1f) Color(0xFF4CAF50) else Color.White)
            )
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
                if (strong && cam == Cam.FOTO) {
                    val toggle = {
                        horizonOn = !horizonOn
                        SecureStore.setHorizon(ctx, horizonOn)
                    }
                    if (horizonOn) Button(onClick = toggle) { Text("Nível") }
                    else OutlinedButton(onClick = toggle) { Text("Nível") }
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
                val label = String.format(Locale.US, "%.1fx", userZoom) +
                    if (digital > 1.01f) "  (digital)" else ""
                Text(label, fontWeight = FontWeight.SemiBold)
                Slider(value = zoomT, onValueChange = { zoomT = it }, valueRange = 0f..1f)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cam.values().forEach { m ->
                        val enabled = recording == null && !liveBusy
                        if (m == cam) {
                            Button(onClick = { selectMode(m) }, enabled = enabled) { Text(m.label) }
                        } else {
                            OutlinedButton(onClick = { selectMode(m) }, enabled = enabled) { Text(m.label) }
                        }
                    }
                }

                val recording1 = recording != null
                val shutterColor = if (cam == Cam.VIDEO) Color(0xFFE53935) else Color.White
                val shutterShape = if (recording1) RoundedCornerShape(10.dp) else CircleShape
                Box(
                    Modifier
                        .size(76.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(if (recording1) 20.dp else 6.dp)
                        .clip(shutterShape)
                        .background(shutterColor)
                        .clickable(enabled = !liveBusy) { onShutter() }
                )
                if (recording1) {
                    Text(String.format(Locale.US, "● %02d:%02d", recSeconds / 60, recSeconds % 60))
                }
                if (status.isNotEmpty()) Text(status)
            }
        }
    }
}
