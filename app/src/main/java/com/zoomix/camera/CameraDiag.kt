package com.zoomix.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.Locale
import kotlin.math.hypot

data class Lens(val id: String, val label: String, val isMain: Boolean)

object Lenses {
    /** Lentes traseiras que o aparelho entrega para apps de terceiros (varia muito entre modelos). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun detectBack(p: ProcessCameraProvider): List<Lens> {
        class Cand(val id: String, val equiv: Float, val minFocus: Float)

        val cands = mutableListOf<Cand>()
        for (info in p.availableCameraInfos) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: continue
                val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val diag = hypot(size.width, size.height)
                if (diag <= 0f) continue
                val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                cands += Cand(c2.cameraId, f * 43.27f / diag, minFocus)
            } catch (_: Exception) {
            }
        }
        if (cands.isEmpty()) return emptyList()

        val main = cands.first()
        val out = mutableListOf<Pair<Int, Lens>>()
        out += 1 to Lens(main.id, "Principal", true)
        for (c in cands.drop(1)) {
            when {
                c.equiv <= 19f -> out += 0 to Lens(c.id, "Ultra", false)
                c.minFocus >= 15f -> out += 2 to Lens(c.id, "Macro", false)
                c.equiv >= 55f -> out += 3 to Lens(c.id, "Tele", false)
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun selectorFor(id: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()
}

object CameraDiag {
    /** Linhas de texto descrevendo cada câmera que o Android informa (para diagnóstico em Ajustes). */
    fun describeCameras(ctx: Context): List<String> {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.map { id ->
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "traseira"
                    CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
                    else -> "externa"
                }
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val equiv = if (f != null && size != null) f * 43.27f / hypot(size.width, size.height) else null
                val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val physical = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds else emptySet<String>()
                buildString {
                    append("Câmera $id · $facing")
                    if (equiv != null) append(String.format(Locale.US, " · ~%.0f mm", equiv))
                    if (pixels != null) append(" · ${pixels.width}x${pixels.height}")
                    if (minFocus != null && minFocus > 0f) append(String.format(Locale.US, " · foco mín. %.0f dioptrias", minFocus))
                    if (physical.isNotEmpty()) append(" · físicas: ${physical.joinToString(",")}")
                }
            }
        } catch (e: Exception) {
            listOf("Não foi possível listar as câmeras: ${e.message}")
        }
    }
}
