package com.zoomix.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HDR próprio do Zoomix: funde 3 fotos (mais escura, normal e mais clara) em uma só.
 *
 * - Alinha as fotos (pequenos tremores da mão) por deslocamento, com o método MTB.
 * - Calcula pesos por pixel (bem exposto, cor saturada, opcionalmente contraste) e suaviza os pesos
 *   para não criar bordas nem halos.
 * - Mistura em resolução total, em faixas de linhas, para gastar pouca memória.
 * - Mantém as CORES da foto normal: só o brilho vem da fusão. Isso evita o visual cinza ou
 *   exageradamente saturado de HDR.
 * - "intensity" mistura a foto normal com o resultado (1 = HDR completo).
 * O resultado é gravado na mesma orientação da foto base (a orientação vai no EXIF).
 */
object Hdr {
    class Params(val intensity: Float, val satExp: Float, val contrastExp: Float)

    val NATURAL = Params(intensity = 0.7f, satExp = 0.5f, contrastExp = 0f)
    val FORTE = Params(intensity = 1f, satExp = 1f, contrastExp = 1f)

    private class Low(val w: Int, val h: Int, val argb: IntArray)

    fun fuse(under: File, base: File, over: File, out: File, p: Params) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(base.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("Foto base inválida")
        for (f in listOf(under, over)) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth != width || o.outHeight != height) throw IOException("Fotos com tamanhos diferentes")
        }

        var sample = 1
        while (max(width, height) / (sample * 2) >= 600) sample *= 2

        val lowU = decodeLow(under, sample)
        val lowB = decodeLow(base, sample)
        val lowO = decodeLow(over, sample)
        val lw = lowB.w
        val lh = lowB.h
        if (lowU.w != lw || lowU.h != lh || lowO.w != lw || lowO.h != lh) throw IOException("Tamanhos incompatíveis")

        val yU = luma(lowU)
        val yB = luma(lowB)
        val yO = luma(lowO)

        val sU = align(yB, yU, lw, lh)
        val sO = align(yB, yO, lw, lh)

        val radius = max(2, lw / 60)
        val rawU = smooth(rawWeights(lowU, yU, sU[0], sU[1], lw, lh, p), lw, lh, radius)
        val rawB = smooth(rawWeights(lowB, yB, 0, 0, lw, lh, p), lw, lh, radius)
        val rawO = smooth(rawWeights(lowO, yO, sO[0], sO[1], lw, lh, p), lw, lh, radius)

        // Normaliza os pesos (somam 1 em cada pixel).
        val n = lw * lh
        val nU = FloatArray(n)
        val nB = FloatArray(n)
        val nO = FloatArray(n)
        for (i in 0 until n) {
            val s = rawU[i] + rawB[i] + rawO[i]
            if (s > 1e-6f) {
                nU[i] = rawU[i] / s
                nB[i] = rawB[i] / s
                nO[i] = rawO[i] / s
            } else {
                nB[i] = 1f
            }
        }

        // Mapas de interpolação (resolução total -> baixa resolução).
        val x0 = IntArray(width)
        val x1 = IntArray(width)
        val tx = FloatArray(width)
        for (x in 0 until width) {
            val xf = (x + 0.5f) / sample - 0.5f
            val xi = floor(xf).toInt()
            x0[x] = xi.coerceIn(0, lw - 1)
            x1[x] = (xi + 1).coerceIn(0, lw - 1)
            tx[x] = (xf - xi).coerceIn(0f, 1f)
        }

        val dxU = sU[0] * sample
        val dyU = sU[1] * sample
        val dxO = sO[0] * sample
        val dyO = sO[1] * sample

        var decU: BitmapRegionDecoder? = null
        var decB: BitmapRegionDecoder? = null
        var decO: BitmapRegionDecoder? = null
        var outBmp: Bitmap? = null

        try {
            val uDecoder = BitmapRegionDecoder.newInstance(under.absolutePath, false)
                ?: throw IOException("Não foi possível abrir a foto escura")
            decU = uDecoder

            val bDecoder = BitmapRegionDecoder.newInstance(base.absolutePath, false)
                ?: throw IOException("Não foi possível abrir a foto base")
            decB = bDecoder

            val oDecoder = BitmapRegionDecoder.newInstance(over.absolutePath, false)
                ?: throw IOException("Não foi possível abrir a foto clara")
            decO = oDecoder

            val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outBmp = bitmapOut
            val chunk = 128
            val rowBuf = IntArray(width * chunk)
            var y0 = 0
            while (y0 < height) {
                val y1c = min(height, y0 + chunk)
                val rows = y1c - y0

                val bArr = readRegion(bDecoder, width, y0, y1c) ?: throw IOException("Falha ao ler a foto base")
                val uTop = max(0, y0 + dyU)
                val uBot = min(height, y1c + dyU)
                val uArr = readRegion(uDecoder, width, uTop, uBot)
                val oTop = max(0, y0 + dyO)
                val oBot = min(height, y1c + dyO)
                val oArr = readRegion(oDecoder, width, oTop, oBot)

                for (r in 0 until rows) {
                    val y = y0 + r
                    val yf = (y + 0.5f) / sample - 0.5f
                    val yi = floor(yf).toInt()
                    val ya = yi.coerceIn(0, lh - 1)
                    val yb = (yi + 1).coerceIn(0, lh - 1)
                    val ty = (yf - yi).coerceIn(0f, 1f)

                    val syU = y + dyU
                    val uValid = uArr != null && syU >= uTop && syU < uBot
                    val syO = y + dyO
                    val oValid = oArr != null && syO >= oTop && syO < oBot

                    for (x in 0 until width) {
                        val bp = bArr[r * width + x]
                        val br = ((bp shr 16) and 0xFF).toFloat()
                        val bg = ((bp shr 8) and 0xFF).toFloat()
                        val bb = (bp and 0xFF).toFloat()

                        val xa = x0[x]
                        val xb = x1[x]
                        val tfx = tx[x]

                        val wb = bil(nB, lw, ya, yb, ty, xa, xb, tfx)
                        var wu = 0f
                        var ur = 0f
                        var ug = 0f
                        var ub = 0f
                        if (uValid) {
                            val sx = x + dxU
                            if (sx in 0 until width) {
                                val up = uArr!![(syU - uTop) * width + sx]
                                ur = ((up shr 16) and 0xFF).toFloat()
                                ug = ((up shr 8) and 0xFF).toFloat()
                                ub = (up and 0xFF).toFloat()
                                wu = bil(nU, lw, ya, yb, ty, xa, xb, tfx)
                            }
                        }
                        var wo = 0f
                        var oR = 0f
                        var oG = 0f
                        var oB = 0f
                        if (oValid) {
                            val sx = x + dxO
                            if (sx in 0 until width) {
                                val op = oArr!![(syO - oTop) * width + sx]
                                oR = ((op shr 16) and 0xFF).toFloat()
                                oG = ((op shr 8) and 0xFF).toFloat()
                                oB = (op and 0xFF).toFloat()
                                wo = bil(nO, lw, ya, yb, ty, xa, xb, tfx)
                            }
                        }

                        val tot = wb + wu + wo
                        val fr: Float
                        val fg: Float
                        val fb: Float
                        if (tot < 1e-5f) {
                            fr = br; fg = bg; fb = bb
                        } else {
                            fr = (wb * br + wu * ur + wo * oR) / tot
                            fg = (wb * bg + wu * ug + wo * oG) / tot
                            fb = (wb * bb + wu * ub + wo * oB) / tot
                        }

                        // Só o brilho vem da fusão; as cores vêm da foto normal.
                        val yBase = (0.299f * br + 0.587f * bg + 0.114f * bb) / 255f
                        val yFused = (0.299f * fr + 0.587f * fg + 0.114f * fb) / 255f
                        val ratio = ((yFused + 0.02f) / (yBase + 0.02f)).coerceIn(0.25f, 6f)
                        val t = smoothstep(0.03f, 0.12f, yBase) * (1f - smoothstep(0.88f, 0.97f, yBase))
                        val cr = t * (br * ratio).coerceAtMost(255f) + (1f - t) * fr
                        val cg = t * (bg * ratio).coerceAtMost(255f) + (1f - t) * fg
                        val cb = t * (bb * ratio).coerceAtMost(255f) + (1f - t) * fb

                        val k = p.intensity
                        val rr = (br + k * (cr - br)).coerceIn(0f, 255f).roundToInt()
                        val gg = (bg + k * (cg - bg)).coerceIn(0f, 255f).roundToInt()
                        val bl = (bb + k * (cb - bb)).coerceIn(0f, 255f).roundToInt()
                        rowBuf[r * width + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bl
                    }
                }
                bitmapOut.setPixels(rowBuf, 0, width, 0, y0, width, rows)
                y0 = y1c
            }
            val compressed = out.outputStream().use {
                bitmapOut.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }

            if (!compressed || !out.isFile || out.length() <= 0L) {
                throw IOException("Falha ao salvar a imagem HDR")
            }
        } finally {
            outBmp?.recycle()
            decO?.recycle()
            decB?.recycle()
            decU?.recycle()
        }
        ImageUtils.copyExif(base, out)
    }

    // ---------- auxiliares ----------

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun bil(a: FloatArray, lw: Int, ya: Int, yb: Int, ty: Float, xa: Int, xb: Int, tx: Float): Float {
        val top = a[ya * lw + xa] * (1f - tx) + a[ya * lw + xb] * tx
        val bot = a[yb * lw + xa] * (1f - tx) + a[yb * lw + xb] * tx
        return top * (1f - ty) + bot * ty
    }

    private fun readRegion(dec: BitmapRegionDecoder, width: Int, top: Int, bottom: Int): IntArray? {
        if (bottom <= top) return null

        val bm = dec.decodeRegion(Rect(0, top, width, bottom), null) ?: return null

        try {
            val rows = bm.height
            val arr = IntArray(width * rows)
            bm.getPixels(arr, 0, width, 0, 0, width, rows)
            return arr
        } finally {
            bm.recycle()
        }
    }

    private fun decodeLow(f: File, sample: Int): Low {
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val bm = BitmapFactory.decodeFile(f.absolutePath, o)
            ?: throw IOException("Foto inválida")

        try {
            val arr = IntArray(bm.width * bm.height)
            bm.getPixels(arr, 0, bm.width, 0, 0, bm.width, bm.height)
            return Low(bm.width, bm.height, arr)
        } finally {
            bm.recycle()
        }
    }

    private fun luma(l: Low): FloatArray {
        val y = FloatArray(l.w * l.h)
        for (i in y.indices) {
            val p = l.argb[i]
            y[i] = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        return y
    }

    /** Peso de cada pixel na baixa resolução, nas coordenadas da foto base (0 onde a foto sai do quadro). */
    private fun rawWeights(l: Low, y: FloatArray, sx: Int, sy: Int, lw: Int, lh: Int, p: Params): FloatArray {
        val w = FloatArray(lw * lh)
        for (yy in 0 until lh) {
            val ty = yy + sy
            if (ty < 0 || ty >= lh) continue
            for (xx in 0 until lw) {
                val tx = xx + sx
                if (tx < 0 || tx >= lw) continue
                val i = ty * lw + tx
                val lum = y[i]
                val px = l.argb[i]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                val m = (r + g + b) / 3f
                val sat = sqrt(((r - m) * (r - m) + (g - m) * (g - m) + (b - m) * (b - m)) / 3f)
                val d = lum - 0.5f
                var wt = exp(-(d * d) / 0.08f) * (sat + 0.03f).pow(p.satExp)
                if (p.contrastExp > 0f && tx in 1 until lw - 1 && ty in 1 until lh - 1) {
                    val lap = abs(4f * lum - y[i - 1] - y[i + 1] - y[i - lw] - y[i + lw])
                    wt *= (lap + 0.02f).pow(p.contrastExp)
                }
                w[yy * lw + xx] = wt + 1e-4f
            }
        }
        return w
    }

    private fun smooth(a: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        boxBlur(boxBlur(a, w, h, r), w, h, r)

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var lo = 0
            var hi = -1
            for (x in 0 until w) {
                val nlo = max(0, x - r)
                val nhi = min(w - 1, x + r)
                while (hi < nhi) { hi++; sum += src[row + hi] }
                while (lo < nlo) { sum -= src[row + lo]; lo++ }
                tmp[row + x] = sum / (hi - lo + 1)
            }
        }
        val dst = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f
            var lo = 0
            var hi = -1
            for (y in 0 until h) {
                val nlo = max(0, y - r)
                val nhi = min(h - 1, y + r)
                while (hi < nhi) { hi++; sum += tmp[hi * w + x] }
                while (lo < nlo) { sum -= tmp[lo * w + x]; lo++ }
                dst[y * w + x] = sum / (hi - lo + 1)
            }
        }
        return dst
    }

    // ---------- alinhamento (MTB) ----------

    private fun down4(a: FloatArray, w: Int, h: Int): FloatArray {
        val cw = w / 4
        val ch = h / 4
        val out = FloatArray(cw * ch)
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                var s = 0f
                for (dy in 0 until 4) for (dx in 0 until 4) s += a[(cy * 4 + dy) * w + (cx * 4 + dx)]
                out[cy * cw + cx] = s / 16f
            }
        }
        return out
    }

    private class Mtb(val bits: ByteArray, val mask: ByteArray)

    private fun mtb(lum: FloatArray): Mtb {
        val hist = IntArray(256)
        for (v in lum) hist[(v * 255f).toInt().coerceIn(0, 255)]++
        var acc = 0
        var med = 0
        val half = lum.size / 2
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= half) { med = i; break }
        }
        val t = med / 255f
        val bits = ByteArray(lum.size)
        val mask = ByteArray(lum.size)
        for (i in lum.indices) {
            bits[i] = if (lum[i] > t) 1 else 0
            mask[i] = if (abs(lum[i] - t) > 0.03f) 1 else 0
        }
        return Mtb(bits, mask)
    }

    /** Procura o deslocamento (dx, dy) tal que cur(x+dx, y+dy) combine com ref(x, y). Devolve [dx, dy, erro]. */
    private fun bestShift(ref: Mtb, cur: Mtb, w: Int, h: Int, range: Int, cx: Int, cy: Int): FloatArray {
        var best = Float.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - range..cy + range) {
            for (dx in cx - range..cx + range) {
                val ys = max(0, -dy)
                val ye = min(h, h - dy)
                val xs = max(0, -dx)
                val xe = min(w, w - dx)
                if (ye <= ys || xe <= xs) continue
                var err = 0
                var cnt = 0
                for (y in ys until ye) {
                    val ro = y * w
                    val co = (y + dy) * w + dx
                    for (x in xs until xe) {
                        val i = ro + x
                        val j = co + x
                        if (ref.mask[i].toInt() == 1 && cur.mask[j].toInt() == 1) {
                            cnt++
                            if (ref.bits[i] != cur.bits[j]) err++
                        }
                    }
                }
                if (cnt < (w * h) / 4) continue
                val score = err.toFloat() / cnt
                if (score < best) { best = score; bx = dx; by = dy }
            }
        }
        return floatArrayOf(bx.toFloat(), by.toFloat(), best)
    }

    /** Deslocamento em pixels da baixa resolução; (0,0) se o alinhamento não for confiável. */
    private fun align(ref: FloatArray, cur: FloatArray, w: Int, h: Int): IntArray {
        return try {
            val cw = w / 4
            val ch = h / 4
            if (cw < 16 || ch < 16) return intArrayOf(0, 0)
            val coarse = bestShift(mtb(down4(ref, w, h)), mtb(down4(cur, w, h)), cw, ch, 8, 0, 0)
            val fine = bestShift(mtb(ref), mtb(cur), w, h, 4, (coarse[0] * 4).toInt(), (coarse[1] * 4).toInt())
            if (fine[2] > 0.3f) intArrayOf(0, 0) else intArrayOf(fine[0].toInt(), fine[1].toInt())
        } catch (e: Exception) {
            intArrayOf(0, 0)
        }
    }
}
