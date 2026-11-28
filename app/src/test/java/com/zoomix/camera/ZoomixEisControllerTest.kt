package com.zoomix.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomixEisControllerTest {

    private fun motion(
        dx: Float,
        dy: Float,
        confidence: Float = 0.9f,
        validBlocks: Int = 20
    ) = ZoomixMotionEstimator.MotionEstimate(
        dx = dx,
        dy = dy,
        confidence = confidence,
        validBlocks = validBlocks
    )

    @Test
    fun movementShouldBeCompensatedInOppositeDirection() {
        val controller = ZoomixEisController(
            maxShift = 10f,
            smoothing = 1f
        )

        val result = controller.update(motion(2f, -3f))

        assertEquals(-2f, result.xPixels, 0.001f)
        assertEquals(3f, result.yPixels, 0.001f)
    }

    @Test
    fun correctionShouldRespectMaximumShift() {
        val controller = ZoomixEisController(
            maxShift = 2f,
            smoothing = 1f
        )

        val result = controller.update(motion(20f, -20f))

        assertEquals(-2f, result.xPixels, 0.001f)
        assertEquals(2f, result.yPixels, 0.001f)
    }

    @Test
    fun smoothingShouldApproachTargetGradually() {
        val controller = ZoomixEisController(
            maxShift = 10f,
            smoothing = 0.2f
        )

        val first = controller.update(motion(5f, 0f))
        val second = controller.update(motion(0f, 0f))

        assertTrue(first.xPixels < 0f)
        assertTrue(second.xPixels < first.xPixels)
        assertTrue(second.xPixels > -5f)
    }

    @Test
    fun invalidMotionShouldRecenterGradually() {
        val controller = ZoomixEisController(
            maxShift = 10f,
            smoothing = 1f,
            recenterRate = 0.5f
        )

        controller.update(motion(4f, 0f))
        val result = controller.update(
            motion(0f, 0f, confidence = 0.1f)
        )

        assertTrue(result.xPixels > -4f)
        assertTrue(result.xPixels < 0f)
    }
}
