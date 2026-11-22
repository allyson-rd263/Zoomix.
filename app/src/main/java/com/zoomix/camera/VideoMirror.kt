package com.zoomix.camera

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@OptIn(UnstableApi::class)
object VideoMirror {

    private var activeTransformer: Transformer? = null

    fun transform(
        context: Context,
        input: File,
        output: File,
        onResult: (Boolean, String?) -> Unit
    ) {
        try {
            val effect = ScaleAndRotateTransformation.Builder()
                .setScale(-1f, 1f)
                .build()

            val item = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(input))
            ).setEffects(
                Effects(emptyList(), listOf(effect))
            ).build()

            val transformer = Transformer.Builder(context.applicationContext)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        activeTransformer = null
                        onResult(true, null)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        activeTransformer = null
                        onResult(false, exportException.message)
                    }
                })
                .build()

            activeTransformer = transformer
            transformer.start(item, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null
            onResult(false, e.message)
        }
    }
}
