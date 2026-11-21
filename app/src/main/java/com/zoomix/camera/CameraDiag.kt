package com.zoomix.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object CameraDiag {
    /** Resolução total do sensor da câmera traseira principal (pode ser maior que a saída em JPEG). */
    fun sensorPixels(ctx: Context): Pair<Int, Int>? {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    val s = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    if (s != null) return s.width to s.height
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
