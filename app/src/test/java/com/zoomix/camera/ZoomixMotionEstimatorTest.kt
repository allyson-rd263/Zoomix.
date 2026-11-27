package com.zoomix.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class ZoomixMotionEstimatorTest {

    private val width = 160
    private val height = 90

    private fun createTexture(): ByteArray {
        val random = Random(12345L)
        return ByteArray(width * height) {
            (random.nextInt(216) + 20).toByte()
        }
    }

    private fun shiftImage(
        source: ByteArray,
        dx: Int,
        dy: Int
    ): ByteArray {
        val shifted = ByteArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = x - dx
                val sourceY = y - dy

                if (sourceX in 0 until width && sourceY in 0 until height) {
                    shifted[y * width + x] =
                        source[sourceY * width + sourceX]
                }
            }
        }

        return shifted
    }

    @Test
    fun identicalFramesShouldProduceZeroMotion() {
        val estimator = ZoomixMotionEstimator()
        val frame = createTexture()

        assertNull(estimator.estimate(frame))

        val result = estimator.estimate(frame)

        assertNotNull(result)
        assertEquals(0f, result!!.dx, 0.5f)
        assertEquals(0f, result.dy, 0.5f)
    }

    @Test
    fun horizontalShiftShouldBeDetected() {
        val estimator = ZoomixMotionEstimator()
        val original = createTexture()
        val shifted = shiftImage(original, 3, 0)

        estimator.estimate(original)
        val result = estimator.estimate(shifted)

        assertNotNull(result)
        assertEquals(3f, result!!.dx, 0.5f)
        assertEquals(0f, result.dy, 0.5f)
    }

    @Test
    fun verticalShiftShouldBeDetected() {
        val estimator = ZoomixMotionEstimator()
        val original = createTexture()
        val shifted = shiftImage(original, 0, -2)

        estimator.estimate(original)
        val result = estimator.estimate(shifted)

        assertNotNull(result)
        assertEquals(0f, result!!.dx, 0.5f)
        assertEquals(-2f, result.dy, 0.5f)
    }
}
