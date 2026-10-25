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

/** A round key: its bounding square, centre and radius. */
class KeyR(val key: Deck.Key, val rect: RectF) {
    val cx get() = rect.centerX()
    val cy get() = rect.centerY()
    val r get() = rect.width() / 2
}
class Legend(val id: String, val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Where everything sits. [FACE] is the player with its door shut (folded); [BAY] is the open
 * compartment (unfolded), where the tape is as large as the screen allows.
 */
class PlayerGeom private constructor(val kind: Kind, val x: Float, val w: Float) {
    enum class Kind { FACE, BAY }
    var y = 0f; var h = 0f
    lateinit var lcd: RectF
    lateinit var chan: RectF
    lateinit var keys: List<KeyR>
    /** The touch area over the tape: the lens, or the compartment. */
    lateinit var win: RectF
    var k = 1f; var tx = 0f; var ty = 0f
    var legends: List<Legend> = emptyList()
    var lid: RectF? = null
    var lens: RectF? = null
    var hold: RectF? = null
    var battery: RectF? = null
    var wheel: RectF? = null
    var comp: RectF? = null
    var radius = 0f

    companion object {
        const val CW = 100.4f
        const val CH = 63.8f
        private const val REEL_Y = -2f

        fun face(x: Float, y: Float, w: Float, h: Float): PlayerGeom = PlayerGeom(Kind.FACE, x, w).apply {
            val lidH = (h - 1.0f * w).coerceIn(0.8f * w, 1.5f * w)
            this.h = 1.0f * w + lidH
            this.y = y + (h - this.h) / 2
            radius = w * 0.12f
            lcd = RectF(x + 0.07f * w, this.y + 0.07f * w, x + 0.93f * w, this.y + 0.32f * w)
            val ld = RectF(x + 0.055f * w, lcd.bottom + 0.055f * w, x + 0.945f * w, lcd.bottom + 0.055f * w + lidH)
            lid = ld
            k = (ld.height() * 0.6f) / 74f                    // the lens shows both hubs: 74 mm of shell
            val lcx = ld.centerX(); val lcy = ld.top + ld.height() * 0.45f
            val ln = RectF(lcx - 15 * k, lcy - 37 * k, lcx + 15 * k, lcy + 37 * k)
            lens = ln
            tx = lcx + REEL_Y * k; ty = lcy
            win = RectF(ln)
            chan = RectF(x + 0.055f * w, ld.bottom + 0.06f * w, x + 0.945f * w, ld.bottom + 0.28f * w)
            keys = keyRow(chan)
            val st = RectF(x + 0.07f * w, chan.bottom + 0.05f * w, x + 0.93f * w, chan.bottom + 0.25f * w)
            hold = RectF(st.left, st.top + st.height() * 0.18f, st.left + st.width() * 0.26f, st.top + st.height() * 0.52f)
            legends = listOf(
                Legend("megaBass", "MEGA BASS", st.left + st.width() * 0.34f, st.top + st.height() * 0.36f, st.width() * 0.34f, st.height() * 0.4f),
                Legend("autoRev", "AUTO REV", st.left + st.width() * 0.34f, st.top + st.height() * 0.8f, st.width() * 0.34f, st.height() * 0.4f),
            )
            battery = RectF(st.left + st.width() * 0.72f, st.top + st.height() * 0.06f, st.right, st.top + st.height() * 0.94f)
            wheel = RectF(x + w - 0.012f * w, ld.top + ld.height() * 0.08f, x + w + 0.033f * w, ld.top + ld.height() * 0.08f + 0.36f * w)
        }

        fun bay(x: Float, y: Float, w: Float, h: Float): PlayerGeom = PlayerGeom(Kind.BAY, x, w).apply {
            this.y = y; this.h = h
            radius = w * 0.08f
            lcd = RectF(x + 0.06f * w, y + 0.05f * w, x + 0.94f * w, y + 0.25f * w)
            val chanH = 0.2f * w
            chan = RectF(x + 0.05f * w, y + h - chanH - 0.05f * w, x + 0.95f * w, y + h - 0.05f * w)
            keys = keyRow(chan)
            val c = RectF(x + 0.035f * w, lcd.bottom + 0.045f * w, x + 0.965f * w, chan.top - 0.045f * w)
            comp = c
            k = minOf((c.width() - 0.07f * w) / CH, (c.height() - 0.06f * w) / (CW + 10))
            // the tape's open edge and the heads face the hinge (the crease), as in a real player
            tx = c.centerX() + 4 * k; ty = c.centerY()
            win = RectF(c)
        }

        /**
         * Four round keys, spaced to be found blind as the WM-EX610's were; play the largest,
         * with room round it for its ring of beads.
         */
        fun keyRow(chan: RectF): List<KeyR> {
            val scale = floatArrayOf(1f, 1.22f, 1f, 1f)
            val order = listOf(Deck.Key.REW, Deck.Key.PLAY, Deck.Key.STOP, Deck.Key.FF)
            val d = minOf(chan.height() * 0.8f, chan.width() / 5.7f)
            val gap = (chan.width() - scale.sum() * d) / 5
            var kx = chan.left + gap
            val cy = chan.centerY()
            return order.mapIndexed { i, key ->
                val kd = d * scale[i]
                KeyR(key, RectF(kx, cy - kd / 2, kx + kd, cy + kd / 2)).also { kx += kd + gap }
            }
        }
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

    private val CW = PlayerGeom.CW
    private val CH = PlayerGeom.CH
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
    /** What the stage knows that the painter needs: the touch, the reach, the volume. */
    class Live(
        var pressed: Deck.Key? = null, var pressAge: Long = 9999, var proximity: Map<String, Float> = emptyMap(),
        var lifting: Boolean = false, var volume: Float = 0.7f, var volAge: Long = 9999,
    )

    /**
     * Visconti is the default: Alfa Romeo's green as the Fold apps use it (an approximation; Alfa
     * publishes no value), as clear-coated lacquer with champagne engraving and keys. Light blue and
     * grey were the WM-EX610's own; graphite and rosso are ours.
     */
    class Finish(val stops: IntArray, val lid: IntArray, val key: IntArray, val ink: Int, val deep: Int, val chan: Int,
                 val etchLight: Boolean = false, val sym: Int = 0xB81E242C.toInt(), val lacquer: Boolean = false,
                 val inside: IntArray = intArrayOf(0xFF45586C.toInt(), 0xFF2F3D4C.toInt()))

    private fun col(v: Number) = v.toInt()
    val finishes = linkedMapOf(
        "visconti" to Finish(intArrayOf(col(0xFF0B6A39), col(0xFF033A21), col(0xFF000805)), intArrayOf(col(0xFF0D723E), col(0xFF033D22)),
            intArrayOf(col(0xFFF1E6CD), col(0xFFB49A6A)), col(0xEBD9BB86), col(0x8C000000), col(0xFF03150D), true, col(0xD9463216), true,
            intArrayOf(col(0xFF123A28), col(0xFF06180F))),
        "blue" to Finish(intArrayOf(col(0xFFBFD4E8), col(0xFF9FBAD4), col(0xFF7593B3)), intArrayOf(col(0xFFC8DBED), col(0xFFA5BFD8)),
            intArrayOf(col(0xFFD9DDE2), col(0xFF8E959D)), col(0xC7FFFFFF), col(0x7316283E), col(0xFF5B6F84)),
        "grey" to Finish(intArrayOf(col(0xFFE2E5E9), col(0xFFC2C7CD), col(0xFF979EA6)), intArrayOf(col(0xFFE9ECEF), col(0xFFC9CED4)),
            intArrayOf(col(0xFFF0F2F4), col(0xFF9EA5AD)), col(0xB8282E36), col(0x591E242C), col(0xFF7C838C)),
        "graphite" to Finish(intArrayOf(col(0xFF61676F), col(0xFF41474E), col(0xFF2A2E33)), intArrayOf(col(0xFF6A7078), col(0xFF474D54)),
            intArrayOf(col(0xFF8A9097), col(0xFF3E444B)), col(0xB8EBEFF3), col(0x80000000), col(0xFF25292E), true, col(0xD9EBEFF3)),
        "rosso" to Finish(intArrayOf(col(0xFFD2363E), col(0xFFA8141E), col(0xFF6E0A12)), intArrayOf(col(0xFFDC4048), col(0xFFB01A24)),
            intArrayOf(col(0xFFE9E4DC), col(0xFFA49C90)), col(0xCCFFF4E8), col(0x80320006), col(0xFF5E0910), true),
    )
    private fun finish() = finishes[Prefs.finish] ?: finishes.getValue("visconti")

    private var brushed: Bitmap? = null
    private fun brush(size: Int): Bitmap {
        brushed?.let { if (it.width >= size) return it }
        val n = max(1, size)
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val pp = Paint()
        val rnd = java.util.Random(7)
        val step = max(1, dens.toInt())
        for (yy in 0 until n step step) {
            val a = 0.03f + rnd.nextFloat() * 0.07f
            pp.color = if (rnd.nextBoolean()) Color.WHITE.a(a) else 0xFF142846.toInt().a(a)
            cv.drawRect(0f, yy.toFloat(), n.toFloat(), (yy + step).toFloat(), pp)
        }
        brushed = b
        return b
    }

    /** Brushed anodised metal: horizontal on the body, vertical on the lid so it reads as its own panel. */
    private fun metal(c: Canvas, R: RectF, rad: Float, stops: IntArray, vertical: Boolean, lacquer: Boolean = false) {
        if (lacquer) {
            // clear-coated lacquer: deep colour darkening towards the edges, and one broad reflection
            p.shader = RadialGradient(R.left + R.width() * 0.35f, R.top + R.height() * 0.3f, max(R.width(), R.height()) * 0.85f, stops, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(R, rad, rad, p)
            p.shader = LinearGradient(R.left, R.top, R.right, R.top + R.height() * 0.6f,
                intArrayOf(0, 0, 0x33E1FFEC, 0x42F0FFF4, 0, 0), floatArrayOf(0f, 0.24f, 0.33f, 0.35f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(R, rad, rad, p); p.shader = null
            return
        }
        p.shader = LinearGradient(R.left, R.top, R.left + R.width() * 0.7f, R.bottom, stops, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(R, rad, rad, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(R, rad, rad, Path.Direction.CW); c.clipPath(path)
        val bm = brush(max(R.width(), R.height()).toInt() + 2)
        p.alpha = 190
        if (vertical) { c.translate(R.right, R.top); c.rotate(90f); c.drawBitmap(bm, Rect(0, 0, R.height().toInt() + 1, R.width().toInt() + 1), RectF(0f, 0f, R.height(), R.width()), p) }
        else c.drawBitmap(bm, Rect(0, 0, R.width().toInt() + 1, R.height().toInt() + 1), R, p)
        p.alpha = 255
        c.restore()
    }

    /** Light from the upper left catches the top and left edges; the rest falls into shade. */
    private fun bevel(c: Canvas, R: RectF, rad: Float, strength: Float = 1f) {
        s.strokeWidth = dp(0.9f)
        s.shader = LinearGradient(R.left, R.top, R.right, R.bottom,
            intArrayOf(Color.WHITE.a(0.75f * strength), Color.WHITE.a(0.12f * strength), Color.BLACK.a(0.35f * strength)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        r.set(R.left + dp(0.5f), R.top + dp(0.5f), R.right - dp(0.5f), R.bottom - dp(0.5f)); c.drawRoundRect(r, rad, rad, s)
        s.shader = null
    }

    /** A recess: shade falls from its upper edge, light catches its lower lip. */
    private fun recess(c: Canvas, R: RectF, rad: Float, fillCol: Int) {
        fill(fillCol); c.drawRoundRect(R, rad, rad, p)
        c.save(); path.reset(); path.addRoundRect(R, rad, rad, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(0f, R.top, 0f, R.top + R.height() * 0.45f, Color.BLACK.a(0.45f), 0, Shader.TileMode.CLAMP); c.drawRect(R, p)
        p.shader = LinearGradient(R.left, 0f, R.left + R.width() * 0.12f, 0f, Color.BLACK.a(0.25f), 0, Shader.TileMode.CLAMP); c.drawRect(R, p)
        p.shader = null; c.restore()
        s.strokeWidth = dp(0.8f)
        s.shader = LinearGradient(0f, R.top, 0f, R.bottom, Color.BLACK.a(0.35f), Color.WHITE.a(0.55f), Shader.TileMode.CLAMP)
        r.set(R.left - dp(0.4f), R.top - dp(0.4f), R.right + dp(0.4f), R.bottom + dp(0.4f)); c.drawRoundRect(r, rad, rad, s)
        s.shader = null
    }

    /** Plain printed caps in the deck's type, for the stage's own labels. */
    fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) = print(c, text, x, y, size, color, true, Paint.Align.RIGHT, size * 0.2f)

    /** Engraved type: a dark cut with a lit lower edge, or, through dark anodising, bright metal. */
    private fun engrave(c: Canvas, text: String, x: Float, y: Float, size: Float, F: Finish, align: Paint.Align = Paint.Align.LEFT, track: Float = 0.14f) {
        if (F.etchLight) {
            print(c, text, x, y - dp(0.5f), size, Color.BLACK.a(0.45f), true, align, size * track)
            print(c, text, x, y, size, F.ink, true, align, size * track)
        } else {
            print(c, text, x, y + dp(0.5f), size, Color.WHITE.a(0.35f), true, align, size * track)
            print(c, text, x, y, size, F.deep, true, align, size * track)
        }
    }

    // ---- the readout, as on the WM-EX610's remote ------------------------------------------------
    // Warm grey reflective glass sunk in a black and silver surround; upright starburst characters
    // ("PLAY F", "FF F", "REW R"); every segment casts its shadow on the reflector behind.
    private val LCD_TOP = 0xFFA29B84.toInt()
    private val LCD_BOT = 0xFF8A8370.toInt()
    private val LCD_ON = 0xFF1F1C15.toInt()
    private val LCD_SHADOW = 0x4228241A

    private fun lcd(c: Canvas, L: RectF, detail: Float, live: Live) {
        val rad = L.height() * 0.42f
        p.shader = LinearGradient(0f, L.top, 0f, L.bottom, 0xFF2A2B2E.toInt(), 0xFF0C0D0F.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(L, rad, rad, p); p.shader = null
        bevel(c, L, rad, 0.6f)
        val sb = L.height() * 0.07f
        val S0 = RectF(L.left + sb, L.top + sb, L.right - sb, L.bottom - sb)
        p.shader = LinearGradient(0f, S0.top, 0f, S0.bottom, intArrayOf(0xFFE9EBEE.toInt(), 0xFFA7ACB2.toInt(), 0xFF8E949B.toInt(), 0xFFD3D7DB.toInt()), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(S0, rad - sb, rad - sb, p); p.shader = null
        val ib = L.height() * 0.075f
        val I = RectF(S0.left + ib * 1.6f, S0.top + ib, S0.right - ib * 1.6f, S0.bottom - ib)
        val ir = min(I.height() * 0.3f, rad)
        fill(0xFF15161A.toInt()); c.drawRoundRect(I, ir, ir, p)
        val G = RectF(I.left + dp(0.8f), I.top + dp(0.8f), I.right - dp(0.8f), I.bottom - dp(0.8f))
        c.save(); path.reset(); path.addRoundRect(G, ir, ir, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(0f, G.top, 0f, G.bottom, LCD_TOP, LCD_BOT, Shader.TileMode.CLAMP); c.drawRect(G, p)
        // depth: the glass sits above the reflector, so the rim shades it
        p.shader = LinearGradient(0f, G.top, 0f, G.top + G.height() * 0.3f, Color.BLACK.a(0.32f), 0, Shader.TileMode.CLAMP); c.drawRect(G, p)
        p.shader = LinearGradient(G.left, 0f, G.left + G.width() * 0.06f, 0f, Color.BLACK.a(0.22f), 0, Shader.TileMode.CLAMP); c.drawRect(G, p)
        p.shader = null

        // icons along the top: the tape, auto reverse, Mega Bass, battery
        val ih = G.height() * 0.13f; val iy = G.top + G.height() * 0.1f
        val ix = G.left + G.width() * 0.06f; val rx = G.left + G.width() * 0.94f
        lcdInk(c, 1f) { col ->
            s.color = col; s.strokeWidth = ih * 0.18f; r.set(ix, iy, ix + ih * 1.7f, iy + ih); c.drawRoundRect(r, ih * 0.25f, ih * 0.25f, s)
            fill(col); c.drawCircle(ix + ih * 0.55f, iy + ih * 0.5f, ih * 0.18f, p); c.drawCircle(ix + ih * 1.15f, iy + ih * 0.5f, ih * 0.18f, p)
            s.strokeWidth = ih * 0.16f; c.drawRect(rx - ih * 1.9f, iy, rx - ih * 0.2f, iy + ih, s)
            c.drawRect(rx - ih * 0.2f, iy + ih * 0.3f, rx, iy + ih * 0.7f, p); c.drawRect(rx - ih * 1.75f, iy + ih * 0.18f, rx - ih * 0.75f, iy + ih * 0.82f, p)
        }
        if (Deck.megaBass) lcdInk(c, 1f) { col -> print(c, "MB", rx - ih * 2.4f, iy + ih * 0.98f, ih * 1.15f, col, true, Paint.Align.RIGHT) }
        if (Deck.autoRev) lcdInk(c, 1f) { col ->
            val ax = rx - ih * 5.2f
            s.color = col; s.strokeWidth = ih * 0.16f
            c.drawLine(ax, iy + ih * 0.3f, ax + ih * 1.5f, iy + ih * 0.3f, s); c.drawLine(ax + ih * 1.5f, iy + ih * 0.72f, ax, iy + ih * 0.72f, s)
            fill(col)
            path.reset(); path.moveTo(ax + ih * 1.8f, iy + ih * 0.3f); path.lineTo(ax + ih * 1.35f, iy); path.lineTo(ax + ih * 1.35f, iy + ih * 0.6f); path.close(); c.drawPath(path, p)
            path.reset(); path.moveTo(ax - ih * 0.3f, iy + ih * 0.72f); path.lineTo(ax + ih * 0.15f, iy + ih * 0.42f); path.lineTo(ax + ih * 0.15f, iy + ih * 1.02f); path.close(); c.drawPath(path, p)
        }

        // main row: what the deck is doing and which way the tape runs, then the counter
        val m = Deck.mode
        val mode = when {
            Deck.door > 0.5f -> "OPEN"
            Deck.hold && live.pressed != null && live.pressAge < 900 -> "HOLD"
            m == Deck.Mode.PLAY -> "PLAY"; m == Deck.Mode.FF -> "FF"; m == Deck.Mode.REW -> "REW"; m == Deck.Mode.AMS -> "AMS"
            else -> "STOP"
        }
        val ch = G.height() * 0.36f; val cw = ch * 0.6f; val cy = G.top + G.height() * 0.3f
        seg14text(c, mode, G.left + G.width() * 0.06f, cy, cw, ch, 5, 1f)
        seg14text(c, if (Deck.dir > 0) "F" else "R", G.left + G.width() * 0.06f + cw * 1.32f * 5.3f, cy, cw, ch, 1, 1f)
        seg14text(c, Deck.counter().toString().padStart(3, '0'), G.left + G.width() * 0.94f - cw * 1.32f * 3 + cw * 0.32f, cy, cw, ch, 3, 1f)
        // bottom row: the title stepping along like a real LCD's marquee, or the volume while the wheel turns
        val cur = Deck.current()
        val txt = if (live.volAge < 1300) "VOL " + "-".repeat((live.volume * 10).toInt().coerceIn(0, 10))
        else ((cur.track?.number ?: 0).toString().padStart(2, '0') + " " + (cur.track?.title ?: "")).uppercase()
        val th = G.height() * 0.17f; val tw = th * 0.6f
        val room = ((G.width() * 0.88f) / (tw * 1.32f)).toInt().coerceAtLeast(1)
        val shown = if (txt.length > room) {
            val run = "$txt    $txt    "
            val step = ((android.os.SystemClock.uptimeMillis() / 260) % (txt.length + 4)).toInt()
            run.substring(step, (step + room).coerceAtMost(run.length))
        } else txt
        seg14text(c, shown, G.left + G.width() * 0.06f, G.top + G.height() * 0.74f, tw, th, room, 0.25f + 0.75f * detail)
        // one highlight across the glass
        p.shader = LinearGradient(G.left, G.top, G.left + G.width() * 0.5f, G.bottom, intArrayOf(Color.WHITE.a(0.2f), Color.WHITE.a(0.04f), 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(G, p); p.shader = null
        c.restore()
    }

    /** LCD ink: draw twice, first as the segments' shadow on the reflector, then the segments. */
    private inline fun lcdInk(c: Canvas, alpha: Float, draw: (Int) -> Unit) {
        c.save(); c.translate(dp(0.55f), dp(0.75f)); draw(LCD_SHADOW.a(alpha)); c.restore()
        draw(LCD_ON.a(alpha))
    }

    // Starburst (14-segment) characters. G is the whole middle bar; "h2" is its right half alone.
    private val SEG14 = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abdeG", '3' to "abcdg2", '4' to "bcfG", '5' to "acdfG", '6' to "acdefG", '7' to "abc",
        '8' to "abcdefG", '9' to "abcdfG",
        'A' to "abcefG", 'B' to "abcdilh2", 'C' to "adef", 'D' to "abcdil", 'E' to "adefg", 'F' to "aefg", 'G' to "acdefh2", 'H' to "bcefG",
        'I' to "adil", 'J' to "bcde", 'K' to "efgjm", 'L' to "def", 'M' to "bcefhj", 'N' to "bcefhm", 'O' to "abcdef", 'P' to "abefG",
        'Q' to "abcdefm", 'R' to "abefGm", 'S' to "acdfG", 'T' to "ail", 'U' to "bcdef", 'V' to "efkj", 'W' to "bcefkm", 'X' to "hjkm",
        'Y' to "hjl", 'Z' to "adjk", '-' to "G", '\'' to "i", '/' to "jk", '&' to "adehjkm", '+' to "Gil",
    )
    private val SEGS = mapOf(
        "a" to floatArrayOf(0f, 0f, 1f, 0f), "b" to floatArrayOf(1f, 0f, 1f, .5f), "c" to floatArrayOf(1f, .5f, 1f, 1f),
        "d" to floatArrayOf(0f, 1f, 1f, 1f), "e" to floatArrayOf(0f, .5f, 0f, 1f), "f" to floatArrayOf(0f, 0f, 0f, .5f),
        "g" to floatArrayOf(0f, .5f, .5f, .5f), "g2" to floatArrayOf(.5f, .5f, 1f, .5f),
        "h" to floatArrayOf(0f, 0f, .5f, .5f), "i" to floatArrayOf(.5f, 0f, .5f, .5f), "j" to floatArrayOf(1f, 0f, .5f, .5f),
        "k" to floatArrayOf(0f, 1f, .5f, .5f), "l" to floatArrayOf(.5f, 1f, .5f, .5f), "m" to floatArrayOf(1f, 1f, .5f, .5f),
    )

    private fun litSegments(ch: Char): Set<String> {
        val code = SEG14[ch.uppercaseChar()] ?: return emptySet()
        val out = HashSet<String>()
        var q = 0
        while (q < code.length) {
            val k = code[q]
            when {
                k == 'G' -> { out += "g"; out += "g2" }
                k == 'h' && q + 1 < code.length && code[q + 1] == '2' -> { out += "g2"; q++ }
                k == 'g' && q + 1 < code.length && code[q + 1] == '2' -> { out += "g2"; q++ }
                else -> out += k.toString()
            }
            q++
        }
        return out
    }

    private fun seg14text(c: Canvas, text: String, x: Float, y: Float, cw: Float, ch: Float, cells: Int, alpha: Float) {
        val lw = cw * 0.19f; val gp = lw * 0.62f
        s.strokeCap = Paint.Cap.SQUARE; s.strokeWidth = lw
        lcdInk(c, alpha) { col ->
            s.color = col
            for (i in 0 until cells) {
                val chr = text.getOrNull(i) ?: ' '
                val x0 = x + i * cw * 1.32f
                for (name in litSegments(chr)) {
                    val sg = SEGS[name] ?: continue
                    val ax = x0 + sg[0] * cw; val ay = y + sg[1] * ch; val bx = x0 + sg[2] * cw; val by = y + sg[3] * ch
                    val dx = bx - ax; val dy = by - ay; val len = kotlin.math.hypot(dx, dy); val ux = dx / len; val uy = dy / len
                    c.drawLine(ax + ux * gp, ay + uy * gp, bx - ux * gp, by - uy * gp, s)
                }
            }
        }
        s.strokeCap = Paint.Cap.BUTT
    }

    // ---- the keys: round, spun aluminium, each in its own well ------------------------------------
    // Around play, a ring of amber beads from the Alfa lamp: lit while the tape runs, a bright head
    // travelling at the take-up reel's speed and in its direction; unlit beads hold an ember.
    private fun keys(c: Canvas, G: PlayerGeom, F: Finish, live: Live) {
        val m = Deck.mode
        fun latched(k: Deck.Key) = when (k) {
            Deck.Key.PLAY -> m == Deck.Mode.PLAY
            Deck.Key.FF -> m == Deck.Mode.FF || (m == Deck.Mode.AMS && Deck.v * Deck.dir > 0)
            Deck.Key.REW -> m == Deck.Mode.REW || (m == Deck.Mode.AMS && Deck.v * Deck.dir < 0)
            Deck.Key.STOP -> false
        }
        for (K in G.keys) {
            val d = max(if (latched(K.key)) 0.55f else 0f, if (live.pressed == K.key) max(0f, 1 - live.pressAge / 170f) else 0f)
            val cx = K.cx; val cy = K.cy; val R = K.r
            // Sunk into the body, as on the WM-EX610: a deep round hole whose upper lip is in shadow
            // and whose lower lip catches the light; the wall falls away dark above and lit below.
            val wellR = if (K.key == Deck.Key.PLAY) R * 1.36f else R * 1.16f
            p.shader = LinearGradient(0f, cy - wellR, 0f, cy + wellR, intArrayOf(Color.BLACK.a(0.35f), 0, Color.WHITE.a(0.55f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, wellR + dp(1f), p)
            p.shader = LinearGradient(0f, cy - wellR, 0f, cy + wellR, intArrayOf(darken(F.chan, 0.55f), darken(F.chan, 0.2f), lighten(F.chan, 0.15f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, wellR, p); p.shader = null
            if (K.key == Deck.Key.PLAY) beads(c, cx, cy, R * 1.18f)
            c.save(); c.translate(cx, cy + d * R * 0.05f); val sc = 1 - d * 0.02f; c.scale(sc, sc)
            // a sunken key throws no shadow outwards: only a tight contact shadow where it meets the floor
            fill(F.key[1]); p.setShadowLayer(R * 0.08f, 0f, R * 0.03f, 0x8C000000.toInt()); c.drawCircle(0f, 0f, R, p); p.clearShadowLayer()
            // a convex cap: spun aluminium domed like a bead. Light from the upper left gathers in
            // one soft hotspot; the curve falls into shade towards the lower right; a thin rim of
            // light bounces back from the body along the lower edge.
            val fr = R
            val base = if (d > 0.3f) darken(F.key[0], 0.08f) else F.key[0]
            val low = darken(F.key[1], if (d > 0.3f) 0.3f else 0.2f)
            p.shader = RadialGradient(-R * 0.32f, -R * 0.38f, R * 1.25f, intArrayOf(lighten(base, 0.35f), base, F.key[1], low),
                floatArrayOf(0.04f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, R, p); p.shader = null
            // the lathe's sheen, following the dome
            val sheenHi = Color.WHITE.a(0.14f); val sheenLo = Color.BLACK.a(0.08f)
            val sweep = android.graphics.SweepGradient(0f, 0f, intArrayOf(sheenHi, sheenLo, sheenHi, sheenLo, sheenHi, sheenLo, sheenHi, sheenLo, sheenHi),
                floatArrayOf(0f, .125f, .25f, .375f, .5f, .625f, .75f, .875f, 1f))
            sweep.setLocalMatrix(android.graphics.Matrix().apply { setRotate(-45f) })
            p.shader = sweep; c.drawCircle(0f, 0f, R * 0.96f, p); p.shader = null
            s.strokeWidth = dp(0.4f); s.color = Color.WHITE.a(0.05f)
            var rr2 = R * 0.12f; while (rr2 < R * 0.9f) { c.drawCircle(0f, 0f, rr2, s); rr2 += R * 0.075f }
            // the hotspot
            p.shader = RadialGradient(-R * 0.34f, -R * 0.4f, R * 0.42f, Color.WHITE.a(0.55f - d * 0.25f), 0, Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, R, p); p.shader = null
            // the hole's upper lip shades the top of the cap
            p.shader = LinearGradient(0f, -R, 0f, -R * 0.15f, Color.BLACK.a(0.42f), 0, Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, R, p); p.shader = null
            // bounce light along the lower rim
            s.strokeWidth = R * 0.06f; s.color = Color.WHITE.a(0.28f); s.strokeCap = Paint.Cap.ROUND
            r.set(-R * 0.93f, -R * 0.93f, R * 0.93f, R * 0.93f); c.drawArc(r, 27f, 126f, false, s)
            s.strokeCap = Paint.Cap.BUTT
            // the edge where the dome turns away
            s.strokeWidth = dp(0.6f); s.color = Color.BLACK.a(0.28f); c.drawCircle(0f, 0f, R - dp(0.3f), s)
            // engraved symbol
            val q = fr * 0.34f
            path.reset()
            when (K.key) {
                Deck.Key.PLAY -> { path.moveTo(-q * 0.6f, -q); path.lineTo(q, 0f); path.lineTo(-q * 0.6f, q); path.close() }
                Deck.Key.STOP -> path.addRect(-q * 0.72f, -q * 0.72f, q * 0.72f, q * 0.72f, Path.Direction.CW)
                Deck.Key.FF -> { path.moveTo(-q, -q * 0.75f); path.lineTo(0f, 0f); path.lineTo(-q, q * 0.75f); path.close(); path.moveTo(0f, -q * 0.75f); path.lineTo(q, 0f); path.lineTo(0f, q * 0.75f); path.close() }
                Deck.Key.REW -> { path.moveTo(q, -q * 0.75f); path.lineTo(0f, 0f); path.lineTo(q, q * 0.75f); path.close(); path.moveTo(0f, -q * 0.75f); path.lineTo(-q, 0f); path.lineTo(0f, q * 0.75f); path.close() }
            }
            c.save(); c.translate(0f, dp(0.5f)); fill(Color.WHITE.a(0.5f)); c.drawPath(path, p); c.restore()
            fill(F.sym); c.drawPath(path, p)
            c.restore()
        }
    }

    private fun beads(c: Canvas, cx: Float, cy: Float, R: Float) {
        val n = 18
        val m = Deck.mode
        val running = m == Deck.Mode.PLAY || m == Deck.Mode.AMS || m == Deck.Mode.FF || m == Deck.Mode.REW
        val turns = (Deck.angR / (2 * PI)).toFloat()
        val head = ((turns % 1f) + 1f) % 1f * n
        val br = R * 0.055f
        for (i in 0 until n) {
            val a = -PI / 2 + i.toDouble() / n * PI * 2
            val bx = cx + (kotlin.math.cos(a) * R).toFloat(); val by = cy + (kotlin.math.sin(a) * R).toFloat()
            val dist = minOf(kotlin.math.abs(i - head), n - kotlin.math.abs(i - head))
            val lit = if (running) 0.35f + 0.65f * max(0f, 1 - dist / 2.2f) else 0.08f
            if (lit > 0.4f) { p.shader = RadialGradient(bx, by, br * 4, (0xFFFFA028.toInt()).a(0.45f * lit), 0, Shader.TileMode.CLAMP); c.drawCircle(bx, by, br * 4, p); p.shader = null }
            fill(Color.argb(((0.25f + 0.75f * lit) * 255).toInt(), 255, (150 + 60 * lit).toInt(), (40 + 40 * lit).toInt()))
            c.drawCircle(bx, by, br, p)
        }
    }


    private fun lighten(col: Int, amt: Float): Int {
        val r0 = (col shr 16) and 255; val g0 = (col shr 8) and 255; val b0 = col and 255
        fun f(v: Int) = (v + (255 - v) * amt).toInt().coerceIn(0, 255)
        return (col and 0xFF000000.toInt()) or (f(r0) shl 16) or (f(g0) shl 8) or f(b0)
    }

    private fun darken(col: Int, amt: Float): Int {
        val r0 = (col shr 16) and 255; val g0 = (col shr 8) and 255; val b0 = col and 255
        fun f(v: Int) = (v * (1 - amt)).toInt().coerceIn(0, 255)
        return (col and 0xFF000000.toInt()) or (f(r0) shl 16) or (f(g0) shl 8) or f(b0)
    }

    // ---- the face: the player with its door shut ---------------------------------------------------
    fun face(c: Canvas, G: PlayerGeom, live: Live) {
        val F = finish()
        val detail = 1 - Deck.conc
        val body = RectF(G.x, G.y, G.x + G.w, G.y + G.h)
        val R = G.radius; val w = G.w
        fill(F.stops[2]); p.setShadowLayer(dp(24f), 0f, dp(10f), 0x99000000.toInt()); c.drawRoundRect(body, R, R, p); p.clearShadowLayer()
        G.wheel?.let { wheel(c, it, live.volume) }
        metal(c, body, R, F.stops, false, F.lacquer)
        if (!F.lacquer) {
            // one highlight over the shoulder (lacquer has its clear-coat reflection instead)
            c.save(); path.reset(); path.addRoundRect(body, R, R, Path.Direction.CW); c.clipPath(path)
            p.shader = RadialGradient(body.left + w * 0.18f, body.top + G.h * 0.02f, w * 1.1f, Color.WHITE.a(0.32f), 0, Shader.TileMode.CLAMP)
            c.drawRect(body, p); p.shader = null
            c.restore()
        }
        bevel(c, body, R)
        G.wheel?.let { fill(Color.BLACK.a(0.35f)); r.set(it.left - dp(0.6f), it.top - dp(1.2f), it.left + it.width() * 0.5f, it.bottom + dp(1.2f)); c.drawRoundRect(r, dp(2f), dp(2f), p) }

        lcd(c, G.lcd, detail, live)

        // the lid: the door you open by unfolding, brushed the other way, with a seam
        val Ld = G.lid ?: return
        val lr = w * 0.07f
        fill(F.lid[1]); p.setShadowLayer(dp(3f), 0f, dp(1f), 0x47000000); c.drawRoundRect(Ld, lr, lr, p); p.clearShadowLayer()
        metal(c, Ld, lr, intArrayOf(F.lid[0], F.lid[1], if (F.lacquer) F.stops[2] else F.stops[1]), true, F.lacquer)
        if (!F.lacquer) {
            c.save(); path.reset(); path.addRoundRect(Ld, lr, lr, Path.Direction.CW); c.clipPath(path)
            p.shader = RadialGradient(Ld.left + Ld.width() * 0.4f, Ld.top + Ld.height() * 0.32f, Ld.height() * 0.85f,
                intArrayOf(Color.WHITE.a(0.22f), 0, Color.BLACK.a(0.14f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(Ld, p); p.shader = null
            c.restore()
        }
        s.strokeWidth = dp(1f); s.color = Color.BLACK.a(0.42f); r.set(Ld.left - dp(0.5f), Ld.top - dp(0.5f), Ld.right + dp(0.5f), Ld.bottom + dp(0.5f)); c.drawRoundRect(r, lr, lr, s)
        // the seam's lit edge; on lacquer, a champagne coachline
        s.strokeWidth = dp(0.6f); s.color = if (F.lacquer) 0x8CD9BB86.toInt() else Color.WHITE.a(0.5f); r.set(Ld.left + dp(0.4f), Ld.top + dp(0.4f), Ld.right - dp(0.4f), Ld.bottom - dp(0.4f)); c.drawRoundRect(r, lr, lr, s)

        lens(c, G, detail, live.lifting)

        val fs = max(dp(9f), w * 0.034f)
        engrave(c, "AUTO REVERSE", Ld.right - Ld.width() * 0.07f, Ld.top + Ld.height() * 0.075f, fs, F, Paint.Align.RIGHT, 0.2f)
        // the wordmark, deep-cut
        engrave(c, "NASTRO", Ld.centerX(), Ld.bottom - Ld.height() * 0.075f, min(w * 0.11f, dp(44f)), F, Paint.Align.CENTER, 0.42f)

        keys(c, G, F, live)
        strip(c, G, F, detail, live)
    }

    private fun lens(c: Canvas, G: PlayerGeom, detail: Float, lifting: Boolean) {
        val L = G.lens ?: return
        val rad = L.width() / 2
        r.set(L.left - dp(2f), L.top - dp(2f), L.right + dp(2f), L.bottom + dp(2f))
        p.shader = LinearGradient(L.left, L.top, L.right, L.bottom, intArrayOf(0xFFF4F6F8.toInt(), 0xFF8B939C.toInt(), 0xFFD9DEE3.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad + dp(2f), rad + dp(2f), p); p.shader = null
        fill(0xFF0C0F13.toInt()); c.drawRoundRect(L, rad, rad, p)
        c.save(); path.reset(); path.addRoundRect(L, rad, rad, Path.Direction.CW); c.clipPath(path)
        val tape = Deck.tape
        if (tape != null && !lifting) {
            c.save(); c.translate(G.tx, G.ty)
            val fl = if (Deck.flipping) cos(Deck.flip * PI).toFloat() else 1f
            c.rotate(90f); c.scale(G.k, G.k * max(0.02f, kotlin.math.abs(fl)))
            val facing = if (Deck.flipping && Deck.flip > 0.5f) (if (Deck.facing == 'A') 'B' else 'A') else Deck.facing
            cassette(c, tape, true, detail, facing)
            c.restore()
        }
        fill(0x6B101822); c.drawRect(L, p)
        p.shader = LinearGradient(L.left, 0f, L.right, 0f, intArrayOf(0, Color.WHITE.a(0.18f), 0), floatArrayOf(0f, 0.22f, 0.34f), Shader.TileMode.CLAMP); c.drawRect(L, p)
        p.shader = RadialGradient(L.centerX(), L.centerY(), L.height() * 0.6f, intArrayOf(0, Color.BLACK.a(0.45f)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP); c.drawRect(L, p)
        p.shader = null
        c.restore()
    }

    /** A knurled wheel standing proud of the body edge; its ribs move with the volume. */
    private fun wheel(c: Canvas, Wl: RectF, volume: Float) {
        val rad = Wl.width() * 0.45f
        p.shader = LinearGradient(Wl.left, 0f, Wl.right, 0f, intArrayOf(0xFF5F666E.toInt(), 0xFFC9CED3.toInt(), 0xFF4A5057.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(Wl, rad, rad, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(Wl, rad, rad, Path.Direction.CW); c.clipPath(path)
        val step = Wl.height() / 18; val off = (volume * Wl.height() * 1.6f) % step
        s.strokeWidth = dp(0.6f); s.color = Color.BLACK.a(0.35f)
        var yy = Wl.top - step + off
        while (yy < Wl.bottom + step) {
            val tt = ((yy - Wl.top) / Wl.height()).coerceIn(0f, 1f)
            val y2 = Wl.top + Wl.height() * (0.5f - 0.5f * cos(tt * PI).toFloat())
            c.drawLine(Wl.left, y2, Wl.right, y2, s); yy += step
        }
        p.shader = LinearGradient(0f, Wl.top, 0f, Wl.bottom, intArrayOf(Color.BLACK.a(0.5f), 0, 0, Color.BLACK.a(0.5f)), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(Wl, p); p.shader = null
        c.restore()
    }

    private fun strip(c: Canvas, G: PlayerGeom, F: Finish, detail: Float, live: Live) {
        val Hd = G.hold ?: return
        // HOLD: a real slide; orange shows in the slot when it is on, as on the real thing
        recess(c, Hd, Hd.height() / 2, 0x99141820.toInt())
        val kw = Hd.width() * 0.44f
        val kx = Hd.left + if (Deck.hold) Hd.width() * 0.52f else Hd.width() * 0.04f
        if (Deck.hold) { fill(0xFFFF8A1F.toInt()); r.set(Hd.left + Hd.width() * 0.08f, Hd.top + Hd.height() * 0.3f, Hd.left + Hd.width() * 0.48f, Hd.top + Hd.height() * 0.7f); c.drawRoundRect(r, Hd.height() * 0.2f, Hd.height() * 0.2f, p) }
        r.set(kx, Hd.top + Hd.height() * 0.08f, kx + kw, Hd.top + Hd.height() * 0.92f)
        p.shader = LinearGradient(0f, Hd.top, 0f, Hd.bottom, F.key[0], F.key[1], Shader.TileMode.CLAMP)
        p.setShadowLayer(dp(2f), 0f, dp(0.6f), 0x66000000); c.drawRoundRect(r, Hd.height() * 0.42f, Hd.height() * 0.42f, p); p.clearShadowLayer(); p.shader = null
        fill(Color.BLACK.a(0.25f)); for (i in 0 until 3) c.drawRect(kx + kw * (0.32f + i * 0.16f), Hd.top + Hd.height() * 0.28f, kx + kw * (0.32f + i * 0.16f) + dp(0.6f), Hd.top + Hd.height() * 0.72f, p)
        val fs = max(dp(9f), G.w * 0.032f)
        engrave(c, "HOLD", Hd.centerX(), Hd.bottom + fs * 1.25f, fs, F, Paint.Align.CENTER, 0.24f)
        // printed switches: they wake as the finger nears
        for (T in G.legends) {
            val onT = if (T.id == "megaBass") Deck.megaBass else Deck.autoRev
            val prox = live.proximity[T.id] ?: 0f
            val a = (0.62f + 0.38f * max(prox, if (onT) 1f else 0f)) * max(0.2f, detail)
            c.saveLayerAlpha(T.x, T.y - fs * 1.4f, T.x + T.w + fs * 6, T.y + fs * 0.6f, (a * 255).toInt())
            engrave(c, T.text, T.x + fs * 1.1f, T.y, fs, F, Paint.Align.LEFT, 0.2f)
            c.restore()
            fill(if (onT) 0xFFFFAD33.toInt() else 0x661E2837); c.drawCircle(T.x + fs * 0.35f, T.y - fs * 0.32f, max(dp(2f), fs * 0.24f), p)
        }
        // the battery door: the settings live behind it
        val B = G.battery ?: return
        s.strokeWidth = dp(0.9f); s.color = Color.BLACK.a(0.35f); c.drawRoundRect(B, B.height() * 0.18f, B.height() * 0.18f, s)
        s.strokeWidth = dp(0.6f); s.color = Color.WHITE.a(0.45f); r.set(B.left + dp(0.6f), B.top + dp(0.6f), B.right - dp(0.6f), B.bottom - dp(0.6f)); c.drawRoundRect(r, B.height() * 0.18f, B.height() * 0.18f, s)
        for (i in 0 until 5) {
            val yy = B.top + B.height() * (0.2f + i * 0.09f)
            fill(Color.BLACK.a(0.22f)); c.drawRect(B.left + B.width() * 0.18f, yy, B.right - B.width() * 0.18f, yy + dp(0.8f), p)
            fill(Color.WHITE.a(0.35f)); c.drawRect(B.left + B.width() * 0.18f, yy + dp(0.8f), B.right - B.width() * 0.18f, yy + dp(1.3f), p)
        }
        engrave(c, "1.5V AA", B.centerX(), B.top + B.height() * 0.86f, fs * 0.82f, F, Paint.Align.CENTER, 0.12f)
    }

    // ---- the open compartment: the tape in all its glory --------------------------------------------
    fun bay(c: Canvas, G: PlayerGeom, live: Live) {
        val F = finish()
        val detail = 1 - Deck.conc
        val body = RectF(G.x, G.y, G.x + G.w, G.y + G.h)
        val R = G.radius
        fill(F.stops[2]); p.setShadowLayer(dp(20f), 0f, dp(9f), 0x99000000.toInt()); c.drawRoundRect(body, R, R, p); p.clearShadowLayer()
        metal(c, body, R, F.stops, false, F.lacquer)
        bevel(c, body, R)
        lcd(c, G.lcd, detail, live)
        val C = G.comp ?: return
        val cr = G.w * 0.05f
        c.save(); path.reset(); path.addRoundRect(C, cr, cr, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(C.left, C.top, C.right, C.bottom, 0xFF3B434D.toInt(), 0xFF1E242B.toInt(), Shader.TileMode.CLAMP); c.drawRect(C, p); p.shader = null
        s.strokeWidth = dp(0.5f); s.color = 0x0DFFFFFF
        var yy = C.top + dp(6f); while (yy < C.bottom) { c.drawLine(C.left + dp(6f), yy, C.right - dp(6f), yy, s); yy += dp(5f) }
        p.shader = LinearGradient(0f, C.top, 0f, C.top + dp(22f), Color.BLACK.a(0.6f), 0, Shader.TileMode.CLAMP); c.drawRect(C.left, C.top, C.right, C.top + dp(22f), p)
        p.shader = LinearGradient(C.left, 0f, C.left + dp(18f), 0f, Color.BLACK.a(0.5f), 0, Shader.TileMode.CLAMP); c.drawRect(C.left, C.top, C.left + dp(18f), C.bottom, p)
        p.shader = null
        c.save(); c.translate(G.tx - 2 * G.k, G.ty); c.rotate(90f); c.scale(G.k, G.k); mechanism(c); c.restore()
        val tape = Deck.tape
        if (tape != null) {
            c.save(); c.translate(G.tx, G.ty)
            val fl = if (Deck.flipping) cos(Deck.flip * PI).toFloat() else 1f
            c.rotate(90f); c.scale(G.k, G.k * max(0.02f, kotlin.math.abs(fl)))
            val facing = if (Deck.flipping && Deck.flip > 0.5f) (if (Deck.facing == 'A') 'B' else 'A') else Deck.facing
            cassette(c, tape, true, detail, facing, spindle = true, shadow = 4f)
            c.restore()
        }
        c.restore()
        s.strokeWidth = dp(1.2f); s.color = Color.WHITE.a(0.55f); r.set(C.left - dp(0.6f), C.top - dp(0.6f), C.right + dp(0.6f), C.bottom + dp(0.6f)); c.drawRoundRect(r, cr, cr, s)
        keys(c, G, F, live)
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
        val inside = finish().inside
        p.shader = LinearGradient(area.left, area.top, area.right, area.bottom, inside[0], inside[1], Shader.TileMode.CLAMP)
        c.drawRoundRect(area, rad, rad, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(area, rad, rad, Path.Direction.CW); c.clipPath(path)
        s.strokeWidth = 1f; s.color = 0x09FFFFFF
        var yy = area.top + dp(14f); val step = dp(7f)
        while (yy < area.bottom) { c.drawLine(area.left, yy, area.right, yy, s); yy += step }
        // The trim along the hinge edge: one moulded groove, where the index lives
        val gx = if (hingeRight) area.right - dp(14f) else area.left + dp(14f)
        s.strokeWidth = dp(1f); s.color = 0x33000000; c.drawLine(gx, area.top + dp(20f), gx, area.bottom - dp(20f), s)
        s.color = 0x14FFFFFF; c.drawLine(gx + dp(1f), area.top + dp(20f), gx + dp(1f), area.bottom - dp(20f), s)
        c.restore()
    }

    /**
     * The index in the door's trim (Giulietta): letters only appear as the thumb reaches the edge,
     * the one under it swelling, and blend back when the touch is broken.
     */
    fun indexRail(c: Canvas, rail: RectF, letters: List<Char>, wake: Float, current: Char?) {
        if (wake < 0.01f || letters.isEmpty()) return
        fill(0xFF0D1014.toInt().a(0.5f * wake)); c.drawRoundRect(rail, rail.width() / 2, rail.width() / 2, p)
        val step = rail.height() / letters.size
        val size = min(dp(13f), step * 0.86f)
        for ((i, ch) in letters.withIndex()) {
            val cy = rail.top + step * (i + 0.5f)
            val on = ch == current
            val sz = if (on) size * 1.9f else size
            val cx = rail.centerX() - if (on) dp(26f) * wake else 0f
            print(c, ch.toString(), cx, cy + sz * 0.35f, sz, (if (on) 0xFFFFAD33.toInt() else 0xFFDCE4EC.toInt()).a(wake), true, Paint.Align.CENTER)
        }
    }

    /** While the index is held, the letter you are on, written large across the shelf. */
    fun bigLetter(c: Canvas, area: RectF, ch: Char, a: Float) {
        if (a < 0.01f) return
        fill(0xFF0D1014.toInt().a(0.55f * a)); r.set(area.centerX() - dp(64f), area.centerY() - dp(64f), area.centerX() + dp(64f), area.centerY() + dp(64f))
        c.drawRoundRect(r, dp(24f), dp(24f), p)
        handwrite(c, ch.toString(), area.centerX(), area.centerY() + dp(30f), dp(96f), dp(200f), 0xFFF2EFE6.toInt().a(a), Paint.Align.CENTER)
    }

    fun shelf(c: Canvas, area: RectF, slots: List<Slot>, lean: FloatArray, header: Boolean, onDoor: Boolean, count: Int, scroll: Float = 0f) {
        val fade = 1 - Deck.conc * 0.75f
        if (onDoor) doorInside(c, area, true) else { fill(0xFF13171C.toInt()); c.drawRoundRect(area, dp(18f), dp(18f), p) }
        c.save(); path.reset(); path.addRoundRect(area, dp(18f), dp(18f), Path.Direction.CW); c.clipPath(path)
        if (header) {
            // The heading scrolls away with the tapes rather than sliding under them
            val pad = dp(18f)
            c.save(); c.translate(0f, -scroll)
            print(c, "THE SHELF", area.left + pad, area.top + dp(30f), dp(13f),
                (if (onDoor) 0xFFB9C8D6.toInt() else 0xFF8D98A5.toInt()).a(fade), true, Paint.Align.LEFT, dp(13f) * 0.24f)
            handwrite(c, "$count ${if (count == 1) "tape" else "tapes"}", area.left + pad, area.top + dp(56f), dp(22f), area.width(), 0xFFC9D1D9.toInt().a(fade))
            c.restore()
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
