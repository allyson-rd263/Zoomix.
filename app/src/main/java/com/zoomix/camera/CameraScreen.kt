package com.zoomix.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private tailrec fun Context.findMainActivity(): MainActivity? =
    when (this) {
        is MainActivity -> this
        is ContextWrapper -> baseContext.findMainActivity()
        else -> null
    }

private const val PHOTO_MAX_ZOOM = 50f
private const val FRONT_MAX_ZOOM = 4f
private const val VIDEO_MAX_ZOOM = 10f
private const val LIVE_CLIP_MS = 2500L
private val YELLOW = Color(0xFFFFD60A)

private enum class Cam(val label: String) {
    FOTO("Foto"),
    LIVE("Live"),
    VIDEO("Vídeo")
}

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

private fun fmtZoom(z: Float): String =
    if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}x" else String.format(Locale.US, "%.1fx", z)

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

/** Abre a última foto/vídeo no app que o Android tiver como padrão (ou a galeria, se não houver nada). */
private fun openGallery(ctx: Context, media: Media?): Boolean {
    val view = Intent(Intent.ACTION_VIEW).apply {
        if (media != null) setDataAndType(media.uri, if (media.isVideo) "video/*" else "image/*")
        else setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        ctx.startActivity(view)
        true
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
            true
        } catch (e2: Exception) {
            false
        }
    }
}

/** Olha a prévia: se há muita área escura e muita estourada ao mesmo tempo, vale usar HDR. */
private fun sceneNeedsHdr(previewView: PreviewView): Boolean {
    val bmp = previewView.bitmap ?: return false
    var small: Bitmap? = null

    try {
        val w = 48
        val h = max(1, 48 * bmp.height / max(1, bmp.width))
        small = Bitmap.createScaledBitmap(bmp, w, h, true)

        var dark = 0
        var bright = 0
        val px = IntArray(w * h)

        small.getPixels(px, 0, w, 0, 0, w, h)

        for (p in px) {
            val l = (0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)) / 255f

            if (l < 0.08f) dark++
            if (l > 0.92f) bright++
        }

        val total = px.size.toFloat()
        return dark / total >= 0.10f && bright / total >= 0.03f
    } catch (_: Exception) {
        return false
    } finally {
        if (small !== bmp) {
            small?.recycle()
        }
        bmp.recycle()
    }
}

@Composable
private fun Bubble(
    text: String,
    tier: GlassTier,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(50)
    val fill = when {
        selected -> Color(0x40FFFFFF)
        tier == GlassTier.COMPLETO -> Color(0x1AFFFFFF)
        else -> Color.Transparent
    }
    val borderColor = if (selected) YELLOW else Color(0xB3FFFFFF)
    var m = modifier.clip(shape).background(fill).border(1.dp, borderColor, shape)
    if (onClick != null) m = m.clickable { onClick() }
    Box(m.padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (selected) YELLOW else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 4f))
        )
    }
}

@Composable
fun CameraScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val tier = remember { GlassConfig.resolve(ctx, SecureStore.glassMode(ctx)) }
    val strong = remember { GlassConfig.isStrong(ctx) }
    val zoomMapOn = remember {
        when (SecureStore.zoomMap(ctx)) {
            "on" -> true
            "off" -> false
            else -> strong
        }
    }

    val previewView = remember { PreviewView(ctx).apply { keepScreenOn = true } }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    val workExecutor = remember { Executors.newSingleThreadExecutor() }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var cam by remember { mutableStateOf(Cam.FOTO) }
    var facingFront by remember { mutableStateOf(false) }
    var lensId by remember { mutableStateOf<String?>(null) }
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    var zoom by remember { mutableStateOf(1f) }
    var zoomBarOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recSeconds by remember { mutableStateOf(0) }
    var liveBusy by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var horizonOn by remember { mutableStateOf(SecureStore.horizon(ctx)) }
    var hdrMode by remember { mutableStateOf(SecureStore.hdrMode(ctx)) }
    var latest by remember { mutableStateOf<Media?>(null) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var mapImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val tilt by rememberTilt(horizonOn && cam == Cam.FOTO && !facingFront)
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { audioGranted = it }

    fun refreshLatest() {
        workExecutor.execute {
            val m = Gallery.latest(ctx)
            val t = m?.let { Gallery.thumbnail(ctx, it.uri) }
            mainExecutor.execute {
                latest = m
                thumb = t?.asImageBitmap()
            }
        }
    }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({ provider = future.get() }, mainExecutor)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshLatest()
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        refreshLatest()
        onDispose {
            ctx.contentResolver.unregisterContentObserver(observer)
            try {
                if (future.isDone) future.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(provider) {
        val p = provider ?: return@LaunchedEffect
        lenses = try {
            Lenses.detectBack(p)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Liga os casos de uso de cada modo: Foto = prévia + foto; Live = prévia + foto + vídeo;
    // Vídeo = prévia + vídeo (em 16:9, igual ao vídeo gravado). Se algo não funcionar no aparelho,
    // volta para uma configuração mais simples.
    LaunchedEffect(provider, cam, facingFront, lensId) {
        val p = provider ?: return@LaunchedEffect
        recording?.stop()
        recording = null
        zoom = 1f

        val selector = when {
            facingFront -> CameraSelector.DEFAULT_FRONT_CAMERA
            lensId != null -> Lenses.selectorFor(lensId!!)
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        previewView.scaleType =
            if (cam == Cam.VIDEO) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER

        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

        fun newPreview() = Preview.Builder()
            .setTargetRotation(targetRotation)
            .apply {
            if (cam == Cam.VIDEO) setTargetAspectRatio(AspectRatio.RATIO_16_9)
        }.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        fun newPhoto() = ImageCapture.Builder()
            .setTargetRotation(targetRotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        fun newVideo(live: Boolean): VideoCapture<Recorder> {
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
                .setAspectRatio(
                    if (live) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
                )
                .setQualitySelector(
                    QualitySelector.fromOrderedList(order, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .build()
            return VideoCapture.Builder(recorder)
                .setTargetRotation(targetRotation)
                .build()
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
                    val vc = newVideo(live = true)
                    camera = p.bindToLifecycle(lifecycleOwner, selector, newPreview(), ic, vc)
                    imageCapture = ic
                    videoCapture = vc
                }
                Cam.VIDEO -> {
                    val vc = newVideo(live = false).also {
                        camera = p.bindToLifecycle(lifecycleOwner, selector, newPreview(), it)
                    }
                    imageCapture = null
                    videoCapture = vc
                }
            }
        } catch (e: Exception) {
            when {
                lensId != null -> {
                    status = "Esta lente não pôde ser usada neste modo"
                    lensId = null
                }
                cam != Cam.FOTO -> {
                    status = "O modo ${cam.label} não está disponível aqui"
                    cam = Cam.FOTO
                }
                facingFront -> {
                    status = "Não foi possível abrir a câmera frontal"
                    facingFront = false
                }
                else -> status = "Não foi possível abrir a câmera: ${e.message}"
            }
        }
        Unit
    }

    // Faixa de zoom. Foto traseira: até 50x (acima do limite do hardware vira zoom digital).
    // Frontal: até 4x. Live e Vídeo: só zoom óptico, no máximo 10x.
    val zs = camera?.cameraInfo?.zoomState?.value
    val hwMin = min(zs?.minZoomRatio ?: 1f, 1f)
    val hwMax = max(zs?.maxZoomRatio ?: 1f, 1f)
    val maxZoom = when {
        cam != Cam.FOTO -> min(hwMax, VIDEO_MAX_ZOOM)
        facingFront -> max(hwMax, FRONT_MAX_ZOOM)
        else -> PHOTO_MAX_ZOOM
    }

    val activity = remember(ctx) { ctx.findMainActivity() }
    val volumeAction = rememberUpdatedState<(Int, Int) -> Boolean> { keyCode, repeatCount ->
        if (!SecureStore.volumeZoom(ctx)) {
            false
        } else {
            if (repeatCount == 0) {
                if (!zoomBarOpen) {
                    zoomBarOpen = true
                } else {
                    val factor = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        1.1f
                    } else {
                        1f / 1.1f
                    }
                    zoom = (zoom * factor).coerceIn(
                        hwMin,
                        max(maxZoom, hwMin)
                    )
                }
            }
            true
        }
    }

    DisposableEffect(activity) {
        activity?.volumeZoomHandler = { keyCode, repeatCount ->
            volumeAction.value(keyCode, repeatCount)
        }
        onDispose {
            activity?.volumeZoomHandler = null
        }
    }
    val zc = zoom.coerceIn(hwMin, max(maxZoom, hwMin))
    val hwZoom = zc.coerceIn(hwMin, hwMax)
    val digital = max(1f, zc / hwZoom)

    LaunchedEffect(camera, hwZoom) {
        camera?.cameraControl?.setZoomRatio(hwZoom)
    }

    // Mini-tela do zoom digital: captura temporária e libera os bitmaps Android.
    val showMap = zoomMapOn && cam == Cam.FOTO && !facingFront && digital > 1.01f

    LaunchedEffect(showMap) {
        if (!showMap) {
            mapImage = null
            return@LaunchedEffect
        }

        while (true) {
            var source: Bitmap? = null
            var scaled: Bitmap? = null

            try {
                source = previewView.bitmap

                if (source != null && source.width > 0 && source.height > 0) {
                    val w = 240
                    val h = max(1, (240f * source.height / source.width).roundToInt())

                    scaled = Bitmap.createScaledBitmap(source, w, h, true)
                    mapImage = scaled.asImageBitmap()
                }
            } catch (_: Exception) {
                // A prévia pode estar temporariamente indisponível.
            } finally {
                if (scaled !== source) {
                    source?.recycle()
                }
            }

            delay(450)
        }
    }

    LaunchedEffect(status) {
        if (status.isNotEmpty() && recording == null && !photoBusy && !liveBusy) {
            val current = status
            delay(4500)
            if (status == current) status = ""
        }
    }

    // ---------- captura ----------

    fun pendingDir(): File = File(ctx.cacheDir, "pending").apply { mkdirs() }

    /** Depois que a foto está no arquivo: horizon lock/zoom digital, e depois IA ou galeria direta. */
    fun finishStill(file: File, extra: Float, roll: Float, front: Boolean) {
        try {
            val mirror = front && SecureStore.selfieMirror(ctx)
            if (extra > 1.01f || roll != 0f || mirror) {
                ImageUtils.processStill(file, extra, roll, mirrorHorizontal = mirror)
            }
            if (front && !SecureStore.selfieAi(ctx)) {
                Gallery.saveJpeg(ctx, file.readBytes(), file.nameWithoutExtension)
                file.delete()
                mainExecutor.execute { status = "Selfie salva na galeria" }
            } else {
                val hasKey = enqueueEnhance(ctx, file)
                mainExecutor.execute {
                    status = if (hasKey) "Foto na fila: melhorando com IA…"
                    else "Foto salva (sem chave de IA configurada)"
                }
            }
        } catch (e: Exception) {
            mainExecutor.execute { status = "Erro ao processar a foto: ${e.message}" }
        }
    }

    fun takeSingle(extra: Float, roll: Float, front: Boolean) {
        val cap = imageCapture ?: return
        photoBusy = true
        val file = File(pendingDir(), "IMG_${stamp()}.jpg")
        status = "Tirando foto…"
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    finishStill(file, extra, roll, front)
                    mainExecutor.execute { photoBusy = false }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute {
                        status = "Erro ao tirar a foto: ${exception.message}"
                        photoBusy = false
                    }
                }
            }
        )
    }

    // HDR próprio: 3 fotos (-2 EV, normal, +2 EV), alinhadas e fundidas, mantendo as cores da normal.
    fun takeHdr(extra: Float, roll: Float, params: Hdr.Params) {
        val cap = imageCapture ?: return
        val cam0 = camera ?: return
        val exp = cam0.cameraInfo.exposureState
        val range = exp.exposureCompensationRange
        val step = exp.exposureCompensationStep.toFloat()
        val originalExposure = exp.exposureCompensationIndex

        if (!exp.isExposureCompensationSupported || step <= 0f) {
            status = "HDR indisponível neste aparelho; foto normal"
            takeSingle(extra, roll, false)
            return
        }

        val under = (-2f / step).roundToInt().coerceIn(range.lower, range.upper)
        val over = (2f / step).roundToInt().coerceIn(range.lower, range.upper)

        if (abs(under * step) < 0.9f || abs(over * step) < 0.9f) {
            status = "HDR indisponível (pouca variação de exposição); foto normal"
            takeSingle(extra, roll, false)
            return
        }

        photoBusy = true
        val dir = pendingDir()
        val name = "IMG_${stamp()}"
        val fU = File(dir, "${name}_u.jpg")
        val fB = File(dir, "${name}_b.jpg")
        val fO = File(dir, "${name}_o.jpg")
        val out = File(dir, "$name.jpg")

        status = "HDR: segure firme…"

        workExecutor.execute {
            var outputReady = false

            try {
                val plan = listOf(0 to fB, under to fU, over to fO)

                for ((i, pair) in plan.withIndex()) {
                    val (idx, file) = pair

                    // Não continua se a câmera não aplicar a exposição solicitada.
                    cam0.cameraControl
                        .setExposureCompensationIndex(idx)
                        .get(3, TimeUnit.SECONDS)

                    Thread.sleep(400)
                    mainExecutor.execute {
                        status = "HDR: foto ${i + 1}/3…"
                    }

                    val latch = CountDownLatch(1)
                    val ok = AtomicBoolean(false)
                    val captureError = java.util.concurrent.atomic.AtomicReference<String?>(null)

                    mainExecutor.execute {
                        try {
                            cap.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ioExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(
                                        output: ImageCapture.OutputFileResults
                                    ) {
                                        ok.set(true)
                                        latch.countDown()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        captureError.set(exception.message)
                                        latch.countDown()
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            captureError.set(e.message)
                            latch.countDown()
                        }
                    }

                    if (!latch.await(20, TimeUnit.SECONDS)) {
                        throw IOException("Tempo esgotado ao capturar a foto ${i + 1}")
                    }

                    if (!ok.get()) {
                        throw IOException(
                            captureError.get() ?: "Falha ao capturar a foto ${i + 1}"
                        )
                    }
                }

                mainExecutor.execute {
                    status = "HDR: combinando as fotos…"
                }

                try {
                    Hdr.fuse(fU, fB, fO, out, params)
                } catch (e: Exception) {
                    if (!fB.isFile || fB.length() <= 0L) {
                        throw IOException("HDR falhou e a foto normal não está disponível", e)
                    }

                    fB.copyTo(out, overwrite = true)
                    mainExecutor.execute {
                        status = "HDR falhou (${e.javaClass.simpleName}); usei a foto normal"
                    }
                }

                if (!out.isFile || out.length() <= 0L) {
                    throw IOException("A imagem final do HDR está vazia")
                }

                outputReady = true
                finishStill(out, extra, roll, false)

            } catch (e: Exception) {
                // Se a foto normal já existe, preserva-a mesmo que outra captura falhe.
                try {
                    if (fB.isFile && fB.length() > 0L) {
                        fB.copyTo(out, overwrite = true)

                        if (out.isFile && out.length() > 0L) {
                            outputReady = true
                            mainExecutor.execute {
                                status = "HDR interrompido; usando a foto normal"
                            }
                            finishStill(out, extra, roll, false)
                        } else {
                            throw IOException("Não foi possível preservar a foto normal")
                        }
                    } else {
                        mainExecutor.execute {
                            status = "Erro no HDR: ${e.message}"
                        }
                    }
                } catch (fallbackError: Exception) {
                    mainExecutor.execute {
                        status = "Erro no HDR: ${fallbackError.message}"
                    }
                }
            } finally {
                // Restaura exatamente a exposição que estava ativa antes do HDR.
                try {
                    cam0.cameraControl
                        .setExposureCompensationIndex(originalExposure)
                        .get(3, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }

                // As três capturas intermediárias nunca são necessárias após a fusão.
                fU.delete()
                fB.delete()
                fO.delete()

                if (!outputReady) {
                    out.delete()
                }

                mainExecutor.execute {
                    photoBusy = false
                }
            }
        }
    }

    fun takePhoto() {
        if (imageCapture == null || photoBusy) return
        val extra = digital
        val back = !facingFront
        val roll = if (back && horizonOn && abs(tilt) in 0.5f..30f) tilt else 0f
        val useHdr = back && lensId == null && when (hdrMode) {
            "off" -> false
            "auto" -> sceneNeedsHdr(previewView)
            else -> true
        }
        if (useHdr) {
            takeHdr(extra, roll, if (hdrMode == "forte") Hdr.FORTE else Hdr.NATURAL)
        } else {
            takeSingle(extra, roll, facingFront)
        }
    }

    // Live: foto + clipe de ~2,5 s gravado a partir do clique, juntados em uma Motion Photo.
    fun takeLive() {
        val cap = imageCapture ?: return
        val vc = videoCapture ?: return
        if (liveBusy) return
        liveBusy = true
        val dir = pendingDir()
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

    // Salva o vídeo final na galeria, preservando o original em caso de falha.
    fun saveRecordedVideo(source: File, original: File, name: String, warning: String? = null) {
        workExecutor.execute {
            try {
                Gallery.saveVideoFile(ctx, source, name)
                if (source != original) source.delete()
                original.delete()
                mainExecutor.execute {
                    status = warning ?: "Vídeo salvo na galeria"
                }
            } catch (e: Exception) {
                mainExecutor.execute {
                    status = "Gravou, mas não salvou na galeria: ${e.message}"
                }
            }
        }
    }

    // Vídeo normal: espelha somente quando a câmera frontal e a opção estão ativas.
    fun toggleRecording() {
        val vc = videoCapture ?: return
        val current = recording
        if (current != null) {
            current.stop()
            return
        }

        val name = "ZOOMIX_VID_${stamp()}"
        val file = File(pendingDir(), "$name.mp4")
        val mirrorFile = File(pendingDir(), "${name}_MIRROR.mp4")
        val mirrorFront = facingFront && SecureStore.selfieMirror(ctx)

        var pending = vc.output.prepareRecording(
            ctx,
            FileOutputOptions.Builder(file).build()
        )
        if (SecureStore.micOn(ctx) && audioGranted) {
            pending = pending.withAudioEnabled()
        }

        recSeconds = 0
        status = "Gravando…"

        recording = pending.start(mainExecutor) { ev ->
            when (ev) {
                is VideoRecordEvent.Status -> {
                    recSeconds = (
                        ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    ).toInt()
                }

                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recSeconds = 0

                    if (ev.hasError()) {
                        val why = ev.cause?.message ?: "sem detalhes"
                        status = "Erro ao gravar o vídeo (código ${ev.error}): $why"
                        file.delete()
                        mirrorFile.delete()
                    } else {
                        if (mirrorFront) {
                            status = "Espelhando vídeo…"
                            VideoMirror.transform(ctx, file, mirrorFile) { success, error ->
                                if (success && mirrorFile.isFile && mirrorFile.length() > 0L) {
                                    status = "Salvando o vídeo na galeria…"
                                    saveRecordedVideo(mirrorFile, file, name)
                                } else {
                                    mirrorFile.delete()
                                    status = "Falha no espelhamento; salvando vídeo original…"
                                    saveRecordedVideo(
                                        file,
                                        file,
                                        name,
                                        "Vídeo salvo sem espelhamento: ${error ?: "falha na conversão"}"
                                    )
                                }
                            }
                        } else {
                            status = "Salvando o vídeo na galeria…"
                            saveRecordedVideo(file, file, name)
                        }
                    }
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

    val busy = recording != null || liveBusy || photoBusy

    // ---------- interface ----------

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

        // Gesto de pinça para o zoom.
        Box(
            Modifier.fillMaxSize().pointerInput(hwMin, maxZoom) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(hwMin, max(maxZoom, hwMin))
                }
            }
        )

        // Controle lateral de zoom com escala logarítmica.
        val zoomFraction = if (hwMin > 0f && maxZoom > hwMin) {
            (ln(zc / hwMin) / ln(maxZoom / hwMin)).coerceIn(0f, 1f)
        } else {
            0f
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (zoomBarOpen) {
                Text(
                    fmtZoom(zc),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(
                        shadow = Shadow(Color.Black, Offset(0f, 1f), 4f)
                    )
                )

                Box(
                    Modifier
                        .width(44.dp)
                        .height(220.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x99111827))
                        .border(
                            1.dp,
                            Color(0x88FFFFFF),
                            RoundedCornerShape(24.dp)
                        )
                        .pointerInput(hwMin, maxZoom) {
                            val trackHeight = size.height.toFloat().coerceAtLeast(1f)
                            val range = (maxZoom / hwMin).coerceAtLeast(1f)

                            fun updateFromPosition(y: Float) {
                                val fraction = (1f - y / trackHeight).coerceIn(0f, 1f)
                                zoom = (hwMin * range.pow(fraction))
                                    .coerceIn(hwMin, max(maxZoom, hwMin))
                            }

                            detectDragGestures(
                                onDragStart = { position ->
                                    updateFromPosition(position.y)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    updateFromPosition(change.position.y)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight(0.8f)
                            .background(Color(0x99FFFFFF), CircleShape)
                    )

                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 22.dp)
                            .graphicsLayer {
                                translationY = (1f - zoomFraction) * 176.dp.toPx()
                            }
                            .size(18.dp)
                            .background(YELLOW, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            }

            Bubble(
                if (zoomBarOpen) "Fechar" else "Zoom",
                tier,
                selected = zoomBarOpen
            ) {
                zoomBarOpen = !zoomBarOpen
            }
        }

        // Linha de nível do horizon lock: fica reta quando o celular está nivelado.
        if (horizonOn && cam == Cam.FOTO && !facingFront) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(140.dp)
                    .height(2.dp)
                    .rotate(-tilt)
                    .background(if (abs(tilt) < 1f) Color(0xFF4CAF50) else Color.White)
            )
        }

        // Bolhas do topo.
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Bubble("Ajustes", tier) { onOpenSettings() }
            if (cam == Cam.FOTO && !facingFront) {
                val hdrLabel = when (hdrMode) {
                    "off" -> "HDR desl."
                    "auto" -> "HDR auto"
                    "natural" -> "HDR natural"
                    else -> "HDR forte"
                }
                Bubble(hdrLabel, tier, selected = hdrMode != "off") {
                    hdrMode = when (hdrMode) {
                        "off" -> "auto"
                        "auto" -> "natural"
                        "natural" -> "forte"
                        else -> "off"
                    }
                    SecureStore.setHdrMode(ctx, hdrMode)
                }
                if (strong) {
                    Bubble("Nível", tier, selected = horizonOn) {
                        horizonOn = !horizonOn
                        SecureStore.setHorizon(ctx, horizonOn)
                    }
                }
            }
        }

        // Mini-tela do zoom digital, no canto superior esquerdo.
        val mi = mapImage
        if (showMap && mi != null) {
            val mapShape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 64.dp)
                    .width(110.dp)
                    .aspectRatio(mi.width.toFloat() / mi.height.toFloat())
                    .clip(mapShape)
                    .border(1.dp, Color(0xB3FFFFFF), mapShape)
            ) {
                Image(
                    bitmap = mi,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(1f / digital)
                        .fillMaxHeight(1f / digital)
                        .border(1.5.dp, YELLOW)
                )
            }
        }

        // Controles de baixo (bolhas flutuantes).
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (status.isNotEmpty()) {
                Bubble(status, tier, modifier = Modifier.widthIn(max = 320.dp))
            }
            if (recording != null) {
                Bubble(String.format(Locale.US, "● %02d:%02d", recSeconds / 60, recSeconds % 60), tier, selected = true)
            }

            // Lentes (só as que o aparelho entrega para apps de terceiros).
            if (!facingFront && lenses.size > 1) {
                val mainId = lenses.firstOrNull { it.isMain }?.id
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lenses.forEach { l ->
                        val sel = (lensId ?: mainId) == l.id
                        Bubble(l.label, tier, selected = sel) {
                            if (!busy) lensId = if (l.isMain) null else l.id
                        }
                    }
                }
            }

            // Zoom: bolhas de atalho (a ativa mostra o zoom atual) + pinça na tela.
            val presets = buildList {
                if (hwMin < 0.95f) add(hwMin)
                add(1f)
                listOf(2f, 5f, 10f, 20f, 50f).forEach { if (it <= maxZoom + 0.01f) add(it) }
            }.distinct()
            val activePreset = presets.lastOrNull { it <= zc + 0.02f } ?: presets.first()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { pz ->
                    val active = pz == activePreset
                    val label = if (active) fmtZoom(zc) + (if (digital > 1.01f) " digital" else "") else fmtZoom(pz)
                    Bubble(label, tier, selected = active) { zoom = pz }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cam.values().forEach { m ->
                    Bubble(m.label, tier, selected = m == cam) { if (!busy) selectMode(m) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Botão da galeria (miniatura da última foto/vídeo).
                val th = thumb
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0xB3FFFFFF), CircleShape)
                        .clickable {
                            if (!openGallery(ctx, latest)) status = "Nenhum app de galeria encontrado"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (th != null) {
                        Image(
                            bitmap = th,
                            contentDescription = "Galeria",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("Galeria", color = Color.White, fontSize = 10.sp)
                    }
                }

                // Botão de disparar.
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
                        .clickable(enabled = !(liveBusy || photoBusy)) { onShutter() }
                )

                // Virar a câmera (traseira/frontal).
                Bubble("Virar", tier) {
                    if (!busy) {
                        facingFront = !facingFront
                        lensId = null
                    }
                }
            }
        }
    }
}
