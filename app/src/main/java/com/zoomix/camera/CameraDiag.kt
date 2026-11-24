package com.zoomix.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
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
    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        null -> "indisponível"
        else -> "desconhecido ($level)"
    }

    private fun capabilityNames(values: IntArray?): String =
        values?.joinToString(", ") { value ->
            when (value) {
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
                else -> "desconhecida($value)"
            }
        } ?: "indisponível"
    private fun videoStabilizationNames(values: IntArray?): String = values?.joinToString(", ") { when (it) { CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"; CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"; CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STABILIZATION"; else -> "desconhecido(" + it + ")" } } ?: "indisponível"

    private fun opticalStabilizationNames(values: IntArray?): String = values?.joinToString(", ") { when (it) { CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"; CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"; else -> "desconhecido(" + it + ")" } } ?: "indisponível"

    /** Linhas de texto descrevendo cada câmera que o Android informa (para diagnóstico em Ajustes). */
    fun describeCameras(ctx: Context): List<String> {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val physicalOwners = mutableMapOf<String, MutableList<String>>()
            if (Build.VERSION.SDK_INT >= 28) {
                ids.forEach { logicalId ->
                    (try { cm.getCameraCharacteristics(logicalId).physicalCameraIds } catch (_: Exception) { emptySet<String>() }).forEach { physicalId ->
                        physicalOwners.getOrPut(physicalId) { mutableListOf() }.add(logicalId)
                    }
                }
            }
            (ids + physicalOwners.keys).distinct().map { id ->
                val c = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { null } ?: return@map "Câmera $id · características indisponíveis"
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
                val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isPublic = id in ids
                val isLogical = isPublic && (physical.isNotEmpty() || capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true)
                val role = when {
                    isPublic == false && physicalOwners.containsKey(id) -> "física associada"
                    isLogical -> "lógica"
                    else -> "individual"
                }
                buildString {
                    append("Câmera $id · $facing · $role")
                    if (isPublic == false && physicalOwners.containsKey(id)) append(" · associada a: ${physicalOwners[id]?.joinToString(",")}")
                    if (equiv != null) append(String.format(Locale.US, " · ~%.0f mm", equiv))
                    if (pixels != null) append(" · ${pixels.width}x${pixels.height}")
                    if (minFocus != null && minFocus > 0f) append(String.format(Locale.US, " · foco mín. %.0f dioptrias", minFocus))
                    if (physical.isNotEmpty()) append(" · físicas: ${physical.joinToString(",")}")
                    if (activeArray != null) append(" · área ativa: ${activeArray.width()}x${activeArray.height()}")
                    append(" · hardware: ${hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
                    append(" · capacidades: ${capabilityNames(capabilities)}")
                    append(" · EIS Camera2: ${videoStabilizationNames(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES))}")
                    append(" · OIS: ${opticalStabilizationNames(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))}")
                }
            }
        } catch (e: Exception) {
            listOf("Não foi possível listar as câmeras: ${e.message}")
        }
    }
}
