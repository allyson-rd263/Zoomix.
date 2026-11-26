package com.zoomix.camera

import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer

/**
 * Conecta o processador gráfico próprio ao pipeline CameraX.
 * Usa obrigatoriamente o executor da thread EGL do processador.
 */
class ZoomixCameraEffect(
    targets: Int,
    processor: ZoomixSurfaceProcessor,
    onError: (Throwable) -> Unit
) : CameraEffect(
    targets,
    processor.executor,
    processor,
    Consumer { error -> onError(error) }
)
