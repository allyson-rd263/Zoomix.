package com.zoomix.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.util.Log
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline OpenGL de passagem dos frames do CameraX.
 *
 * Cada entrada possui sua própria textura OES.
 * Aplica compensação eletrônica por análise de movimento dos quadros na GPU.
 */
class ZoomixSurfaceProcessor(
    private val eisEnabled: Boolean = true,
    private val onError: (Throwable) -> Unit
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener, AutoCloseable {

    companion object {
        private const val TAG = "ZoomixSurfaceProcessor"
        private const val ANALYSIS_WIDTH = 160
        private const val ANALYSIS_HEIGHT = 90
        private const val ANALYSIS_BYTES = ANALYSIS_WIDTH * ANALYSIS_HEIGHT * 4
    }

    private val thread = HandlerThread("Zoomix-GL").apply { start() }
    private val handler = Handler(thread.looper)
    internal val executor = Executor { command ->
        if (!handler.post(command)) {
            throw RejectedExecutionException(
                "A thread Zoomix-GL não aceitou a tarefa."
            )
        }
    }

    private var eglInitialized = false
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: android.opengl.EGLConfig? = null
    private var pbuffer = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var transformLocation = -1
    private var samplerLocation = -1
    private var eisOffsetLocation = -1
    private var eisScaleLocation = -1

    private var eisActive = eisEnabled
    private var analysisTexture = 0
    private var analysisFramebuffer = 0
    private val analysisPbos = IntArray(2)
    private var analysisPendingIndex = -1
    private var analysisWriteIndex = 0

    private val motionEstimator = ZoomixMotionEstimator(
        width = ANALYSIS_WIDTH,
        height = ANALYSIS_HEIGHT
    )
    private val eisController = ZoomixEisController(
        width = ANALYSIS_WIDTH,
        height = ANALYSIS_HEIGHT,
        maxShift = 2f
    )
    private var eisCorrection = eisController.reset()

    private data class Input(
        val textureId: Int,
        val texture: SurfaceTexture,
        val surface: Surface
    )

    private data class Output(
        val surface: Surface,
        val eglSurface: android.opengl.EGLSurface,
        val width: Int,
        val height: Int,
        val targets: Int
    )

    private val inputs = LinkedHashMap<SurfaceTexture, Input>()
    private val outputs = LinkedHashMap<SurfaceOutput, Output>()
    private val firstFrameOutputs = HashSet<SurfaceOutput>()

    private var activeInput: SurfaceTexture? = null

    private val releaseRequested = AtomicBoolean(false)
    private var released = false

    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    private val vertices = floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
    )

    override fun onInputSurface(request: SurfaceRequest) {
        if (releaseRequested.get()) {
            request.willNotProvideSurface()
            return
        }

        var textureId = 0
        var createdTexture: SurfaceTexture? = null
        var createdSurface: Surface? = null

        try {
            Log.i(TAG, "Solicitação de entrada: ${request.resolution.width}x${request.resolution.height}")
            initializeGl()

            textureId = createExternalTexture()

            val inputTexture = SurfaceTexture(textureId)
            createdTexture = inputTexture
            inputTexture.setDefaultBufferSize(
                request.resolution.width,
                request.resolution.height
            )

            val inputSurface = Surface(inputTexture)
            createdSurface = inputSurface

            inputs[inputTexture] = Input(
                textureId = textureId,
                texture = inputTexture,
                surface = inputSurface
            )

            activeInput = inputTexture
            inputTexture.setOnFrameAvailableListener(this, handler)

            request.provideSurface(inputSurface, executor) {
                handler.post {
                    Log.i(TAG, "CameraX liberou a superfície de entrada")
                    releaseInput(inputTexture)
                }
            }
            Log.i(TAG, "Superfície de entrada fornecida ao CameraX")
        } catch (error: Throwable) {
            val inputTexture = createdTexture

            if (inputTexture != null) {
                inputTexture.setOnFrameAvailableListener(null)
                inputs.remove(inputTexture)

                if (activeInput === inputTexture) {
                    activeInput = inputs.keys.lastOrNull()
                }
            }

            try {
                createdSurface?.release()
            } catch (_: Throwable) {
            }

            if (inputTexture != null) {
                try {
                    inputTexture.release()
                } catch (_: Throwable) {
                }
            }

            if (
                textureId != 0 &&
                display != EGL14.EGL_NO_DISPLAY &&
                context != EGL14.EGL_NO_CONTEXT &&
                pbuffer != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            ) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            }

            reportError(error)

            try {
                request.willNotProvideSurface()
            } catch (_: Throwable) {
            }

            checkReadyToRelease()
            throw ProcessingException().apply { initCause(error) }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (releaseRequested.get()) {
            surfaceOutput.close()
            return
        }

        var createdEglSurface = EGL14.EGL_NO_SURFACE

        try {
            initializeGl()

            val outputSurface = surfaceOutput.getSurface(executor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    Log.i(TAG, "CameraX solicitou fechamento da saída: targets=${event.surfaceOutput.targets}")
                    handler.post {
                        releaseOutput(event.surfaceOutput)
                    }
                }
            }

            val size = surfaceOutput.size
            val targets = surfaceOutput.targets
            Log.i(TAG, "Saída recebida: targets=$targets, ${size.width}x${size.height}")

            createdEglSurface = EGL14.eglCreateWindowSurface(
                display,
                requireNotNull(config),
                outputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )

            check(createdEglSurface != EGL14.EGL_NO_SURFACE) {
                "Não foi possível criar a superfície EGL de saída: ${EGL14.eglGetError()}"
            }

            outputs[surfaceOutput] = Output(
                surface = outputSurface,
                eglSurface = createdEglSurface,
                width = size.width,
                height = size.height,
                targets = targets
            )
            Log.i(TAG, "Superfície EGL de saída criada: targets=$targets")
        } catch (error: Throwable) {
            if (
                createdEglSurface != EGL14.EGL_NO_SURFACE &&
                display != EGL14.EGL_NO_DISPLAY
            ) {
                if (
                    context != EGL14.EGL_NO_CONTEXT &&
                    pbuffer != EGL14.EGL_NO_SURFACE
                ) {
                    EGL14.eglMakeCurrent(
                        display,
                        pbuffer,
                        pbuffer,
                        context
                    )
                }

                EGL14.eglDestroySurface(display, createdEglSurface)
            }

            reportError(error)

            try {
                surfaceOutput.close()
            } catch (_: Throwable) {
            }

            checkReadyToRelease()
            throw ProcessingException().apply { initCause(error) }
        }
    }

    private fun releaseInput(texture: SurfaceTexture) {
        val input = inputs.remove(texture) ?: return

        texture.setOnFrameAvailableListener(null)

        if (activeInput === texture) {
            activeInput = inputs.keys.lastOrNull()
        }

        if (
            display != EGL14.EGL_NO_DISPLAY &&
            context != EGL14.EGL_NO_CONTEXT &&
            pbuffer != EGL14.EGL_NO_SURFACE
        ) {
            val current = EGL14.eglMakeCurrent(
                display,
                pbuffer,
                pbuffer,
                context
            )

            if (current) {
                GLES20.glDeleteTextures(
                    1,
                    intArrayOf(input.textureId),
                    0
                )
            } else {
                reportError(
                    IllegalStateException(
                        "Não foi possível ativar o contexto EGL para liberar a textura de entrada: ${EGL14.eglGetError()}"
                    )
                )
            }
        }

        try {
            input.surface.release()
        } catch (error: Throwable) {
            reportError(error)
        }

        try {
            input.texture.release()
        } catch (error: Throwable) {
            reportError(error)
        }

        checkReadyToRelease()
    }

    private fun releaseOutput(surfaceOutput: SurfaceOutput) {
        val output = outputs.remove(surfaceOutput) ?: return
        firstFrameOutputs.remove(surfaceOutput)
        Log.i(TAG, "Liberando saída: targets=${output.targets}")

        if (display != EGL14.EGL_NO_DISPLAY) {
            if (
                context != EGL14.EGL_NO_CONTEXT &&
                pbuffer != EGL14.EGL_NO_SURFACE
            ) {
                val current = EGL14.eglMakeCurrent(
                    display,
                    pbuffer,
                    pbuffer,
                    context
                )

                if (!current) {
                    reportError(
                        IllegalStateException(
                            "Não foi possível ativar o contexto EGL antes de liberar a saída: ${EGL14.eglGetError()}"
                        )
                    )
                }
            }

            val destroyed = EGL14.eglDestroySurface(
                display,
                output.eglSurface
            )

            if (!destroyed) {
                reportError(
                    IllegalStateException(
                        "Falha ao destruir a superfície EGL de saída: ${EGL14.eglGetError()}"
                    )
                )
            }
        }

        try {
            surfaceOutput.close()
        } catch (error: Throwable) {
            reportError(error)
        }

        checkReadyToRelease()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (released) return

        val input = inputs[surfaceTexture] ?: return

        // Durante uma reconfiguração, somente a entrada mais recente
        // alimenta as saídas. As anteriores permanecem alocadas até
        // o CameraX confirmar que podem ser liberadas.
        if (surfaceTexture !== activeInput) return

        try {
            check(
                EGL14.eglMakeCurrent(
                    display,
                    pbuffer,
                    pbuffer,
                    context
                )
            ) {
                "Não foi possível ativar o contexto EGL antes de atualizar a textura."
            }

            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureMatrix)

            val timestamp = surfaceTexture.timestamp

            if (eisActive) {
                try {
                    analyzeFrame(input.textureId)
                } catch (error: Throwable) {
                    Log.e(TAG, "EIS desativado após falha na análise", error)
                    deleteAnalysisResources()
                    eisActive = false
                    eisCorrection = eisController.reset()
                }
            }

            outputs.toList().forEach { (surfaceOutput, output) ->

                if (
                    !EGL14.eglMakeCurrent(
                        display,
                        output.eglSurface,
                        output.eglSurface,
                        context
                    )
                ) {
                    reportError(
                        IllegalStateException(
                            "Falha ao ativar EGL de saída (targets=${output.targets}): ${EGL14.eglGetError()}"
                        )
                    )
                    return@forEach
                }

                GLES20.glViewport(
                    0,
                    0,
                    output.width,
                    output.height
                )

                surfaceOutput.updateTransformMatrix(
                    outputMatrix,
                    textureMatrix
                )

                if (firstFrameOutputs.add(surfaceOutput)) {
                    val determinant =
                        outputMatrix[0] * outputMatrix[5] -
                        outputMatrix[4] * outputMatrix[1]

                    Log.i(
                        TAG,
                        "Primeiro frame: targets=${output.targets}, " +
                            "resolucao=${output.width}x${output.height}, " +
                            "det=$determinant, " +
                            "output=${outputMatrix.contentToString()}, " +
                            "texture=${textureMatrix.contentToString()}"
                    )
                }
                drawFrame(
                    matrix = outputMatrix,
                    textureId = input.textureId,
                    correction = if (eisActive) eisCorrection else null
                )

                EGLExt.eglPresentationTimeANDROID(
                    display,
                    output.eglSurface,
                    timestamp
                )

                if (!EGL14.eglSwapBuffers(display, output.eglSurface)) {
                    reportError(
                        IllegalStateException(
                            "Falha ao enviar frame EGL (targets=${output.targets}): ${EGL14.eglGetError()}"
                        )
                    )
                }
            }

        } catch (error: Throwable) {
            reportError(error)
        }
    }

    private fun initializeGl() {
        if (display != EGL14.EGL_NO_DISPLAY) return

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) {
                "Falha ao obter display EGL"
            }

            val versions = IntArray(2)
            eglInitialized = EGL14.eglInitialize(
                display, versions, 0, versions, 1
            )

            check(eglInitialized) {
                "Falha ao inicializar EGL"
            }

            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                0x3142, 1,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)

            check(
                EGL14.eglChooseConfig(
                    display, attributes, 0,
                    configs, 0, 1, count, 0
                ) && count[0] > 0
            ) {
                "Nenhuma configuração EGL compatível"
            }

            config = requireNotNull(configs[0])

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
                ),
                0
            )

            check(context != EGL14.EGL_NO_CONTEXT) {
                "Falha ao criar contexto OpenGL ES: ${EGL14.eglGetError()}"
            }

            pbuffer = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                ),
                0
            )

            check(pbuffer != EGL14.EGL_NO_SURFACE) {
                "Falha ao criar superfície EGL auxiliar: ${EGL14.eglGetError()}"
            }

            check(
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            ) {
                "Falha ao ativar contexto OpenGL: ${EGL14.eglGetError()}"
            }

            program = createProgram()

            if (eisActive) {
                try {
                    initializeAnalysisResources()
                } catch (error: Throwable) {
                    deleteAnalysisResources()
                    eisActive = false
                    eisCorrection = eisController.reset()
                    Log.w(TAG, "EIS desativado: falha ao preparar análise de quadros", error)
                }
            }
        } catch (error: Throwable) {
            cleanupEglAfterInitializationFailure()
            throw error
        }
    }

    private fun cleanupEglAfterInitializationFailure() {
        if (display != EGL14.EGL_NO_DISPLAY && eglInitialized) {
            var contextCurrent = false

            if (
                context != EGL14.EGL_NO_CONTEXT &&
                pbuffer != EGL14.EGL_NO_SURFACE
            ) {
                contextCurrent = EGL14.eglMakeCurrent(
                    display, pbuffer, pbuffer, context
                )

                if (contextCurrent) {
                    deleteAnalysisResources()
                    if (program != 0) GLES20.glDeleteProgram(program)
                }
            }

            program = 0

            positionLocation = -1

            texCoordLocation = -1

            transformLocation = -1

            samplerLocation = -1
            if (contextCurrent) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }

            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, pbuffer)
            }

            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }

            EGL14.eglTerminate(display)
        }

        eglInitialized = false
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
        program = 0
        positionLocation = -1
        texCoordLocation = -1
        transformLocation = -1
        samplerLocation = -1
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)

        GLES20.glGenTextures(1, ids, 0)

        val id = ids[0]

        check(id != 0) {
            "Falha ao criar textura OpenGL externa"
        }

        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            id
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        return id
    }

    private fun drawFrame(
        matrix: FloatArray,
        textureId: Int,
        correction: ZoomixEisController.Correction? = null
    ) {
        GLES20.glUseProgram(program)

        val position = positionLocation
        val texCoord = texCoordLocation
        val transform = transformLocation
        val sampler = samplerLocation

        check(position >= 0 && texCoord >= 0 && transform >= 0 && sampler >= 0) {
            "Shader inválido: localização de atributo ou uniforme não encontrada"
        }

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position, 2, GLES20.GL_FLOAT, false, 16, vertices
        )

        vertices.position(2)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(
            texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices
        )

        GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
        GLES20.glUniform2f(
            eisOffsetLocation,
            correction?.offsetX ?: 0f,
            correction?.offsetY ?: 0f
        )
        GLES20.glUniform1f(
            eisScaleLocation,
            correction?.cropScale ?: 1f
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            textureId
        )
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun initializeAnalysisResources() {
        check(eisActive) { "EIS não está habilitado" }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        analysisTexture = textures[0]
        check(analysisTexture != 0) { "Falha ao criar textura de análise" }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTexture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            ANALYSIS_WIDTH,
            ANALYSIS_HEIGHT,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        analysisFramebuffer = framebuffers[0]
        check(analysisFramebuffer != 0) { "Falha ao criar framebuffer de análise" }

        GLES20.glBindFramebuffer(
            GLES20.GL_FRAMEBUFFER,
            analysisFramebuffer
        )
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            analysisTexture,
            0
        )
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE
        ) { "Framebuffer de análise incompleto" }

        GLES30.glGenBuffers(2, analysisPbos, 0)
        check(analysisPbos.all { it != 0 }) { "Falha ao criar PBOs de análise" }

        analysisPbos.forEach { buffer ->
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffer)
            GLES30.glBufferData(
                GLES30.GL_PIXEL_PACK_BUFFER,
                ANALYSIS_BYTES,
                null,
                GLES30.GL_STREAM_READ
            )
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        checkGlError("inicialização dos recursos de análise")
        analysisPendingIndex = -1
        analysisWriteIndex = 0
        motionEstimator.reset()
        eisCorrection = eisController.reset()
        Log.i(TAG, "Análise EIS preparada: ${ANALYSIS_WIDTH}x${ANALYSIS_HEIGHT}, PBO duplo")
    }

    private fun analyzeFrame(textureId: Int) {
        if (analysisFramebuffer == 0 || analysisPbos.any { it == 0 }) {
            throw IllegalStateException("Recursos de análise indisponíveis")
        }

        try {
            if (analysisPendingIndex >= 0) {
                GLES30.glBindBuffer(
                    GLES30.GL_PIXEL_PACK_BUFFER,
                    analysisPbos[analysisPendingIndex]
                )

                val mapped = GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER,
                    0,
                    ANALYSIS_BYTES,
                    GLES30.GL_MAP_READ_BIT
                ) as? ByteBuffer ?: throw IllegalStateException("Falha ao mapear PBO")

                val rgba = ByteArray(ANALYSIS_BYTES)
                var unmapped = false

                try {
                    mapped.position(0)
                    mapped.get(rgba)
                } finally {
                    unmapped = GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                }

                check(unmapped) { "Dados do PBO foram invalidados" }

                val gray = ByteArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
                var source = 0

                for (i in gray.indices) {
                    val r = rgba[source].toInt() and 0xFF
                    val g = rgba[source + 1].toInt() and 0xFF
                    val b = rgba[source + 2].toInt() and 0xFF
                    gray[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
                    source += 4
                }

                val estimate = motionEstimator.estimate(gray)
                eisCorrection = eisController.update(
                    motionToTextureSpace(estimate)
                )
            }

            GLES20.glBindFramebuffer(
                GLES20.GL_FRAMEBUFFER,
                analysisFramebuffer
            )
            GLES20.glViewport(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            drawFrame(
                matrix = textureMatrix,
                textureId = textureId,
                correction = null
            )

            GLES30.glBindBuffer(
                GLES30.GL_PIXEL_PACK_BUFFER,
                analysisPbos[analysisWriteIndex]
            )
            GLES30.glReadPixels(
                0,
                0,
                ANALYSIS_WIDTH,
                ANALYSIS_HEIGHT,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                0
            )
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            analysisPendingIndex = analysisWriteIndex
            analysisWriteIndex = 1 - analysisWriteIndex
            checkGlError("análise do quadro")
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    private fun motionToTextureSpace(
        estimate: ZoomixMotionEstimator.MotionEstimate?
    ): ZoomixMotionEstimator.MotionEstimate? {
        if (estimate == null) return null

        // Parte linear da matriz 4x4, em ordem column-major.
        val a = textureMatrix[0]
        val b = textureMatrix[4]
        val c = textureMatrix[1]
        val d = textureMatrix[5]
        val determinant = a * d - b * c

        if (!determinant.isFinite() ||
            kotlin.math.abs(determinant) < 0.000001f
        ) {
            Log.w(TAG, "Matriz de textura não permite converter o movimento EIS")
            return null
        }

        // O estimador mede em pixels da imagem transformada.
        // A inversa da matriz converte o vetor para o espaço da textura.
        val dx = estimate.dx / ANALYSIS_WIDTH
        val dy = estimate.dy / ANALYSIS_HEIGHT

        val textureDx = ((d * dx - b * dy) / determinant) * ANALYSIS_WIDTH
        val textureDy = ((-c * dx + a * dy) / determinant) * ANALYSIS_HEIGHT

        return estimate.copy(
            dx = textureDx,
            dy = textureDy
        )
    }

    private fun deleteAnalysisResources() {
        if (analysisPbos.any { it != 0 }) {
            GLES30.glDeleteBuffers(2, analysisPbos, 0)
            analysisPbos.fill(0)
        }
        if (analysisFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(
                1,
                intArrayOf(analysisFramebuffer),
                0
            )
            analysisFramebuffer = 0
        }
        if (analysisTexture != 0) {
            GLES20.glDeleteTextures(
                1,
                intArrayOf(analysisTexture),
                0
            )
            analysisTexture = 0
        }
        analysisPendingIndex = -1
        analysisWriteIndex = 0
        motionEstimator.reset()
        eisCorrection = eisController.reset()
    }

    private fun checkGlError(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "Erro OpenGL em $stage: 0x${Integer.toHexString(error)}"
        }
    }

    private fun createProgram(): Int {
        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uEisOffset;
            uniform float uEisScale;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vec2 centered = (aTexCoord.xy - vec2(0.5)) / uEisScale;
                vec2 corrected = centered + vec2(0.5) - uEisOffset;
                vTexCoord = (uTexMatrix * vec4(corrected, 0.0, 1.0)).xy;
            }
            """.trimIndent()
        )

        val fragmentShader = try {
            compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                varying vec2 vTexCoord;

                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            )
        } catch (error: Throwable) {
            GLES20.glDeleteShader(vertexShader)
            throw error
        }

        val id = GLES20.glCreateProgram()

        try {
            check(id != 0) { "Falha ao criar programa OpenGL" }
            GLES20.glAttachShader(id, vertexShader)
            GLES20.glAttachShader(id, fragmentShader)
            GLES20.glLinkProgram(id)

            val status = IntArray(1)

            GLES20.glGetProgramiv(
                id,
                GLES20.GL_LINK_STATUS,
                status,
                0
            )

            check(status[0] == GLES20.GL_TRUE) {
                "Falha ao vincular shaders: ${GLES20.glGetProgramInfoLog(id)}"
            }

            positionLocation = GLES20.glGetAttribLocation(id, "aPosition")
            texCoordLocation = GLES20.glGetAttribLocation(id, "aTexCoord")
            transformLocation = GLES20.glGetUniformLocation(id, "uTexMatrix")
            samplerLocation = GLES20.glGetUniformLocation(id, "uTexture")
            eisOffsetLocation = GLES20.glGetUniformLocation(id, "uEisOffset")
            eisScaleLocation = GLES20.glGetUniformLocation(id, "uEisScale")

            check(
                positionLocation >= 0 &&
                texCoordLocation >= 0 &&
                transformLocation >= 0 &&
                samplerLocation >= 0 &&
                eisOffsetLocation >= 0 &&
                eisScaleLocation >= 0
            ) {
                "Shader inválido: localização de atributo ou uniforme não encontrada"
            }

            return id

        } catch (error: Throwable) {
            GLES20.glDeleteProgram(id)
            throw error

        } finally {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader = GLES20.glCreateShader(type)

        check(shader != 0) {
            "Falha ao criar shader OpenGL"
        }

        try {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val status = IntArray(1)

            GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                status,
                0
            )

            check(status[0] == GLES20.GL_TRUE) {
                "Falha ao compilar shader: ${GLES20.glGetShaderInfoLog(shader)}"
            }

            return shader
        } catch (error: Throwable) {
            GLES20.glDeleteShader(shader)
            throw error
        }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun reportError(error: Throwable) {
        Log.e(TAG, "Falha no pipeline OpenGL", error)
        try {
            onError(error)
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        if (!releaseRequested.compareAndSet(false, true)) return

        executor.execute {
            released = true
            checkReadyToRelease()
        }
    }

    private fun checkReadyToRelease() {
        if (!released || inputs.isNotEmpty() || outputs.isNotEmpty()) return

        if (display != EGL14.EGL_NO_DISPLAY && eglInitialized) {
            var contextCurrent = false

            if (
                context != EGL14.EGL_NO_CONTEXT &&
                pbuffer != EGL14.EGL_NO_SURFACE
            ) {
                contextCurrent = EGL14.eglMakeCurrent(
                    display, pbuffer, pbuffer, context
                )

                if (contextCurrent) {
                    deleteAnalysisResources()
                    if (program != 0) GLES20.glDeleteProgram(program)
                } else {
                    reportError(
                        IllegalStateException(
                            "Não foi possível ativar EGL durante a liberação."
                        )
                    )
                }
            }

            program = 0

            positionLocation = -1

            texCoordLocation = -1

            transformLocation = -1

            samplerLocation = -1
            if (contextCurrent) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }

            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, pbuffer)
                pbuffer = EGL14.EGL_NO_SURFACE
            }

            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }

            EGL14.eglTerminate(display)
        }

        eglInitialized = false
        display = EGL14.EGL_NO_DISPLAY
        config = null
        activeInput = null
        thread.quitSafely()
    }


}
