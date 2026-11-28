package com.zoomix.camera

import kotlin.math.abs
import kotlin.math.max

/**
 * Converte movimento estimado entre frames em compensação
 * acumulada para estabilização eletrônica.
 *
 * As correções são expressas em pixels da imagem de análise.
 */
internal class ZoomixEisController(
    private val width: Int = 160,
    private val height: Int = 90,
    private val maxShift: Float = 4f,
    private val smoothing: Float = 0.18f,
    private val recenterRate: Float = 0.998f
) {
    data class Correction(
        val xPixels: Float,
        val yPixels: Float,
        val offsetX: Float,
        val offsetY: Float,
        val cropScale: Float
    )

    private var targetX = 0f
    private var targetY = 0f
    private var currentX = 0f
    private var currentY = 0f

    init {
        require(width > 0 && height > 0)
        require(maxShift > 0f)
        require(smoothing in 0f..1f)
        require(recenterRate in 0f..1f)
        require(maxShift < minOf(width, height) / 2f)
    }

    fun update(
        estimate: ZoomixMotionEstimator.MotionEstimate?
    ): Correction {
        if (estimate != null &&
            estimate.confidence >= 0.20f &&
            estimate.validBlocks >= 4
        ) {
            targetX = (targetX - estimate.dx)
                .coerceIn(-maxShift, maxShift)

            targetY = (targetY - estimate.dy)
                .coerceIn(-maxShift, maxShift)
        } else {
            targetX *= recenterRate
            targetY *= recenterRate
        }

        currentX += (targetX - currentX) * smoothing
        currentY += (targetY - currentY) * smoothing

        return correction()
    }

    fun reset(): Correction {
        targetX = 0f
        targetY = 0f
        currentX = 0f
        currentY = 0f
        return correction()
    }

    private fun correction(): Correction {
        val marginX = maxShift / width
        val marginY = maxShift / height

        val cropScale = max(
            1f / (1f - 2f * marginX),
            1f / (1f - 2f * marginY)
        )

        return Correction(
            xPixels = currentX,
            yPixels = currentY,
            offsetX = currentX / width,
            offsetY = currentY / height,
            cropScale = cropScale
        )
    }
}
