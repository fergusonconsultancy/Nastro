package com.fergusonconsultancy.nastro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Colour with alpha a (0..1) applied over its own. */
fun Int.a(a: Float): Int {
    val base = (this ushr 24) / 255f
    return (((base * a).coerceIn(0f, 1f) * 255).toInt() shl 24) or (this and 0xFFFFFF)
}

/** Geometry of the player, sized to its contents. Tall decks put the keys in a row under the window. */
class DeckGeom(x: Float, y: Float, w: Float, h: Float, density: Float) {
    class Key(val key: Deck.Key, val x: Float, val y: Float, val r: Float)
    class Legend(val id: String, val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

    val tall = h / w > 1.9f
    val x = x
    val w = w
    val y: Float
    val h: Float
    val lcd: RectF
    val win: RectF
    val keys: List<Key>
    val leg: RectF
    val legends: List<Legend>
    val narrow: Boolean
    val k: Float
    val radius = w * 0.07f
    val ccx: Float
    val ccy: Float

    init {
        val pad = w * 0.07f
        val gap = w * 0.04f
        val lcdH = max(58f * density, w * 0.19f)
        val kw = if (tall) 0f else w * 0.2f
        val winW = w - pad * 2 - if (tall) 0f else kw + gap
        val m = 8f * density
        var kk = (winW - m * 2) / (CH + 6)
        val keysH = if (tall) max(70f * density, w * 0.24f) else 0f
        val legendH = max(66f * density, w * 0.17f)
        var winH = CW * kk + m * 2
        val fixed = pad * 0.7f + lcdH + gap + gap + keysH + (if (tall) gap else 0f) + legendH
        if (fixed + winH > h) { winH = h - fixed; kk = (winH - m * 2) / CW }
        val bodyH = fixed + winH
        this.y = y + (h - bodyH) / 2
        this.h = bodyH
        k = kk
        lcd = RectF(x + pad, this.y + pad * 0.7f, x + w - pad, this.y + pad * 0.7f + lcdH)
        win = RectF(x + pad, lcd.bottom + gap, x + pad + winW, lcd.bottom + gap + winH)
        keys = if (tall) {
            val r = min(keysH * 0.36f, (w - pad * 2) / 9f)
            val ky = win.bottom + gap + keysH / 2
            listOf(Deck.Key.REW, Deck.Key.PLAY, Deck.Key.STOP, Deck.Key.FF).mapIndexed { i, kk2 ->
                Key(kk2, x + pad + (w - pad * 2) * (0.125f + i * 0.25f), ky, r)
            }
        } else {
            val kcx = win.right + gap + kw / 2
            val r = min(kw * 0.36f, winH / 11f)
            listOf(Deck.Key.PLAY, Deck.Key.STOP, Deck.Key.FF, Deck.Key.REW).mapIndexed { i, kk2 ->
                Key(kk2, kcx, win.top + winH * 0.14f + i * (winH * 0.72f / 3), r)
            }
        }
        leg = RectF(x + pad, this.y + bodyH - legendH, x + w - pad, this.y + bodyH)
        narrow = leg.width() < 300 * density
        val items = listOf("autoRev" to "AUTO REVERSE", "megaBass" to "MEGA BASS", "hold" to "HOLD")
        legends = items.mapIndexed { i, (id, t) ->
            if (narrow) Legend(id, t, leg.left + leg.width() * 0.52f, leg.top + leg.height() * (0.3f + i * 0.27f), leg.width() * 0.48f, leg.height() * 0.27f)
            else Legend(id, t, leg.left + leg.width() * (i / 3f), leg.top + leg.height() * 0.66f, leg.width() / 3f, leg.height() * 0.5f)
        }
        ccx = win.centerX(); ccy = win.centerY()
    }

    companion object {
        const val CW = 100.4f
        const val CH = 63.8f
    }
}

/**
 * Draws everything: cassettes in their own millimetres, the player around them, the shelf.
 * A straight port of the preview, so what Mechack saw there is what the phone draws.
 */
class Painter(ctx: Context) {
    private val dens = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dens
    private val hand: Typeface = runCatching {
        Typeface.Builder(ctx.assets, "fonts/Caveat.ttf").setFontVariationSettings("'wght' 600").build()
    }.getOrNull() ?: Typeface.create("casual", Typeface.NORMAL)
    private val printM: Typeface = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/BarlowCondensed-Medium.ttf") }.getOrDefault(Typeface.SANS_SERIF)
    private val printS: Typeface = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/BarlowCondensed-SemiBold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
    val ui: Typeface = runCatching {
        Typeface.Builder(ctx.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' 450").build()
    }.getOrNull() ?: Typeface.SANS_SERIF

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val r = RectF()

    private val CW = DeckGeom.CW
    private val CH = DeckGeom.CH
    private val HUB = Deck.HUB_R
    private val PACK = Deck.PACK_R
    private val RDX = 21.25f
    private val RY = -2f
    private val INK = 0xFF1F2C6B.toInt()     // a blue biro

    private fun fill(c: Int) { p.shader = null; p.color = c; p.clearShadowLayer() }

    // ---- shell outlines, built once per window shape --------------------------------------------
    private val seeCache = HashMap<String, Path>()
    private val bodyCache = HashMap<String, Path>()
    private val outline = Path().apply { addRoundRect(RectF(-CW / 2, -CH / 2, CW / 2, CH / 2), 3.2f, 3.2f, Path.Direction.CW) }

    private fun see(sh: Shell): Path = seeCache.getOrPut(sh.win + sh.clear) {
        Path().apply {
            if (sh.clear > 0f) { addRect(-CW / 2, -CH / 2, CW / 2, CH / 2, Path.Direction.CW); return@apply }
            val w = Path()
            when (sh.win) {
                "wide" -> w.addRoundRect(RectF(-28f, -9f, 28f, 8f), 2.5f, 2.5f, Path.Direction.CW)
                "oval" -> w.addOval(RectF(-37f, -17.5f, 37f, 13.5f), Path.Direction.CW)
                else -> w.addRoundRect(RectF(-13f, -7.5f, 13f, 4.5f), 2f, 2f, Path.Direction.CW)
            }
            val holes = Path().apply { addCircle(-RDX, RY, 8.5f, Path.Direction.CW); addCircle(RDX, RY, 8.5f, Path.Direction.CW) }
            w.op(holes, Path.Op.UNION)
            set(w)
        }
    }

    private fun body(sh: Shell): Path = bodyCache.getOrPut(sh.win + sh.clear) {
        Path(outline).apply { op(see(sh), Path.Op.DIFFERENCE) }
    }

    // ---- text ---------------------------------------------------------------------------------
    private fun print(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, semi: Boolean = true,
                      align: Paint.Align = Paint.Align.LEFT, trackMm: Float = 0f) {
        t.typeface = if (semi) printS else printM
        t.textSize = size; t.color = color; t.textAlign = align
        t.letterSpacing = if (size > 0) trackMm / size else 0f
        t.shader = null; t.clearShadowLayer()
        c.drawText(text, x, y, t)
        t.letterSpacing = 0f
    }

    private fun handwrite(c: Canvas, text: String, x: Float, y: Float, size: Float, maxW: Float, color: Int,
                          align: Paint.Align = Paint.Align.LEFT) {
        t.typeface = hand; t.textSize = size; t.color = color; t.textAlign = align; t.letterSpacing = 0f
        t.shader = null; t.clearShadowLayer()
        val w = t.measureText(text)
        val sc = if (w > maxW) maxW / w else 1f
        c.save(); c.translate(x, y); c.rotate(-0.7f); c.scale(sc, 1f); c.drawText(text, 0f, 0f, t); c.restore()
    }

    // ---- cassette --------------------------------------------------------------------------------
    /** One cassette, centred, label edge up, in millimetres. [live] uses the deck's reels and packs. */
    fun cassette(c: Canvas, tape: Tape, live: Boolean, detail: Float, facing: Char = 'A', spindle: Boolean = false, shadow: Float = 0f) {
        val sh = tape.shell
        val (rl, rr) = if (live) Deck.packs() else Pair(packR(0.62), packR(0.38))
        val aL = if (live) Deck.angL else 0f
        val aR = if (live) Deck.angR else 0f

        // 1 shadow and back
        fill(if (sh.clear > 0f) 0x40000000 else sh.body)
        p.setShadowLayer(if (shadow > 0) shadow else 6f, if (shadow > 0) 1.2f else 0f, if (shadow > 0) 2.2f else 2.2f, 0x73000000)
        c.drawPath(outline, p); p.clearShadowLayer()

        // 2 what shows through: packs and the tape path
        val seeP = see(sh)
        c.save(); c.clipPath(seeP)
        fill(if (sh.clear > 0f) 0xFF1A1C1F.toInt() else 0xFF0B0C0E.toInt())
        c.drawRect(-CW / 2, -CH / 2, CW / 2, CH / 2, p)
        fill(0xFF3A281E.toInt())
        path.reset()
        path.moveTo(-RDX - rl * 0.2f, RY + rl); path.lineTo(-38f, 25.5f); path.lineTo(38f, 25.5f); path.lineTo(RDX + rr * 0.2f, RY + rr)
        path.lineTo(RDX + rr * 0.2f, RY + rr - 0.5f); path.lineTo(37.5f, 25f); path.lineTo(-37.5f, 25f); path.lineTo(-RDX - rl * 0.2f, RY + rl - 0.5f); path.close()
        c.drawPath(path, p)
        for ((px, rad) in listOf(-RDX to rl, RDX to rr)) {
            p.shader = RadialGradient(px - rad * 0.3f, RY - rad * 0.35f, rad * 1.25f,
                intArrayOf(0xFF5A4334.toInt(), 0xFF3B2A20.toInt(), 0xFF22170F.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(px, RY, rad, p); p.shader = null
            s.strokeWidth = 0.25f; s.color = 0x0FFFE6C8
            var kk = HUB + 1.2f
            while (kk < rad) { c.drawCircle(px, RY, kk, s); kk += 1.6f }
            s.strokeWidth = 0.5f; s.color = 0x59D6AA82
            r.set(px - rad + 0.25f, RY - rad + 0.25f, px + rad - 0.25f, RY + rad - 0.25f)
            c.drawArc(r, 171f, 117f, false, s)
        }
        c.restore()

        // 3 the body over it
        if (sh.clear > 0f) {
            fill(sh.body.a(1 - sh.clear)); c.drawPath(outline, p)
            s.strokeWidth = 0.35f; s.color = 0x2EFFFFFF
            r.set(-CW / 2 + 3, -CH / 2 + 3, CW / 2 - 3, CH / 2 - 3); c.drawRoundRect(r, 2f, 2f, s)
        } else {
            fill(sh.body); c.drawPath(body(sh), p)
            fill(0x0FFFFFFF)
            var yy = -CH / 2 + 40
            while (yy < CH / 2) { c.drawRect(-CW / 2 + 16, yy, CW / 2 - 16, yy + 0.35f, p); yy += 1.2f }
        }
        // glazing: one soft highlight
        c.save(); c.clipPath(seeP)
        p.shader = LinearGradient(-30f, -12f, 10f, 10f, intArrayOf(0x29FFFFFF, 0x0AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(-CW / 2, -CH / 2, CW / 2, CH / 2, p); p.shader = null
        c.restore()
        if (sh.clear == 0f) {
            s.strokeWidth = 0.5f; s.color = 0x1FFFFFFF
            when (sh.win) {
                "wide" -> { r.set(-28f, -9f, 28f, 8f); c.drawRoundRect(r, 2.5f, 2.5f, s) }
                "oval" -> { r.set(-37f, -17.5f, 37f, 13.5f); c.drawOval(r, s) }
                else -> { r.set(-13f, -7.5f, 13f, 4.5f); c.drawRoundRect(r, 2f, 2f, s) }
            }
        }

        // 4 hubs, turning
        for ((px, ang) in listOf(-RDX to aL, RDX to aR)) {
            c.save(); c.translate(px, RY); c.rotate((ang * 180 / PI).toFloat())
            fill(sh.hub); c.drawCircle(0f, 0f, HUB, p)
            s.strokeWidth = 1.2f; s.color = 0x2E000000; c.drawCircle(0f, 0f, HUB - 0.6f, s)
            s.strokeWidth = 0.6f; s.color = 0x38000000
            for (i in 0 until 12) {
                val a = i * PI / 6
                c.drawLine((cos(a) * 6.5).toFloat(), (sin(a) * 6.5).toFloat(), (cos(a) * (HUB - 1.6)).toFloat(), (sin(a) * (HUB - 1.6)).toFloat(), s)
            }
            fill(0xFF0C0D0F.toInt()); c.drawCircle(0f, 0f, 4.6f, p)
            fill(sh.hub)
            for (i in 0 until 6) { c.save(); c.rotate(i * 60f); c.drawRect(3.1f, -0.75f, 4.8f, 0.75f, p); c.restore() }
            if (spindle) {
                p.shader = RadialGradient(-1f, -1f, 3.4f, intArrayOf(0xFFF4F6F8.toInt(), 0xFF8A9097.toInt()), null, Shader.TileMode.CLAMP)
                c.drawCircle(0f, 0f, 2.5f, p)
                for (q in 0 until 3) { c.save(); c.rotate(q * 120f + 28f); c.drawRect(2.1f, -0.55f, 3.7f, 0.55f, p); c.restore() }
                p.shader = null
            }
            c.restore()
        }

        // 5 label and printing
        label(c, tape, detail, facing)

        // 6 foot, screws, edge
        fill(0x12FFFFFF)
        path.reset(); path.moveTo(-31f, CH / 2); path.lineTo(-27f, 22f); path.lineTo(27f, 22f); path.lineTo(31f, CH / 2); path.close()
        c.drawPath(path, p)
        s.strokeWidth = 0.4f; s.color = 0x59000000; c.drawPath(path, s)
        for ((sx, sy) in listOf(-46f to -27.5f, 46f to -27.5f, -46f to 27.5f, 46f to 27.5f, 0f to 27f)) {
            fill(0xFF8D9094.toInt()); c.drawCircle(sx, sy, 1.6f, p)
            s.strokeWidth = 0.45f; s.color = 0xFF3B3D40.toInt()
            c.drawLine(sx - 1, sy - 1, sx + 1, sy + 1, s); c.drawLine(sx + 1, sy - 1, sx - 1, sy + 1, s)
        }
        fill(0xFF0A0A0A.toInt()); c.drawCircle(-12f, 28.5f, 1.5f, p); c.drawCircle(12f, 28.5f, 1.5f, p)
        s.strokeWidth = 0.5f; s.color = 0x24FFFFFF; c.drawPath(outline, s)
    }

    private fun packR(f: Double) = kotlin.math.sqrt(HUB * HUB + f * (PACK * PACK - HUB * HUB)).toFloat()

    private fun label(c: Canvas, tape: Tape, detail: Float, side: Char) {
        val sh = tape.shell
        val title = tape.label
        val sideS = side.toString()
        fun small(text: String, x: Float, y: Float, col: Int, al: Paint.Align = Paint.Align.LEFT) =
            print(c, text, x, y, 2.6f, col.a(detail), true, al, 0.25f)
        when (sh.label) {
            "top" -> {
                fill(sh.labelBg); r.set(-44f, -28f, 44f, -13f); c.drawRoundRect(r, 1.2f, 1.2f, p)
                s.strokeWidth = 0.25f; s.color = 0x2E000000
                c.drawLine(-34f, -20.5f, 34f, -20.5f, s); c.drawLine(-34f, -15.5f, 34f, -15.5f, s)
                fill(0xFF111111.toInt()); c.drawRect(-43f, -27f, -36f, -20f, p)
                print(c, sideS, -39.5f, -21.6f, 6f, Color.WHITE, true, Paint.Align.CENTER)
                handwrite(c, title, -33f, -21.2f, 7.2f, 66f, INK)
                small("TYPE I  ·  NORMAL BIAS 120µs", -33f, -14.5f, 0xFF555555.toInt())
                s.strokeWidth = 0.4f; s.color = 0x40FFFFFF
                c.drawLine(-36f, -11f, 36f, -11f, s); c.drawLine(-36f, 11f, 36f, 11f, s)
                print(c, "NASTRO  FERRIC", -44f, 19f, 5.2f, sh.print, true, Paint.Align.LEFT, 0.6f)
                print(c, tape.length.toString(), 44f, 19.5f, 8f, sh.accent, true, Paint.Align.RIGHT)
            }
            "strip" -> {
                fill(sh.labelBg); r.set(-40f, -28.5f, 40f, -18f); c.drawRoundRect(r, 1f, 1f, p)
                fill(0x14000000); c.drawRect(-40f, -19f, 40f, -18.4f, p)
                print(c, sideS, -36.5f, -20.6f, 6f, sh.accent, true, Paint.Align.CENTER)
                handwrite(c, title, -31f, -20.8f, 7f, 68f, INK)
                when (sh.id) {
                    "clear" -> { small("NORMAL POSITION · 120µs EQ", -36f, 13.5f, 0xFF4A4F55.toInt()); print(c, "C${tape.length}", 40f, 21f, 8f, sh.accent, true, Paint.Align.RIGHT) }
                    "oval" -> {
                        fill(sh.accent); c.drawCircle(-40f, -2f, 3.6f, p)
                        print(c, sideS, -40f, -0.2f, 5f, Color.WHITE, true, Paint.Align.CENTER)
                        small("TINTED OVAL WINDOW  ·  POSITION NORMAL", 0f, -14f, 0xFFD9DDE1.toInt(), Paint.Align.CENTER)
                        print(c, "C${tape.length}", 0f, 21f, 7f, sh.accent, true, Paint.Align.CENTER)
                    }
                    "yellow" -> {
                        fill(sh.accent)
                        path.reset(); path.moveTo(-48f, 9f); path.lineTo(-18f, 9f); path.lineTo(-18f, 15f); path.lineTo(-10f, 15f); path.lineTo(-10f, 22f); path.lineTo(-48f, 22f); path.close()
                        c.drawPath(path, p)
                        print(c, tape.length.toString(), -44f, 17.6f, 7f, Color.WHITE, true)
                        print(c, "NASTRO dB", -44f, 21.3f, 3.4f, Color.WHITE, true, Paint.Align.LEFT, 0.4f)
                        fill(sh.extra.getOrElse(0) { 0xFFB0177F.toInt() })
                        path.reset(); path.moveTo(40f, 20f); path.lineTo(48f, 20f); path.lineTo(46f, 8f); path.close(); c.drawPath(path, p)
                    }
                    "darkI" -> {
                        print(c, "TYPE I NORMAL BIAS", -30f, -10.5f, 3.6f, sh.print, true, Paint.Align.LEFT, 0.3f)
                        print(c, "C${tape.length}", 40f, -9f, 10f, sh.print, true, Paint.Align.RIGHT)
                        val gold = sh.extra.getOrElse(0) { 0xFFB59B5E.toInt() }
                        print(c, tape.length.toString(), 42f, 18.5f, 6f, gold, false, Paint.Align.RIGHT)
                        print(c, "MINUTES", 42f, 21.5f, 2.5f, gold.a(detail), false, Paint.Align.RIGHT, 0.4f)
                    }
                }
            }
            "band" -> {
                fill(sh.labelBg); c.drawRect(-48f, 8f, 48f, 17f, p)
                for (i in 0 until 3) { fill(sh.extra.getOrElse(i) { sh.accent }); c.drawRect(30f, 8f + i * 3, 48f, 11f + i * 3, p) }
                s.strokeWidth = 0.4f; s.color = sh.accent; c.drawRect(-46f, 9.3f, -40.4f, 15.7f, s)
                print(c, sideS, -43.2f, 14.6f, 5.2f, sh.accent, false, Paint.Align.CENTER)
                print(c, "C${tape.length}", -38.5f, 15f, 6f, 0xFF222222.toInt(), true)
                handwrite(c, title, -28f, 15.2f, 6.4f, 56f, INK)
                small("TYPE I (NORMAL) POSITION", -38.5f, 21.5f, 0xFFB9BCC4.toInt())
            }
            "full" -> {
                c.save()
                val lab = Path().apply { addRoundRect(RectF(-46f, -28.5f, 46f, 15.5f), 1.5f, 1.5f, Path.Direction.CW); op(see(sh), Path.Op.DIFFERENCE) }
                p.shader = LinearGradient(0f, -28f, 0f, 16f, 0xFFDFE2E6.toInt(), 0xFFA4A9B0.toInt(), Shader.TileMode.CLAMP)
                c.drawPath(lab, p); p.shader = null
                c.restore()
                fill(0xFFF6F6F4.toInt()); c.drawRect(-45f, -27.5f, 45f, -18.5f, p)
                fill(0xFF111111.toInt()); c.drawRect(-44.5f, -27f, -37.5f, -19f, p)
                print(c, sideS, -41f, -20.6f, 6.4f, Color.WHITE, true, Paint.Align.CENTER)
                handwrite(c, title, -35f, -20.6f, 7f, 70f, INK)
                fill(sh.accent); c.drawRect(-46f, 9f, 46f, 13.5f, p)
                print(c, "POSITION · NORMAL", 0f, 12.4f, 3.2f, Color.WHITE.a(detail), true, Paint.Align.CENTER, 0.6f)
                fill(0x8CD4202A.toInt().a(detail))
                for (i in 0 until 24) c.drawRect(-46f + i * 3.85f, 14f, -44.4f + i * 3.85f, 15.5f, p)
                print(c, "NASTRO", -42f, 4f, 4.4f, 0xFF2A2D31.toInt(), true, Paint.Align.LEFT, 0.4f)
                print(c, tape.length.toString(), 42f, 5f, 8f, sh.extra.getOrElse(0) { 0xFF6FA8DC.toInt() }, true, Paint.Align.RIGHT)
            }
            "index" -> {
                fill(sh.labelBg); c.drawRect(-46f, -28.5f, 46f, -6.5f, p)
                print(c, "C${tape.length}", -42f, -21f, 6.5f, 0xFF111111.toInt(), false)
                print(c, "INDEX", -30f, -22f, 3.4f, 0xFF333333.toInt(), true, Paint.Align.LEFT, 0.6f)
                s.strokeWidth = 0.25f; s.color = 0x40000000
                for (yy in floatArrayOf(-21.5f, -16f, -10.5f)) c.drawLine(-18f, yy, 43f, yy, s)
                handwrite(c, title, -17f, -16.6f, 6.6f, 60f, INK)
                handwrite(c, "side $sideS", -17f, -11.2f, 5f, 30f, INK.a(detail))
                fill(sh.accent); c.drawRect(-46f, -6.5f, 46f, -4.9f, p)
                fill(0xFF141414.toInt()); c.drawRect(-46f, 9f, 46f, 18f, p)
                print(c, "LOW NOISE  HIGH OUTPUT", -30f, 15.5f, 4f, 0xFFF3F3F3.toInt(), true, Paint.Align.LEFT, 0.5f)
                handwrite(c, "side ${sideS.lowercase()}1", 44f, 16f, 5f, 20f, 0xFFF3F3F3.toInt(), Paint.Align.RIGHT)
            }
            "tag" -> {
                print(c, sideS, -41f, -15f, 13f, sh.print, false, Paint.Align.CENTER)
                print(c, "CHROME", -33f, -20f, 6f, sh.print, true, Paint.Align.LEFT, 0.8f)
                print(c, "POSITION HIGH", -33f, -15.5f, 3.4f, sh.print.a(detail), true, Paint.Align.LEFT, 0.6f)
                print(c, tape.length.toString(), -33f, -3f, 13f, sh.print, true)
                print(c, "LOW-RESONANCE MECHANISM", 40f, -22f, 3f, sh.print.a(detail), true, Paint.Align.RIGHT, 0.4f)
                c.save(); c.translate(29f, 15f); c.rotate(-5.2f)
                fill(sh.labelBg); r.set(-20f, -5.2f, 18f, 4.8f); c.drawRoundRect(r, 1.2f, 1.2f, p)
                s.strokeWidth = 0.3f; s.color = 0x26000000; c.drawRoundRect(r, 1.2f, 1.2f, s)
                handwrite(c, tape.album, -1f, 2f, 6f, 34f, INK, Paint.Align.CENTER)
                c.restore()
            }
        }
    }

    /** Heads, pinch roller, capstan and guides, in the cassette's millimetres (foot at +y). */
    fun mechanism(c: Canvas) {
        fun steel(x: Float, y: Float, rad: Float) {
            p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, 0xFFF2F4F6.toInt(), 0xFF7D848C.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p); p.shader = null
        }
        fill(0xFF4A525C.toInt()); r.set(-25f, 32.6f, 25f, 36.8f); c.drawRoundRect(r, 1.4f, 1.4f, p)
        p.shader = LinearGradient(-5f, 0f, 5f, 0f, intArrayOf(0xFF9AA1A8.toInt(), 0xFFF1F3F5.toInt(), 0xFF8D949B.toInt()), null, Shader.TileMode.CLAMP)
        r.set(-5f, 29.6f, 5f, 34.2f); c.drawRoundRect(r, 1.6f, 1.6f, p); p.shader = null
        fill(0xFF2B2F34.toInt()); c.drawRect(-0.15f, 29.6f, 0.15f, 31.6f, p)
        fill(0xFF121314.toInt()); c.drawCircle(17.5f, 33.4f, 2.9f, p)
        s.strokeWidth = 0.4f; s.color = 0x29FFFFFF; r.set(15.3f, 31.2f, 19.7f, 35.6f); c.drawArc(r, 180f, 108f, false, s)
        steel(13.6f, 31.6f, 0.85f); steel(-29f, 32.6f, 1.1f); steel(29f, 32.6f, 1.1f)
        s.strokeWidth = 0.35f; s.color = 0xFF9AA1A8.toInt()
        path.reset(); for (i in 0 until 7) { val yy = 35.4f + if (i % 2 == 1) 0.5f else -0.5f; if (i == 0) path.moveTo(-3f, yy) else path.lineTo(-3f + i, yy) }
        c.drawPath(path, s)
    }

    // ---- the player ----------------------------------------------------------------------------------
    private var brushed: Bitmap? = null
    private fun brush(w: Int, h: Int): Bitmap {
        brushed?.let { if (it.width >= w && it.height >= h) return it }
        val b = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val pp = Paint()
        val rnd = java.util.Random(7)
        val step = max(1, dens.toInt())
        for (yy in 0 until h step step) {
            val a = 0.03f + rnd.nextFloat() * 0.07f
            pp.color = if (rnd.nextBoolean()) Color.WHITE.a(a) else 0xFF142846.toInt().a(a)
            cv.drawRect(0f, yy.toFloat(), w.toFloat(), (yy + step).toFloat(), pp)
        }
        brushed = b
        return b
    }

    /** The Walkman: its face with the door shut ([open] false), or the open compartment ([open] true). */
    fun deck(c: Canvas, G: DeckGeom, open: Boolean, now: Long, pressed: Deck.Key?, pressAge: Long, proximity: Map<String, Float>, lifting: Boolean) {
        val detail = 1 - Deck.conc
        val x = G.x; val y = G.y; val w = G.w; val h = G.h
        val rad = G.radius
        // body: light-blue anodised aluminium, brushed
        r.set(x, y, x + w, y + h)
        p.shader = LinearGradient(x, y, x + w, y + h, intArrayOf(0xFFB9CFE4.toInt(), 0xFF9FBAD4.toInt(), 0xFF7F9DBB.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        p.setShadowLayer(dp(30f), 0f, dp(14f), 0x8C000000.toInt())
        c.drawRoundRect(r, rad, rad, p); p.clearShadowLayer(); p.shader = null
        c.save(); path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW); c.clipPath(path)
        val bm = brush(w.toInt() + 1, h.toInt() + 1)
        p.shader = BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setTranslate(x, y) })
        }
        p.alpha = 230; c.drawRect(r, p); p.alpha = 255; p.shader = null
        p.shader = RadialGradient(x + w * 0.25f, y + h * 0.05f, w * 0.9f, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(r, p); p.shader = null
        c.restore()
        s.strokeWidth = dp(1.2f); s.color = 0x8CFFFFFF.toInt(); r.set(x + 0.6f, y + 0.6f, x + w - 0.6f, y + h - 0.6f); c.drawRoundRect(r, rad, rad, s)
        s.strokeWidth = 1f; s.color = 0x59142337; r.set(x + dp(3f), y + dp(3f), x + w - dp(3f), y + h - dp(3f)); c.drawRoundRect(r, rad * 0.93f, rad * 0.93f, s)

        lcdPanel(c, G.lcd, detail)

        // window recess, or the compartment
        val Wn = G.win
        c.save()
        if (open) {
            val e = dp(12f)
            val cx0 = Wn.left - e; val cy0 = Wn.top - e; val cx1 = Wn.right + e + dp(10f); val cy1 = Wn.bottom + e
            r.set(cx0 - dp(2f), cy0 - dp(2f), cx1 + dp(2f), cy1 + dp(2f)); fill(0x8CE8F0F8.toInt()); c.drawRoundRect(r, dp(18f), dp(18f), p)
            r.set(cx0, cy0, cx1, cy1); path.reset(); path.addRoundRect(r, dp(16f), dp(16f), Path.Direction.CW); c.clipPath(path)
            p.shader = LinearGradient(cx0, cy0, cx1, cy1, 0xFF3A424C.toInt(), 0xFF20262D.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            s.strokeWidth = 1f; s.color = 0x0FFFFFFF
            var yy = cy0 + dp(12f); val step = dp(8f)
            while (yy < cy1) { c.drawLine(cx0 + dp(8f), yy, cx1 - dp(8f), yy, s); yy += step }
            p.shader = LinearGradient(0f, cy0, 0f, cy0 + dp(26f), 0x8C000000.toInt(), 0, Shader.TileMode.CLAMP); c.drawRect(cx0, cy0, cx1, cy0 + dp(40f), p)
            p.shader = LinearGradient(cx0, 0f, cx0 + dp(22f), 0f, 0x8C000000.toInt(), 0, Shader.TileMode.CLAMP); c.drawRect(cx0, cy0, cx0 + dp(34f), cy1, p)
            p.shader = null
            c.save(); c.translate(G.ccx - 2 * G.k, G.ccy); c.rotate(-90f); c.scale(G.k, G.k); mechanism(c); c.restore()
        } else {
            r.set(Wn.left - dp(4f), Wn.top - dp(4f), Wn.right + dp(4f), Wn.bottom + dp(4f)); fill(0x8C1E2D3E.toInt()); c.drawRoundRect(r, dp(14f), dp(14f), p)
            r.set(Wn); fill(0xFF14181E.toInt()); c.drawRoundRect(r, dp(11f), dp(11f), p)
            path.reset(); path.addRoundRect(r, dp(11f), dp(11f), Path.Direction.CW); c.clipPath(path)
        }
        // the tape
        val tape = Deck.tape
        val doorT = easeOut(Deck.door)
        if (tape != null && !(lifting && !open)) {
            c.save()
            c.translate(G.ccx - if (open) 4 * G.k else 0f, G.ccy - if (open) 0f else doorT * Wn.height() * 0.04f)
            val fl = if (Deck.flipping) cos(Deck.flip * PI).toFloat() else 1f
            c.rotate(-90f); c.scale(G.k, G.k * max(0.02f, kotlin.math.abs(fl)))
            val facing = if (Deck.flipping && Deck.flip > 0.5f) (if (Deck.facing == 'A') 'B' else 'A') else Deck.facing
            cassette(c, tape, true, detail, facing, spindle = open, shadow = if (open) 4f else 0f)
            c.restore()
        }
        if (!open) {
            // smoked acrylic door with one highlight; it tilts open from its foot on the cover screen
            c.save()
            c.translate(Wn.left, Wn.bottom); c.scale(1f, 1 - doorT * 0.12f)
            fill(0xFF162232.toInt().a(0.46f + doorT * 0.2f)); c.drawRect(0f, -Wn.height(), Wn.width(), 0f, p)
            p.shader = LinearGradient(0f, -Wn.height(), Wn.width(), 0f,
                intArrayOf(0, 0, Color.WHITE.a(0.1f + doorT * 0.12f), 0), floatArrayOf(0f, 0.42f, 0.5f, 0.62f), Shader.TileMode.CLAMP)
            c.drawRect(0f, -Wn.height(), Wn.width(), 0f, p); p.shader = null
            c.restore()
        }
        c.restore()
        if (!open) { s.strokeWidth = dp(0.6f); s.color = 0x59FFFFFF; r.set(Wn.left - dp(4f), Wn.top - dp(4f), Wn.right + dp(4f), Wn.bottom + dp(4f)); c.drawRoundRect(r, dp(14f), dp(14f), s) }

        // keys strip and keys
        val k0 = G.keys[0]; val kN = G.keys[3]
        if (G.tall) r.set(k0.x - k0.r * 1.6f, k0.y - k0.r * 1.45f, kN.x + k0.r * 1.6f, k0.y + k0.r * 1.45f)
        else r.set(k0.x - k0.r * 1.45f, k0.y - k0.r * 1.6f, k0.x + k0.r * 1.45f, kN.y + k0.r * 1.6f)
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF6F7680.toInt(), 0xFF8C939C.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, k0.r * 1.45f, k0.r * 1.45f, p); p.shader = null
        s.strokeWidth = 1f; s.color = 0x40000000; c.drawRoundRect(r, k0.r * 1.45f, k0.r * 1.45f, s)
        val m = Deck.mode
        for (K in G.keys) {
            val latched = when (K.key) {
                Deck.Key.PLAY -> m == Deck.Mode.PLAY
                Deck.Key.FF -> m == Deck.Mode.FF || (m == Deck.Mode.AMS && Deck.v * Deck.dir > 0)
                Deck.Key.REW -> m == Deck.Mode.REW || (m == Deck.Mode.AMS && Deck.v * Deck.dir < 0)
                Deck.Key.STOP -> false
            }
            val tap = if (pressed == K.key) max(0f, 1 - pressAge / 160f) else 0f
            key(c, K, max(if (latched) 0.6f else 0f, tap))
        }
        val P = G.keys.first { it.key == Deck.Key.PLAY }
        val on = m == Deck.Mode.PLAY
        val lx = P.x - P.r * 1.05f; val ly = P.y - P.r * 1.32f
        if (on) { p.shader = RadialGradient(lx, ly, P.r * 0.6f, 0x8CFFAD33.toInt(), 0x00FFAD33, Shader.TileMode.CLAMP); c.drawCircle(lx, ly, P.r * 0.6f, p); p.shader = null }
        fill(if (on) 0xFFFFAD33.toInt() else 0xFF5A4A3A.toInt()); c.drawCircle(lx, ly, max(dp(2.2f), P.r * 0.09f), p)

        // wordmark and the printed switches
        val Lg = G.leg
        val wm = if (G.narrow) min(Lg.width() * 0.085f, dp(26f)) else min(dp(30f), Lg.height() * 0.34f)
        val wy = if (G.narrow) Lg.top + Lg.height() * 0.62f else Lg.top + Lg.height() * 0.36f
        print(c, "NASTRO", Lg.left + dp(1f), wy + dp(1f), wm, 0x59 shl 24 or 0x1E324B, true, Paint.Align.LEFT, wm * 0.32f)
        print(c, "NASTRO", Lg.left, wy, wm, 0xBFFFFFFF.toInt(), true, Paint.Align.LEFT, wm * 0.32f)
        for (T in G.legends) {
            val onT = when (T.id) { "autoRev" -> Deck.autoRev; "megaBass" -> Deck.megaBass; else -> Deck.hold }
            val prox = proximity[T.id] ?: 0f
            val a = (0.62f + 0.38f * max(prox, if (onT) 1f else 0f)) * max(0.15f, detail)
            val size = if (G.narrow) max(dp(10f), min(dp(12.5f), T.h * 0.62f)) else max(dp(11f), min(dp(14f), Lg.height() * 0.17f))
            val dot = dp(3f)
            print(c, T.text, T.x + dot * 4, T.y + dp(1f), size, 0xFF1C2E46.toInt().a(a * 0.35f), true, Paint.Align.LEFT, size * 0.14f)
            print(c, T.text, T.x + dot * 4, T.y, size, Color.WHITE.a(a), true, Paint.Align.LEFT, size * 0.14f)
            fill(if (onT) 0xFFFFAD33.toInt() else 0x73283C55); c.drawCircle(T.x + dot * 1.3f, T.y - size * 0.32f, dot, p)
        }
    }

    private fun easeOut(v: Float): Float { val q = 1 - v.coerceIn(0f, 1f); return 1 - q * q * q }

    private fun key(c: Canvas, K: DeckGeom.Key, depth: Float) {
        val x = K.x; val y = K.y; val rad = K.r
        c.save(); c.translate(0f, depth * dp(1.6f))
        p.shader = RadialGradient(x - rad * 0.35f, y - rad * 0.4f, rad * 1.3f,
            if (depth > 0.3f) 0xFFC3C8CE.toInt() else 0xFFD9DDE2.toInt(), if (depth > 0.3f) 0xFF7E858D.toInt() else 0xFF8E959D.toInt(), Shader.TileMode.CLAMP)
        p.setShadowLayer(dp(8f) * (1 - depth * 0.8f), 0f, dp(3f) * (1 - depth), 0x73000000)
        c.drawCircle(x, y, rad, p); p.clearShadowLayer(); p.shader = null
        s.strokeWidth = dp(1f); s.color = 0x8CFFFFFF.toInt(); r.set(x - rad + 0.5f, y - rad + 0.5f, x + rad - 0.5f, y + rad - 0.5f)
        c.drawArc(r, 189f, 126f, false, s)
        fill(0xFF33383F.toInt())
        val q = rad * 0.36f
        path.reset()
        when (K.key) {
            Deck.Key.PLAY -> { path.moveTo(x - q * 0.7f, y - q); path.lineTo(x + q, y); path.lineTo(x - q * 0.7f, y + q); path.close() }
            Deck.Key.STOP -> path.addRect(x - q * 0.75f, y - q * 0.75f, x + q * 0.75f, y + q * 0.75f, Path.Direction.CW)
            Deck.Key.FF -> { path.moveTo(x - q, y - q * 0.8f); path.lineTo(x, y); path.lineTo(x - q, y + q * 0.8f); path.close(); path.moveTo(x, y - q * 0.8f); path.lineTo(x + q, y); path.lineTo(x, y + q * 0.8f); path.close() }
            Deck.Key.REW -> { path.moveTo(x + q, y - q * 0.8f); path.lineTo(x, y); path.lineTo(x + q, y + q * 0.8f); path.close(); path.moveTo(x, y - q * 0.8f); path.lineTo(x - q, y); path.lineTo(x, y + q * 0.8f); path.close() }
        }
        c.drawPath(path, p)
        // tactile dot on play, for finding it blind
        if (K.key == Deck.Key.PLAY) { fill(0xFFF2F4F6.toInt()); c.drawCircle(x, y + rad * 0.68f, max(dp(1.6f), rad * 0.07f), p) }
        c.restore()
    }

    private fun lcdPanel(c: Canvas, L: RectF, detail: Float) {
        fill(0xFF5D6B78.toInt()); c.drawRoundRect(L, dp(8f), dp(8f), p)
        r.set(L.left + dp(3f), L.top + dp(3f), L.right - dp(3f), L.bottom - dp(3f))
        p.shader = LinearGradient(0f, L.top, 0f, L.bottom, 0xFFA9B59A.toInt(), 0xFF94A185.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(6f), dp(6f), p); p.shader = null
        val on = 0xFF1D2318.toInt(); val off = 0x141D2318
        val rowH = L.height() * 0.64f
        val dh = rowH * 0.7f; val dw = dh * 0.52f; val dy = L.top + rowH * 0.18f
        // side and direction
        print(c, Deck.sideLetter.toString(), L.left + L.width() * 0.05f, dy + dh * 0.92f, dh * 0.95f, on, true)
        val ax = L.left + L.width() * 0.05f + dh * 0.62f; val ay = dy + dh * 0.5f
        path.reset()
        if (Deck.dir > 0) { path.moveTo(ax, ay - dh * 0.18f); path.lineTo(ax + dh * 0.3f, ay); path.lineTo(ax, ay + dh * 0.18f) }
        else { path.moveTo(ax + dh * 0.3f, ay - dh * 0.18f); path.lineTo(ax, ay); path.lineTo(ax + dh * 0.3f, ay + dh * 0.18f) }
        path.close()
        val running = Deck.mode == Deck.Mode.PLAY || Deck.mode == Deck.Mode.AMS
        fill(if (running) on else off); c.drawPath(path, p)
        val cnt = Deck.counter().toString().padStart(3, '0')
        val cx0 = L.left + L.width() * 0.27f
        for (i in 0 until 3) seg7(c, cnt[i], cx0 + i * dw * 1.32f, dy, dw, dh, on, off)
        // track and time on the right; the title runs along the bottom row and falls away at speed
        val cur = Deck.current()
        val fs = max(dp(11f), rowH * 0.24f)
        fun mm(sec: Double) = "${(sec / 60).toInt()}:${(sec % 60).toInt().toString().padStart(2, '0')}"
        val n = cur.track?.number ?: 0
        print(c, if (L.width() < dp(280f) * 1.6f) n.toString().padStart(2, '0') else "TRACK ${n.toString().padStart(2, '0')}",
            L.right - L.width() * 0.05f, L.top + rowH * 0.48f, fs, on, true, Paint.Align.RIGHT)
        print(c, "${mm(max(0.0, cur.into))} / ${mm(cur.track?.durSecs ?: 0.0)}", L.right - L.width() * 0.05f, L.top + rowH * 0.86f, fs, on.a(detail), true, Paint.Align.RIGHT)
        val ix = cx0 + 3 * dw * 1.32f + dw * 0.25f
        if (Deck.megaBass) print(c, "MB", ix, L.top + rowH * 0.48f, fs, on.a(detail), true)
        if (Deck.autoRev) print(c, "⇄", ix, L.top + rowH * 0.86f, fs, on.a(detail), true)
        val title = cur.track?.title ?: ""
        c.save(); r.set(L.left + dp(8f), L.top + rowH, L.right - dp(8f), L.bottom - dp(4f)); c.clipRect(r)
        print(c, title.uppercase(), L.left + L.width() * 0.05f, L.bottom - L.height() * 0.11f, L.height() * 0.17f, on.a(detail * 0.9f), false, Paint.Align.LEFT, L.height() * 0.01f)
        c.restore()
    }

    private val SEG = mapOf('0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc", '5' to "afgcd",
        '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg")

    private fun seg7(c: Canvas, ch: Char, x: Float, y: Float, w: Float, h: Float, on: Int, off: Int) {
        val th = w * 0.17f; val hh = h / 2
        val segs = mapOf(
            'a' to floatArrayOf(x + th * 0.6f, y, w - th * 1.2f, th), 'd' to floatArrayOf(x + th * 0.6f, y + h - th, w - th * 1.2f, th),
            'g' to floatArrayOf(x + th * 0.6f, y + hh - th / 2, w - th * 1.2f, th),
            'f' to floatArrayOf(x, y + th * 0.6f, th, hh - th * 0.9f), 'b' to floatArrayOf(x + w - th, y + th * 0.6f, th, hh - th * 0.9f),
            'e' to floatArrayOf(x, y + hh + th * 0.3f, th, hh - th * 0.9f), 'c' to floatArrayOf(x + w - th, y + hh + th * 0.3f, th, hh - th * 0.9f),
        )
        val lit = SEG[ch] ?: ""
        c.save(); c.skew(-0.08f, 0f); c.translate(h * 0.04f + y * 0.08f, 0f)
        for ((k, v) in segs) { fill(if (lit.contains(k)) on else off); r.set(v[0], v[1], v[0] + v[2], v[1] + v[3]); c.drawRoundRect(r, th * 0.45f, th * 0.45f, p) }
        c.restore()
    }

    // ---- the shelf -----------------------------------------------------------------------------------
    class Slot(val tape: Tape, val index: Int, val rect: RectF)

    /** Inside of the door when the phone is open: the shelf is kept here. */
    fun doorInside(c: Canvas, area: RectF, hingeRight: Boolean) {
        val rad = dp(18f)
        p.shader = LinearGradient(area.left, area.top, area.right, area.bottom, 0xFF45586C.toInt(), 0xFF2F3D4C.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(area, rad, rad, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(area, rad, rad, Path.Direction.CW); c.clipPath(path)
        s.strokeWidth = 1f; s.color = 0x09FFFFFF
        var yy = area.top + dp(14f); val step = dp(7f)
        while (yy < area.bottom) { c.drawLine(area.left, yy, area.right, yy, s); yy += step }
        val kw = dp(8f); val kh = dp(44f)
        val hx = if (hingeRight) area.right - kw - dp(6f) else area.left + dp(6f)
        for (f in floatArrayOf(0.14f, 0.5f, 0.86f)) {
            val ky = area.top + area.height() * f
            p.shader = LinearGradient(hx, 0f, hx + kw, 0f, intArrayOf(0xFF7F8A96.toInt(), 0xFFD4DBE2.toInt(), 0xFF6C7783.toInt()), null, Shader.TileMode.CLAMP)
            r.set(hx, ky - kh / 2, hx + kw, ky + kh / 2); c.drawRoundRect(r, kw / 2, kw / 2, p); p.shader = null
        }
        c.restore()
    }

    fun shelf(c: Canvas, area: RectF, slots: List<Slot>, lean: FloatArray, header: Boolean, onDoor: Boolean, count: Int) {
        val fade = 1 - Deck.conc * 0.75f
        if (onDoor) doorInside(c, area, true) else { fill(0xFF13171C.toInt()); c.drawRoundRect(area, dp(18f), dp(18f), p) }
        c.save(); path.reset(); path.addRoundRect(area, dp(18f), dp(18f), Path.Direction.CW); c.clipPath(path)
        if (header) {
            val pad = dp(18f)
            print(c, "THE SHELF", area.left + pad, area.top + dp(30f), dp(13f),
                (if (onDoor) 0xFFB9C8D6.toInt() else 0xFF8D98A5.toInt()).a(fade), true, Paint.Align.LEFT, dp(13f) * 0.24f)
            handwrite(c, "$count ${if (count == 1) "tape" else "tapes"}", area.left + pad, area.top + dp(56f), dp(22f), area.width(), 0xFFC9D1D9.toInt().a(fade))
        }
        val inDeck = Deck.tape
        val pending = Deck.pending
        for (sl in slots) {
            val rc = sl.rect
            if (rc.top > area.bottom || rc.bottom < area.top) continue
            c.save()
            c.translate(rc.centerX(), rc.centerY() + lean.getOrElse(sl.index) { 0f })
            if (sl.tape === inDeck || sl.tape === pending) {
                // the case stays on the shelf, open, showing its card
                fill(0x0AFFFFFF); r.set(-rc.width() / 2, -rc.height() / 2, rc.width() / 2, rc.height() / 2); c.drawRoundRect(r, dp(6f), dp(6f), p)
                s.strokeWidth = dp(1f); s.color = 0x2EFFFFFF; s.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
                c.drawRoundRect(r, dp(6f), dp(6f), s); s.pathEffect = null
                val cardH = rc.height() * 0.86f
                val art = sl.tape.art
                if (art != null) {
                    val side = cardH
                    r.set(-side / 2, -side / 2, side / 2, side / 2)
                    p.shader = null; p.alpha = (fade * 255).toInt()
                    c.drawBitmap(art, Rect(0, 0, art.width, art.height), r, p); p.alpha = 255
                } else {
                    val cw = rc.height() * 0.62f
                    fill(sl.tape.card.a(fade)); r.set(-cw / 2, -cardH / 2, cw / 2, cardH / 2); c.drawRect(r, p)
                    fill(0xFFF2EBDD.toInt().a(fade)); c.drawRect(-cw / 2, cardH * 0.08f, cw / 2, cardH * 0.24f, p)
                    c.drawCircle(0f, -cardH * 0.14f, cw * 0.24f, p)
                    print(c, sl.tape.artist.uppercase().take(16), 0f, cardH * 0.2f, cw * 0.11f, sl.tape.card, true, Paint.Align.CENTER)
                }
            } else {
                val kk = rc.width() / (CW + 4)
                c.scale(kk, kk)
                cassette(c, sl.tape, false, fade)
            }
            c.restore()
        }
        c.restore()
    }
}
