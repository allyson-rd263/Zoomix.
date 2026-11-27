package com.zoomix.camera

import kotlin.math.abs
import kotlin.math.max

/**
 * Estima o deslocamento global entre dois frames em escala de cinza.
 *
 * A imagem deve ser fornecida como ByteArray, com um byte por pixel,
 * em ordem de linhas, com valores de luminância entre 0 e 255.
 *
 * Não acessa câmera, sensores ou OpenGL.
 */
class ZoomixMotionEstimator(
    private val width: Int = 160,
    private val height: Int = 90,
    private val blockSize: Int = 8,
    private val gridSpacing: Int = 16,
    private val searchRadius: Int = 6
) {

    data class MotionEstimate(
        val dx: Float,
        val dy: Float,
        val confidence: Float,
        val validBlocks: Int
    )

    private data class BlockMatch(
        val dx: Int,
        val dy: Int,
        val error: Float
    )

    private var previousFrame: ByteArray? = null

    init {
        require(width > 2 * searchRadius + blockSize)
        require(height > 2 * searchRadius + blockSize)
        require(blockSize >= 4)
        require(gridSpacing >= blockSize)
        require(searchRadius >= 1)
    }

    /**
     * A primeira imagem apenas estabelece a referência.
     * Nas chamadas seguintes, retorna o movimento estimado.
     */
    fun estimate(currentFrame: ByteArray): MotionEstimate? {
        require(currentFrame.size == width * height) {
            "Dimensão inválida: esperado ${width * height} pixels."
        }

        val previous = previousFrame
        previousFrame = currentFrame.copyOf()

        if (previous == null) return null

        val matches = ArrayList<BlockMatch>()

        val startX = searchRadius + blockSize / 2
        val startY = searchRadius + blockSize / 2
        val endX = width - searchRadius - blockSize / 2
        val endY = height - searchRadius - blockSize / 2

        var y = startY
        while (y < endY) {
            var x = startX

            while (x < endX) {
                val match = matchBlock(previous, currentFrame, x, y)

                if (match != null) {
                    matches.add(match)
                }

                x += gridSpacing
            }

            y += gridSpacing
        }

        if (matches.size < 4) return null

        val medianDx = median(matches.map { it.dx.toFloat() })
        val medianDy = median(matches.map { it.dy.toFloat() })

        val deviations = matches.map {
            max(
                abs(it.dx - medianDx),
                abs(it.dy - medianDy)
            )
        }

        val medianDeviation = median(deviations)
        val tolerance = max(1.5f, medianDeviation * 2.5f)

        val inliers = matches.filter {
            abs(it.dx - medianDx) <= tolerance &&
                abs(it.dy - medianDy) <= tolerance
        }

        if (inliers.size < 4) return null

        val dx = median(inliers.map { it.dx.toFloat() })
        val dy = median(inliers.map { it.dy.toFloat() })

        val consistency = inliers.size.toFloat() / matches.size
        val averageError = inliers.map { it.error }.average().toFloat()

        val errorConfidence = (1f - averageError / 48f).coerceIn(0f, 1f)
        val confidence = (consistency * errorConfidence).coerceIn(0f, 1f)

        if (confidence < 0.15f) return null

        return MotionEstimate(
            dx = dx,
            dy = dy,
            confidence = confidence,
            validBlocks = inliers.size
        )
    }

    private fun matchBlock(
        previous: ByteArray,
        current: ByteArray,
        centerX: Int,
        centerY: Int
    ): BlockMatch? {
        val half = blockSize / 2
        val sampleStep = 2

        var sum = 0
        var sumSquared = 0
        var sampleCount = 0

        var y = -half
        while (y < half) {
            var x = -half
            while (x < half) {
                val value = previous[
                    (centerY + y) * width + centerX + x
                ].toInt() and 0xFF

                sum += value
                sumSquared += value * value
                sampleCount++
                x += sampleStep
            }
            y += sampleStep
        }

        val varianceNumerator =
            sumSquared * sampleCount - sum * sum

        if (varianceNumerator < sampleCount * sampleCount * 12) {
            return null
        }

        var bestError = Float.MAX_VALUE
        var secondBestError = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var error = 0
                var count = 0

                var by = -half
                while (by < half) {
                    var bx = -half

                    while (bx < half) {
                        val previousValue = previous[
                            (centerY + by) * width + centerX + bx
                        ].toInt() and 0xFF

                        val currentValue = current[
                            (centerY + by + dy) * width +
                                centerX + bx + dx
                        ].toInt() and 0xFF

                        error += abs(previousValue - currentValue)
                        count++
                        bx += sampleStep
                    }

                    by += sampleStep
                }

                val averageError = error.toFloat() / count

                if (averageError < bestError) {
                    secondBestError = bestError
                    bestError = averageError
                    bestDx = dx
                    bestDy = dy
                } else if (averageError < secondBestError) {
                    secondBestError = averageError
                }
            }
        }

        if (bestError > 48f) return null

        if (secondBestError < Float.MAX_VALUE &&
            secondBestError - bestError < 0.5f
        ) {
            return null
        }

        return BlockMatch(
            dx = bestDx,
            dy = bestDy,
            error = bestError
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f

        val sorted = values.sorted()
        val middle = sorted.size / 2

        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    fun reset() {
        previousFrame = null
    }
}
