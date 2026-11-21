package com.zoomix.camera

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** LEVE: painel translúcido simples (celular fraco). COMPLETO: gradiente e brilho de borda. */
enum class GlassTier { LEVE, COMPLETO }

object GlassConfig {
    fun resolve(ctx: Context, mode: String): GlassTier = when (mode) {
        "leve" -> GlassTier.LEVE
        "completo" -> GlassTier.COMPLETO
        else -> if (isWeakDevice(ctx)) GlassTier.LEVE else GlassTier.COMPLETO
    }

    fun isStrong(ctx: Context): Boolean = !isWeakDevice(ctx)

    private fun isWeakDevice(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isLowRamDevice || Build.VERSION.SDK_INT < 31 || am.memoryClass < 192
    }
}

@Composable
fun GlassPanel(
    tier: GlassTier,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val fill = if (tier == GlassTier.LEVE) {
        Brush.verticalGradient(listOf(Color(0xCC111827), Color(0xCC111827)))
    } else {
        Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF)))
    }
    val border = if (tier == GlassTier.LEVE) {
        Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF)))
    } else {
        Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x11FFFFFF), Color(0x55FFFFFF)))
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape),
        content = content
    )
}
