package com.zoomix.camera

import android.app.Activity
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import java.nio.ByteBuffer

/**
 * Diagnóstico isolado de EGL ES 3.0 e leitura de pixels por Pixel Buffer Object.
 *
 * Não inicia CameraX e não altera o ZoomixSurfaceProcessor.
 * Pode ser aberto explicitamente por ADB:
 * adb shell am start -n com.zoomix.camera/.EglPboDiagnosticActivity
 */
class EglPboDiagnosticActivity : Activity() {

    companion object {
        private const val TAG = "ZoomixEglDiag"
        // EGL_OPENGL_ES3_BIT_KHR (0x0040). Mantido como literal para evitar
        // depender da disponibilidade da constante no SDK de compilação.
        private const val EGL_OPENGL_ES3_BIT_KHR_VALUE = 0x0040
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val resultView = TextView(this).apply {
            text = "Executando diagnóstico EGL ES 3.0 + PBO..."
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(18, 18, 18))
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }
        setContentView(resultView)

        Thread({
            val result = runDiagnostic()
            Log.i(TAG, result)
            runOnUiThread { resultView.text = result }
        }, "Zoomix-EGL-Diagnostic").start()
    }

    private fun runDiagnostic(): String {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var textureId = 0
        var framebufferId = 0
        var pboId = 0
        var current = false
        val report = StringBuilder()

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) {
                "eglGetDisplay falhou: ${eglError()}"
            }

            val eglVersions = IntArray(2)
            check(EGL14.eglInitialize(display, eglVersions, 0, eglVersions, 1)) {
                "eglInitialize falhou: ${eglError()}"
            }
            report.append("EGL inicializado: ${eglVersions[0]}.${eglVersions[1]}\n")

            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR_VALUE,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display, configAttributes, 0,
                    configs, 0, configs.size, configCount, 0
                ) && configCount[0] > 0
            ) {
                "Nenhuma configuração EGL compatível com ES3/PBUFFER: ${eglError()}"
            }
            val config = requireNotNull(configs[0])

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) {
                "eglCreateContext ES3 falhou: ${eglError()}"
            }

            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(
                    EGL14.EGL_WIDTH, 16,
                    EGL14.EGL_HEIGHT, 16,
                    EGL14.EGL_NONE
                ),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreatePbufferSurface falhou: ${eglError()}"
            }

            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent falhou: ${eglError()}"
            }
            current = true

            val glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "desconhecida"
            val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "desconhecido"
            val major = IntArray(1)
            val minor = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
            checkGl("consulta da versão OpenGL")
            check(major[0] >= 3) {
                "O contexto criado não é ES3: ${major[0]}.${minor[0]} ($glVersion)"
            }
            report.append("OpenGL: $glVersion\nGPU: $renderer\n")
            report.append("Contexto ES: ${major[0]}.${minor[0]}\n")

            // Cria uma textura RGBA8 e um framebuffer de teste.
            val texture = IntArray(1)
            GLES30.glGenTextures(1, texture, 0)
            textureId = texture[0]
            check(textureId != 0) { "Não foi possível criar textura de teste." }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST
            )
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                4, 4, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            checkGl("criação da textura RGBA8")

            val framebuffer = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffer, 0)
            framebufferId = framebuffer[0]
            check(framebufferId != 0) { "Não foi possível criar framebuffer." }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                textureId,
                0
            )
            val framebufferStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(framebufferStatus == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer incompleto: 0x${framebufferStatus.toString(16)}"
            }
            GLES30.glViewport(0, 0, 4, 4)
            GLES30.glClearColor(0.25f, 0.50f, 0.75f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            checkGl("renderização do padrão de teste")

            // PBO é recurso do OpenGL ES 3.0. A leitura usa offset zero,
            // não um ByteBuffer de CPU, e depois mapeia o buffer para validar os bytes.
            val buffer = IntArray(1)
            GLES30.glGenBuffers(1, buffer, 0)
            pboId = buffer[0]
            check(pboId != 0) { "glGenBuffers não criou o PBO." }

            val byteCount = 4 * 4 * 4
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboId)
            GLES30.glBufferData(
                GLES30.GL_PIXEL_PACK_BUFFER,
                byteCount,
                null,
                GLES30.GL_STREAM_READ
            )
            checkGl("alocação do PBO")

            GLES30.glReadPixels(
                0, 0, 4, 4,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                0
            )
            checkGl("glReadPixels para PBO")
            GLES30.glFinish()

            val mapped = GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER,
                0,
                byteCount,
                GLES30.GL_MAP_READ_BIT
            ) as? ByteBuffer
            check(mapped != null) { "glMapBufferRange não conseguiu mapear o PBO." }

            val red = mapped.get(0).toInt() and 0xFF
            val green = mapped.get(1).toInt() and 0xFF
            val blue = mapped.get(2).toInt() and 0xFF
            val alpha = mapped.get(3).toInt() and 0xFF
            val unmapped = GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            check(unmapped) { "glUnmapBuffer informou corrupção do conteúdo." }
            checkGl("mapeamento/leitura do PBO")

            check(
                red in 60..68 && green in 124..132 &&
                    blue in 187..195 && alpha >= 250
            ) {
                "Pixels recuperados não correspondem ao padrão esperado: " +
                    "RGBA=($red,$green,$blue,$alpha)"
            }

            report.append("PBO: criado e alocado (${byteCount} bytes)\n")
            report.append("glReadPixels via PBO: OK\n")
            report.append("Mapeamento e validação: OK\n")
            report.append("Pixel RGBA recuperado: $red,$green,$blue,$alpha\n")
            report.append("\nRESULTADO: APROVADO")
        } catch (error: Throwable) {
            report.append("\nRESULTADO: FALHOU\n")
            report.append(error.message ?: error.javaClass.simpleName)
            Log.e(TAG, "Falha no diagnóstico EGL/PBO", error)
        } finally {
            if (current) {
                try {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                    if (pboId != 0) GLES30.glDeleteBuffers(1, intArrayOf(pboId), 0)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    if (framebufferId != 0) {
                        GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
                    }
                    if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                } catch (error: Throwable) {
                    Log.w(TAG, "Falha ao liberar recursos GL do diagnóstico", error)
                }
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                EGL14.eglTerminate(display)
            }
        }

        return report.toString()
    }

    private fun checkGl(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "$stage: erro OpenGL 0x${error.toString(16)}"
        }
    }

    private fun eglError(): String =
        "0x${EGL14.eglGetError().toString(16)}"
}
