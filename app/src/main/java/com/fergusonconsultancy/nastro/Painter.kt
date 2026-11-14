package com.fergusonconsultancy.nastro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
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
    /** The open body's battery bay. */
    var cell: RectF? = null
    /** Upright and open: mm of drive showing beyond each end of the tape (the motor, the gears). */
    var ends = 0f
    var radius = 0f
    /** The player's body: the whole screen (or half) when it runs full-bleed, else its own shape. */
    var body = RectF()
    /**
     * How the tape is turned on screen: 90 stands it upright (portrait), 0 lays it flat
     * (landscape). Either way its open edge faces the hinge, as in a real player.
     */
    var rot = 90f
    /** The size the controls are drawn to: the width in portrait, the controls' column in landscape. */
    var u = w
    /** The raised plate the keys are sunk into (open deck only). */
    var keyPlate: RectF? = null
    val wide get() = rot == 0f

    /** A point in the cassette's own millimetres, on screen. */
    fun at(sx: Float, sy: Float): android.graphics.PointF =
        if (rot == 0f) android.graphics.PointF(tx + sx * k, ty + sy * k) else android.graphics.PointF(tx - sy * k, ty + sx * k)

    companion object {
        const val CW = 100.4f
        const val CH = 63.8f
        private const val REEL_Y = -2f

        /**
         * [x], [y], [w], [h] is the safe area for the parts (clear of the camera and system bars);
         * [bleed], when given, is the body: the whole screen, its corners the phone's own.
         * Wider than tall (the cover screen on its side), the face is laid out sideways.
         */
        fun face(x: Float, y: Float, w: Float, h: Float, bleed: RectF? = null, mm: Float = 0f, anchor: Pair<Float, Float>? = null): PlayerGeom =
            if (w > h * 1.05f) faceWide(x, y, w, h, bleed, mm) else faceTall(x, y, w, h, bleed, mm, anchor)

        /**
         * The cover screen on its side (11 Nov 2026): the lid with its lens takes the left, the
         * tape lying flat behind it; the readout, keys and switches stand in a column on the right.
         */
        private fun faceWide(x: Float, y: Float, w: Float, h: Float, bleed: RectF?, mm: Float): PlayerGeom = PlayerGeom(Kind.FACE, x, w).apply {
            this.y = y; this.h = h
            rot = 0f
            radius = if (bleed != null) 0f else h * 0.12f
            body = bleed?.let { RectF(it) } ?: RectF(x, y, x + w, y + h)
            val pad = h * 0.05f
            // the column's stack is 0.8 of its width tall: readout, keys, switches
            u = minOf(w * 0.4f, (h - pad * 2) / 0.86f)
            val colR = x + w - pad - 0.035f * u
            val colL = colR - u
            val stackH = u * 0.8f
            val top = y + (h - stackH) / 2
            lcd = RectF(colL, top, colR, top + 0.25f * u)
            chan = RectF(colL - 0.015f * u, lcd.bottom + 0.06f * u, colR + 0.015f * u, lcd.bottom + 0.28f * u)
            keys = keyRow(chan)
            val st = RectF(colL, chan.bottom + 0.05f * u, colR, chan.bottom + 0.25f * u)
            strip(st)
            val ld = RectF(x + pad, y + pad, colL - pad * 1.3f, y + h - pad)
            // a very wide screen: keep the lid in proportion and centre it in its share
            val maxW = ld.height() * 2.3f
            if (ld.width() > maxW) { val cx = ld.centerX(); ld.left = cx - maxW / 2; ld.right = cx + maxW / 2 }
            lid = ld
            k = minOf(ld.width() * 0.8f / 74f, ld.height() * 0.62f / 30f).let { if (mm > 0f) min(it, mm) else it }
            val lcx = ld.centerX(); val lcy = ld.top + ld.height() * 0.45f
            val ln = RectF(lcx - 37 * k, lcy - 15 * k, lcx + 37 * k, lcy + 15 * k)
            lens = ln
            tx = lcx; ty = lcy - REEL_Y * k
            win = RectF(ln)
            val wr = bleed?.right ?: (x + w)
            wheel = RectF(wr - 0.03f * u, lcd.top, wr + 0.015f * u, lcd.top + 0.36f * u)
        }

        private fun PlayerGeom.strip(st: RectF) {
            hold = RectF(st.left, st.top + st.height() * 0.18f, st.left + st.width() * 0.26f, st.top + st.height() * 0.52f)
            legends = listOf(
                Legend("megaBass", "MEGA BASS", st.left + st.width() * 0.34f, st.top + st.height() * 0.36f, st.width() * 0.34f, st.height() * 0.4f),
                Legend("autoRev", "AUTO REV", st.left + st.width() * 0.34f, st.top + st.height() * 0.8f, st.width() * 0.34f, st.height() * 0.4f),
            )
            battery = RectF(st.left + st.width() * 0.72f, st.top + st.height() * 0.06f, st.right, st.top + st.height() * 0.94f)
        }

        private fun faceTall(x: Float, y: Float, w: Float, h: Float, bleed: RectF? = null, mm: Float = 0f, anchor: Pair<Float, Float>? = null): PlayerGeom {
            // Too squat for the whole face at this width (a split window): narrow it, centred, so nothing falls off the foot
            if (h < 1.7f * w) { val w2 = h / 1.7f; return faceTallFit(x + (w - w2) / 2, y, w2, h, bleed, mm) }
            return faceTallFit(x, y, w, h, bleed, mm, anchor)
        }

        private fun faceTallFit(x: Float, y: Float, w: Float, h: Float, bleed: RectF? = null, mm: Float = 0f, anchor: Pair<Float, Float>? = null): PlayerGeom = PlayerGeom(Kind.FACE, x, w).apply {
            // 14 Nov 2026: the controls give up a little height so the lid, and the tape behind its lens, can
            // grow to the real thing's size: the hubs 42.5 mm apart, as in your hand
            // 14 Nov 2026, Mechack: the readout up, the keys and switches down, the space between to the lid
            val lidH = (h - 0.75f * w).coerceIn(0.8f * w, if (bleed != null) 2.3f * w else 1.5f * w)
            this.h = 0.75f * w + lidH
            this.y = y + (h - this.h) / 2
            radius = if (bleed != null) 0f else w * 0.12f
            body = bleed?.let { RectF(it) } ?: RectF(x, this.y, x + w, this.y + this.h)
            lcd = RectF(x + 0.07f * w, this.y + 0.015f * w, x + 0.93f * w, this.y + 0.225f * w)
            val ld = RectF(x + 0.045f * w, lcd.bottom + 0.045f * w, x + 0.955f * w, lcd.bottom + 0.045f * w + lidH)
            lid = ld
            k = min(ld.height() * 0.74f / 74f, ld.width() * 0.62f / 30f).let { if (mm > 0f) min(it, mm) else it }
            var lcx = ld.centerX(); var lcy = ld.top + ld.height() * 0.455f
            // 14 Nov 2026, Mechack: the tape behind the lens must line up with the tape in the open
            // compartment. Folded, the door lies over the compartment, the hinge on the left, so the tape is
            // on the same spot: as far from the hinge edge and from the top as it is unfolded. The lens
            // follows it, kept on the lid and clear of the engraving.
            if (anchor != null && bleed != null) {
                lcx = (bleed.left + anchor.first - REEL_Y * k).coerceIn(ld.left + 15 * k + 0.04f * w, ld.right - 15 * k - 0.04f * w)
                lcy = (bleed.top + anchor.second).coerceIn(ld.top + 37 * k + ld.height() * 0.075f, ld.bottom - 37 * k - ld.height() * 0.14f)
            }
            val ln = RectF(lcx - 15 * k, lcy - 37 * k, lcx + 15 * k, lcy + 37 * k)
            lens = ln
            tx = lcx + REEL_Y * k; ty = lcy
            win = RectF(ln)
            chan = RectF(x + 0.055f * w, ld.bottom + 0.05f * w, x + 0.945f * w, ld.bottom + 0.24f * w)
            keys = keyRow(chan)
            val st = RectF(x + 0.07f * w, chan.bottom + 0.035f * w, x + 0.93f * w, chan.bottom + 0.205f * w)
            strip(st)
            // the volume wheel stands proud of the body's edge; full-bleed, it emerges from the screen's edge
            val wr = bleed?.right ?: (x + w)
            wheel = RectF(wr - 0.03f * w, ld.top + ld.height() * 0.08f, wr + 0.015f * w, ld.top + ld.height() * 0.08f + 0.36f * w)
        }

        /**
         * The open body (13 Nov 2026, back to the Walkman): the face is the door, so its readout and
         * keys swing away with it and the body is all compartment, the tape at its foot's battery bay.
         * [mm] is the screen's pixels per millimetre: the tape is drawn at its real size where it fits,
         * so the phone itself becomes the Walkman.
         */
        fun bay(x: Float, y: Float, w: Float, h: Float, bleed: RectF? = null, mm: Float = 0f): PlayerGeom =
            if (w > h * 1.05f) bayWide(x, y, w, h, bleed, mm) else bayTall(x, y, w, h, bleed, mm)

        private fun PlayerGeom.noFace() {
            lcd = RectF(); chan = RectF(); keys = emptyList(); keyPlate = null; legends = emptyList(); hold = null; wheel = null; battery = null
        }

        // 14 Nov 2026, Mechack: the tape looked too small, with dead space round it. A real Walkman's
        // compartment is barely bigger than the cassette, so this one is too: the tape at its real size
        // (the screen's own pixels per millimetre) where it fits, the hole closing round it with the
        // head assembly along its open edge, the AA bay below, the pair centred in the body.
        private const val CLEAR = 1.2f      // mm round the shell
        private const val HEADS = 6.4f      // mm of head plate, pinch roller arm and capstan beyond the shell's foot
        private const val CELL = 22f        // mm: the gumstick cell (16.7 wide) and its clearance
        private const val GAP = 4f          // mm of body between the two holes
        private const val LIP = 2.6f        // mm: each hole's raised lip

        private fun bayTall(x: Float, y: Float, w: Float, h: Float, bleed: RectF?, mm: Float): PlayerGeom = PlayerGeom(Kind.BAY, x, w).apply {
            this.y = y; this.h = h
            noFace()
            radius = if (bleed != null) 0f else w * 0.08f
            body = bleed?.let { RectF(it) } ?: RectF(x, y, x + w, y + h)
            val cwMm = CH + HEADS + CLEAR * 2
            val groupMm = CW + CLEAR * 2 + GAP + LIP * 4 + CELL
            // the width is what limits the tape upright, so the hole uses the body's whole width, edge to edge
            val bw = (bleed?.width() ?: w) * 0.975f
            var kk = min(bw / (cwMm + LIP * 2), (h * 0.97f) / groupMm)
            if (mm > 0f) kk = min(kk, mm)
            k = kk
            // 14 Nov 2026, Mechack: use the space at either end. The tape can't grow (the width holds it),
            // so the hole runs on past its ends and shows what a Walkman hides there: the motor and its
            // belt at one end, the reel gears at the other
            ends = ((h * 0.97f / k - groupMm) / 2f).coerceIn(0f, 26f)
            val chMm = CW + CLEAR * 2 + ends * 2
            // 14 Nov 2026, Mechack: the AA bay down at the foot; the compartment centred in the rest
            val cx = bleed?.centerX() ?: (x + w / 2)
            val cellTop = y + h - (CELL + LIP) * k
            val room = cellTop - (GAP + LIP * 2) * k - y
            val top = y + max(LIP * k, (room - chMm * k) / 2)
            val c = RectF(cx - cwMm * k / 2, top, cx + cwMm * k / 2, top + chMm * k)
            comp = c
            val cl = RectF(c.left, cellTop, c.right, cellTop + CELL * k)
            cell = cl; battery = RectF(cl)
            // the tape's open edge and the heads face the hinge (the crease), as in a real player
            tx = c.left + (CLEAR + HEADS + CH / 2) * k; ty = c.centerY()
            win = RectF(c)
        }

        /**
         * The open body with the phone on its side: the crease runs across, so the body takes the top
         * half, the tape lying flat with its open edge down at the hinge, the battery bay beside it.
         */
        private fun bayWide(x: Float, y: Float, w: Float, h: Float, bleed: RectF?, mm: Float): PlayerGeom = PlayerGeom(Kind.BAY, x, w).apply {
            this.y = y; this.h = h
            rot = 0f
            noFace()
            u = h
            radius = if (bleed != null) 0f else h * 0.08f
            body = bleed?.let { RectF(it) } ?: RectF(x, y, x + w, y + h)
            // lying flat: the tape across, its foot and the heads down at the hinge, the AA bay standing beside it
            val cwMm = CW + CLEAR * 2; val chMm = CH + HEADS + CLEAR * 2
            val groupMm = cwMm + GAP + LIP * 4 + CELL
            var kk = min((w * 0.97f) / groupMm, (h * 0.97f) / (chMm + LIP * 2))
            if (mm > 0f) kk = min(kk, mm)
            k = kk
            val left = x + (w - (groupMm - LIP * 2) * k) / 2
            val cy = y + h / 2
            val c = RectF(left, cy - chMm * k / 2, left + cwMm * k, cy + chMm * k / 2)
            comp = c
            val cx0 = c.right + (GAP + LIP * 2) * k
            val cl = RectF(cx0, c.top, cx0 + CELL * k, c.bottom)
            cell = cl; battery = RectF(cl)
            tx = c.centerX(); ty = c.top + (CLEAR + CH / 2) * k
            win = RectF(c)
        }

        /** The satin plate the keys are sunk into: round-ended, just clear of the play key's bead ring. */
        private fun plateFor(keys: List<KeyR>): RectF {
            val play = keys.maxByOrNull { it.r }!!
            val half = play.r * 1.36f + play.r * 0.2f
            val l = keys.first(); val r = keys.last()
            return RectF(l.cx - l.r * 1.16f - play.r * 0.3f, play.cy - half, r.cx + r.r * 1.16f + play.r * 0.3f, play.cy + half)
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

    internal fun handwrite(c: Canvas, text: String, x: Float, y: Float, size: Float, maxW: Float, color: Int,
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
        // as on the real thing: each pack's tape runs to the guide roller in its corner, round it, and along the open edge
        val gr = Deck.GUIDE_R
        path.moveTo(-RDX - rl * 0.55f, RY + rl * 0.84f); path.lineTo(-38f - gr, 26.9f); path.lineTo(-36.5f, 30.85f); path.lineTo(36.5f, 30.85f)
        path.lineTo(38f + gr, 26.9f); path.lineTo(RDX + rr * 0.55f, RY + rr * 0.84f)
        path.lineTo(RDX + rr * 0.55f - 0.35f, RY + rr * 0.84f - 0.3f); path.lineTo(38f + gr - 0.45f, 26.9f); path.lineTo(36.3f, 30.4f); path.lineTo(-36.3f, 30.4f)
        path.lineTo(-38f - gr + 0.45f, 26.9f); path.lineTo(-RDX - rl * 0.55f + 0.35f, RY + rl * 0.84f - 0.3f); path.close()
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
        val (lx, ly) = if (turn == 90f) Light.y to -Light.x else Light.x to Light.y
        p.shader = LinearGradient(-30f + 22f * lx, -12f + 12f * ly, 10f + 22f * lx, 10f + 12f * ly, intArrayOf(0x29FFFFFF, 0x0AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
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
        fill(0xFF0A0A0A.toInt()); c.drawCircle(-13.6f, 29.3f, 1.15f, p); c.drawCircle(13.6f, 29.3f, 1.15f, p)
        if (live) transport(c, sh)
        s.strokeWidth = 0.5f; s.color = 0x24FFFFFF; c.drawPath(outline, s)
    }

    /** A tape lifted off the shelf in the hand: its place shows its open case. */
    var carried: Tape? = null

    /** How the tape being drawn is turned on screen, so its glazing follows the light. */
    private var turn = 0f

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

    /**
     * Heads, pinch roller, capstan and guides, in the cassette's millimetres (foot at +y). They
     * move with the transport (Deck.engage): on play the head slides up into the cassette's head
     * opening and the pinch roller closes on the capstan; on stop and while winding both fall back.
     */
    // 14 Nov 2026, Mechack: more of the mechanism, and its relationship to the tape: reels, guides,
    // roller. Drawn now in the shell's own millimetres (its foot edge at +31.9), so every part meets
    // the tape where a real one does: the head's face in the head opening, the capstan up through the
    // shell's capstan hole behind the tape, the pinch roller coming in through its notch to press the
    // tape against it, swung on a sprung arm; the head plate screwed down, its flex running away.
    fun mechanism(c: Canvas) {
        fun steel(x: Float, y: Float, rad: Float) {
            p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, 0xFFF2F4F6.toInt(), 0xFF7D848C.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p); p.shader = null
        }
        fun screwMm(x: Float, y: Float, rad: Float) {
            p.shader = RadialGradient(x - rad * 0.35f, y - rad * 0.4f, rad * 1.4f, intArrayOf(0xFFF1F3F5.toInt(), 0xFFA8AEB5.toInt(), 0xFF5D646C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p); p.shader = null
            s.strokeWidth = rad * 0.28f; s.color = 0xFF2A2E33.toInt(); c.drawLine(x - rad * 0.6f, y, x + rad * 0.6f, y, s); c.drawLine(x, y - rad * 0.6f, x, y + rad * 0.6f, s)
        }
        val e = Deck.engage
        val back = 1.7f * (1 - e)            // the head's travel, mm
        // The flex: the head's ribbon cable, amber with its copper traces, curling away to the board
        path.reset(); path.moveTo(-1.6f, 36f + back); path.lineTo(-1.6f, 38.2f); path.quadTo(-1.6f, 40f, -4f, 40f); path.lineTo(-16f, 40f)
        path.lineTo(-16f, 37.8f); path.lineTo(-4.4f, 37.8f); path.quadTo(1.6f, 37.8f, 1.6f, 36f + back); path.close()
        fill(0xFFB37A22.toInt()); p.setShadowLayer(0.6f, 0.2f, 0.4f, 0x80000000.toInt()); c.drawPath(path, p); p.clearShadowLayer()
        s.strokeWidth = 0.18f; s.color = 0xFFE2B15A.toInt()
        for (o in floatArrayOf(-0.8f, 0f, 0.8f)) { c.drawLine(-4.5f, 38.9f + o * 0.9f, -15.5f, 38.9f + o * 0.9f, s) }
        // The head plate, sliding in and out with the head
        p.shader = LinearGradient(0f, 33.2f + back, 0f, 37.4f + back, intArrayOf(0xFF6A737D.toInt(), 0xFF454D57.toInt(), 0xFF2E343B.toInt()), null, Shader.TileMode.CLAMP)
        p.setShadowLayer(0.9f, 0.3f, 0.6f, 0x99000000.toInt())
        r.set(-24f, 33.2f + back, 24f, 37.4f + back); c.drawRoundRect(r, 1.2f, 1.2f, p); p.clearShadowLayer(); p.shader = null
        s.strokeWidth = 0.2f; s.color = 0x33FFFFFF; c.drawLine(-23f, 33.45f + back, 23f, 33.45f + back, s)
        // slots it slides on, and its screws
        for (sx in floatArrayOf(-19f, 19f)) { fill(0xFF15181C.toInt()); r.set(sx - 2.2f, 35.6f + back - 0.45f, sx + 2.2f, 35.6f + back + 0.45f); c.drawRoundRect(r, 0.45f, 0.45f, p) }
        screwMm(-19f, 35.6f, 0.75f); screwMm(19f, 35.6f, 0.75f)
        // the head: chrome, its gap, on its azimuth screw with a coil under it
        p.shader = LinearGradient(-5f, 0f, 5f, 0f, intArrayOf(0xFF9AA1A8.toInt(), 0xFFF1F3F5.toInt(), 0xFF8D949B.toInt()), null, Shader.TileMode.CLAMP)
        p.setShadowLayer(0.5f, 0.2f, 0.4f, 0x99000000.toInt())
        r.set(-5f, 30.85f + back, 5f, 35.2f + back); c.drawRoundRect(r, 1.4f, 1.4f, p); p.clearShadowLayer(); p.shader = null
        fill(0xFF2B2F34.toInt()); c.drawRect(-0.15f, 30.85f + back, 0.15f, 32.2f + back, p)
        s.strokeWidth = 0.25f; s.color = 0xFF9AA1A8.toInt(); c.drawCircle(7f, 35.3f + back, 1.1f, s); c.drawCircle(7f, 35.3f + back, 0.75f, s)
        screwMm(7f, 35.3f + back, 0.6f)
        // the head's tape guides: forks either side that keep the tape at its height
        for (gx in floatArrayOf(-6.6f, 6.6f)) { fill(0xFF8D949B.toInt()); r.set(gx - 0.55f, 30.9f + back, gx + 0.55f, 33.6f + back); c.drawRect(r, p); fill(0xFFC9CED4.toInt()); r.set(gx - 0.9f, 30.75f + back, gx + 0.9f, 31.1f + back); c.drawRect(r, p) }
        // Pinch roller on its arm: the arm swings on a pivot screw, a spring pulling it in
        val off = 1.4f * (1 - e)
        val px = 13.6f + off * 0.5f; val py = 33.8f + off
        val pvx = 22.5f; val pvy = 38.6f
        s.strokeCap = Paint.Cap.ROUND
        s.strokeWidth = 2.6f; s.color = 0xFF2E343B.toInt(); c.drawLine(px, py, pvx, pvy, s)
        s.strokeWidth = 2.1f; s.shader = LinearGradient(px, py - 1f, px, py + 1f, 0xFF8E969F.toInt(), 0xFF555D66.toInt(), Shader.TileMode.CLAMP); c.drawLine(px, py, pvx, pvy, s); s.shader = null
        s.strokeCap = Paint.Cap.BUTT
        // the spring: from the arm to a post on the chassis
        val ax = px + (pvx - px) * 0.55f; val ay = py + (pvy - py) * 0.55f
        val sx0 = 28.5f; val sy0 = 35.2f
        s.strokeWidth = 0.22f; s.color = 0xFFC9CED4.toInt()
        path.reset(); path.moveTo(ax, ay)
        for (i in 1..9) { val t = i / 10f; val nx = -(sy0 - ay); val ny = sx0 - ax; val nl = kotlin.math.hypot(nx, ny); val w = if (i % 2 == 0) 0.55f else -0.55f
            path.lineTo(ax + (sx0 - ax) * t + nx / nl * w, ay + (sy0 - ay) * t + ny / nl * w) }
        path.lineTo(sx0, sy0); c.drawPath(path, s)
        steel(sx0, sy0, 0.6f)
        screwMm(pvx, pvy, 1.0f)
        // the roller: black rubber on a brass hub
        fill(0xFF121314.toInt()); p.setShadowLayer(0.7f, 0.25f, 0.5f, 0x99000000.toInt()); c.drawCircle(px, py, 2.9f, p); p.clearShadowLayer()
        s.strokeWidth = 0.4f; s.color = 0x29FFFFFF; r.set(px - 2.2f, py - 2.2f, px + 2.2f, py + 2.2f); c.drawArc(r, 180f, 108f, false, s)
        spokes(c, px, py, 2.1f, Deck.pinchAng, 3, 0x40FFFFFF)
        fill(0xFFB08A4E.toInt()); c.drawCircle(px, py, 0.8f, p)
        // The deck's tape guides, posts at either end of the head plate
        steel(-28.5f, 32.8f + back * 0.4f, 1.0f); steel(28.5f, 32.8f + back * 0.4f, 1.0f)
    }

    /**
     * Marks on a turning part, so its turning is seen. Concentrato: the faster it spins, the more
     * the marks blur away, until a fast wind shows only the smooth part (no strobing spokes).
     */
    private fun spokes(c: Canvas, x: Float, y: Float, rad: Float, ang: Float, n: Int, col: Int) {
        val speed = kotlin.math.abs(Deck.tapeMmS) / rad
        val clear = 1f - ((speed - 40f) / 160f).coerceIn(0f, 1f)
        if (clear <= 0.02f) return
        s.strokeWidth = rad * 0.22f; s.color = col.a(clear * ((col ushr 24) / 255f)); s.strokeCap = Paint.Cap.ROUND
        for (i in 0 until n) {
            val a = ang + i * (2 * PI / n).toFloat()
            c.drawLine(x + cos(a) * rad * 0.25f, y + sin(a) * rad * 0.25f, x + cos(a) * rad * 0.85f, y + sin(a) * rad * 0.85f, s)
        }
        s.strokeCap = Paint.Cap.BUTT
    }

    /**
     * In the cassette's foot: its two tape guides (white rollers in the corners) turning with the
     * tape, and at the head opening the tape itself with the pressure pad on its spring behind it.
     * As the head comes in it presses the tape back into the pad and the spring flexes.
     */
    private fun transport(c: Canvas, sh: Shell) {
        val e = Deck.engage
        // The pinch roller notches either side, the tape running across them; on the deck's side the
        // capstan stands up behind the tape and the roller comes in to press it there
        for (side in floatArrayOf(-1f, 1f)) {
            val nx0 = side * 10.6f; val nx1 = side * 16.8f
            r.set(min(nx0, nx1), 30.0f, max(nx0, nx1), CH / 2 + 0.2f)
            fill(0xFF0A0A0B.toInt()); c.drawRoundRect(r, 0.5f, 0.5f, p)
            c.save(); c.clipRect(r)
            fill(0xFF3A281E.toInt()); c.drawRect(r.left, 30.4f, r.right, 30.85f, p)
            if (side > 0) {
                val off = 1.4f * (1 - e)
                val px = 13.6f + off * 0.5f; val py = 33.8f + off
                fill(0xFF121314.toInt()); c.drawCircle(px, py, 2.9f, p)
                spokes(c, px, py, 2.1f, Deck.pinchAng, 3, 0x40FFFFFF)
            }
            c.restore()
        }
        // the deck's capstan up through the shell's hole, turning when it drives
        p.shader = RadialGradient(13.3f, 29.0f, 1.1f, 0xFFF2F4F6.toInt(), 0xFF7D848C.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(13.6f, 29.3f, 0.85f, p); p.shader = null
        if (e > 0.6f) spokes(c, 13.6f, 29.3f, 0.7f, Deck.capstanAng, 1, 0x99505860.toInt())
        // Tape guides
        for (gx in floatArrayOf(-38f, 38f)) {
            val gy = 26.9f
            p.shader = RadialGradient(gx - 0.4f, gy - 0.4f, 1.9f, 0xFFF4F4F2.toInt(), 0xFFB9BCBF.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(gx, gy, Deck.GUIDE_R, p); p.shader = null
            s.strokeWidth = 0.18f; s.color = 0x59000000; c.drawCircle(gx, gy, Deck.GUIDE_R, s)
            spokes(c, gx, gy, Deck.GUIDE_R * 0.95f, Deck.guideAng, 3, 0x8C5A5E63.toInt())
            fill(0xFF6E7378.toInt()); c.drawCircle(gx, gy, 0.35f, p)
        }
        // The head opening, cut into the foot edge below the centre screw (screw ends at 28.6 mm)
        val top = 28.9f; val bottom = CH / 2 + 0.2f
        fill(0xFF0A0A0B.toInt()); r.set(-6.5f, top, 6.5f, bottom); c.drawRoundRect(r, 0.6f, 0.6f, p)
        c.save(); r.set(-6.5f, top, 6.5f, bottom); c.clipRect(r)
        val push = 0.35f * e
        // Pressure pad spring: a bronze leaf anchored at its ends, flexing as the pad is pushed
        s.strokeWidth = 0.28f; s.color = 0xFFB08A4E.toInt()
        path.reset(); path.moveTo(-5.4f, 29.15f); path.quadTo(0f, 29.55f - push * 1.6f, 5.4f, 29.15f); c.drawPath(path, s)
        // The pad: felt, pressed back by the tape
        fill(0xFF6C6F68.toInt()); r.set(-2.1f, 29.75f - push, 2.1f, 30.35f - push); c.drawRoundRect(r, 0.2f, 0.2f, p)
        // The tape across the opening: straight while the head is back, pressed into the pad by it
        fill(0xFF3A281E.toInt())
        path.reset()
        path.moveTo(-6.5f, 30.4f); path.lineTo(-3f, 30.4f - push); path.lineTo(3f, 30.4f - push); path.lineTo(6.5f, 30.4f)
        path.lineTo(6.5f, 30.85f); path.lineTo(3f, 30.85f - push); path.lineTo(-3f, 30.85f - push); path.lineTo(-6.5f, 30.85f); path.close()
        c.drawPath(path, p)
        // The head's face, seen in the opening as it arrives and presses the tape into the pad
        val face = 30.85f - push + 1.7f * (1 - e)
        if (face < bottom) {
            p.shader = LinearGradient(-4f, 0f, 4f, 0f, intArrayOf(0xFF9AA1A8.toInt(), 0xFFF1F3F5.toInt(), 0xFF8D949B.toInt()), null, Shader.TileMode.CLAMP)
            r.set(-4f, face, 4f, bottom + 2f); c.drawRoundRect(r, 0.9f, 0.9f, p); p.shader = null
            fill(0xFF2B2F34.toInt()); c.drawRect(-0.12f, face, 0.12f, face + 1.2f, p)
        }
        c.restore()
    }

    // ---- the player ----------------------------------------------------------------------------------
    /** What the stage knows that the painter needs: the touch, the reach, the volume. */
    class Live(
        var pressed: Deck.Key? = null, var pressAge: Long = 9999, var proximity: Map<String, Float> = emptyMap(),
        var lifting: Boolean = false, var volume: Float = 0.7f, var volAge: Long = 9999,
        /** The open body's AA springing up for the settings: 0 seated, 1 up. */
        var cellPop: Float = 0f,
    )

    /**
     * Visconti is the default: Alfa Romeo's green as the Fold apps use it (an approximation; Alfa
     * publishes no value), as brushed anodised metal with champagne engraving, keys and coachline.
     * Light blue and grey were the WM-EX610's own; graphite and rosso are ours.
     * [aniso]: the brushing stretches the highlight into a streak across the grooves.
     */
    class Finish(val stops: IntArray, val lid: IntArray, val key: IntArray, val ink: Int, val deep: Int, val chan: Int,
                 val etchLight: Boolean = false, val sym: Int = 0xB81E242C.toInt(), val lacquer: Boolean = false,
                 val inside: IntArray = intArrayOf(0xFF45586C.toInt(), 0xFF2F3D4C.toInt()),
                 val aniso: Boolean = false, val coach: Boolean = false)

    private fun col(v: Number) = v.toInt()
    val finishes = linkedMapOf(
        "visconti" to Finish(intArrayOf(col(0xFF0D6A3C), col(0xFF043F25), col(0xFF01120A)), intArrayOf(col(0xFF0F7543), col(0xFF054528)),
            intArrayOf(col(0xFFF1E6CD), col(0xFFB49A6A)), col(0xEBD9BB86), col(0x8C000000), col(0xFF03150D), true, col(0xD9463216), false,
            intArrayOf(col(0xFF123A28), col(0xFF06180F)), aniso = true, coach = true),
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
    private fun metal(c: Canvas, R: RectF, rad: Float, stops: IntArray, vertical: Boolean, lacquer: Boolean = false, aniso: Boolean = false) {
        if (lacquer) {
            // clear-coated lacquer: deep colour darkening towards the edges, and one broad reflection
            p.shader = RadialGradient(R.left + R.width() * 0.35f, R.top + R.height() * 0.3f, max(R.width(), R.height()) * 0.85f, stops, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(R, rad, rad, p)
            val d = Light.x * 0.16f + Light.y * 0.08f
            p.shader = LinearGradient(R.left, R.top, R.right, R.top + R.height() * 0.6f,
                intArrayOf(0, 0, 0x33E1FFEC, 0x42F0FFF4, 0, 0), floatArrayOf(0f, 0.24f + d, 0.33f + d, 0.35f + d, 0.45f + d, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(R, rad, rad, p); p.shader = null
            return
        }
        p.shader = LinearGradient(R.left, R.top, R.left + R.width() * 0.7f, R.bottom, stops, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(R, rad, rad, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(R, rad, rad, Path.Direction.CW); c.clipPath(path)
        val bm = brush(max(R.width(), R.height()).toInt() + 2)
        p.alpha = if (aniso) 242 else 190
        if (vertical) { c.save(); c.translate(R.right, R.top); c.rotate(90f); c.drawBitmap(bm, Rect(0, 0, R.height().toInt() + 1, R.width().toInt() + 1), RectF(0f, 0f, R.height(), R.width()), p); c.restore() }
        else c.drawBitmap(bm, Rect(0, 0, R.width().toInt() + 1, R.height().toInt() + 1), R, p)
        p.alpha = 255
        if (aniso) {
            // brushed metal stretches its highlight into a soft streak across the grooves
            // it slides across the grooves as the phone tilts (14 Nov 2026)
            val d = (if (vertical) Light.y else Light.x) * 0.18f
            val glint = (0x33 + (kotlin.math.abs(d) * 90).toInt()).coerceAtMost(0x45) shl 24
            val streak = intArrayOf(0x00E6FFF0, 0x00E6FFF0, glint or 0xE6FFF0, 0x00E6FFF0, 0x00E6FFF0)
            val stops = floatArrayOf(0f, 0.2f + d, 0.3f + d, 0.4f + d, 1f)
            p.shader = if (vertical) LinearGradient(0f, R.top, 0f, R.bottom, streak, stops, Shader.TileMode.CLAMP)
            else LinearGradient(R.left, 0f, R.right, 0f, streak, stops, Shader.TileMode.CLAMP)
            c.drawRect(R, p); p.shader = null
        }
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
        val body = G.body
        val R = G.radius; val w = G.w
        fill(F.stops[2]); if (R > 0) p.setShadowLayer(dp(24f), 0f, dp(10f), 0x99000000.toInt()); c.drawRoundRect(body, R, R, p); p.clearShadowLayer()
        G.wheel?.let { wheel(c, it, live.volume) }
        metal(c, body, R, F.stops, false, F.lacquer, F.aniso)
        if (!F.lacquer && !F.aniso) {
            // one highlight over the shoulder (lacquer and anisotropic brushing carry their own)
            c.save(); path.reset(); path.addRoundRect(body, R, R, Path.Direction.CW); c.clipPath(path)
            p.shader = RadialGradient(body.left + w * (0.18f + 0.3f * Light.x), body.top + G.h * (0.02f + 0.12f * Light.y), w * 1.1f, Color.WHITE.a(0.32f), 0, Shader.TileMode.CLAMP)
            c.drawRect(body, p); p.shader = null
            c.restore()
        }
        bevel(c, body, R)
        G.wheel?.let { fill(Color.BLACK.a(0.35f)); r.set(it.left - dp(0.6f), it.top - dp(1.2f), it.left + it.width() * 0.5f, it.bottom + dp(1.2f)); c.drawRoundRect(r, dp(2f), dp(2f), p) }

        lcd(c, G.lcd, detail, live)

        // the lid: the door you open by unfolding, brushed the other way, with a seam
        val Ld = G.lid ?: return
        val lr = min(Ld.width(), Ld.height()) * 0.079f
        fill(F.lid[1]); p.setShadowLayer(dp(3f), 0f, dp(1f), 0x47000000); c.drawRoundRect(Ld, lr, lr, p); p.clearShadowLayer()
        metal(c, Ld, lr, intArrayOf(F.lid[0], F.lid[1], if (F.lacquer || F.aniso) F.stops[2] else F.stops[1]), true, F.lacquer, F.aniso)
        if (!F.lacquer && !F.aniso) {
            c.save(); path.reset(); path.addRoundRect(Ld, lr, lr, Path.Direction.CW); c.clipPath(path)
            p.shader = RadialGradient(Ld.left + Ld.width() * (0.4f + 0.3f * Light.x), Ld.top + Ld.height() * (0.32f + 0.2f * Light.y), Ld.height() * 0.85f,
                intArrayOf(Color.WHITE.a(0.22f), 0, Color.BLACK.a(0.14f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(Ld, p); p.shader = null
            c.restore()
        }
        s.strokeWidth = dp(1f); s.color = Color.BLACK.a(0.42f); r.set(Ld.left - dp(0.5f), Ld.top - dp(0.5f), Ld.right + dp(0.5f), Ld.bottom + dp(0.5f)); c.drawRoundRect(r, lr, lr, s)
        // the seam's lit edge; on lacquer, a champagne coachline
        s.strokeWidth = dp(0.6f); s.color = if (F.coach) 0x8CD9BB86.toInt() else Color.WHITE.a(0.5f); r.set(Ld.left + dp(0.4f), Ld.top + dp(0.4f), Ld.right - dp(0.4f), Ld.bottom - dp(0.4f)); c.drawRoundRect(r, lr, lr, s)

        lens(c, G, detail, live.lifting)

        val fs = max(dp(9f), G.u * 0.034f)
        engrave(c, "AUTO REVERSE", Ld.right - Ld.width() * 0.07f, Ld.top + Ld.height() * (if (G.wide) 0.075f else 0.06f), fs, F, Paint.Align.RIGHT, 0.2f)
        // the wordmark, deep-cut
        engrave(c, "NASTRO", Ld.centerX(), Ld.bottom - Ld.height() * 0.075f, min(G.u * 0.11f, dp(44f)), F, Paint.Align.CENTER, 0.42f)

        keys(c, G, F, live)
        strip(c, G, F, detail, live)
    }

    private fun lens(c: Canvas, G: PlayerGeom, detail: Float, lifting: Boolean) {
        val L = G.lens ?: return
        val rad = min(L.width(), L.height()) / 2
        r.set(L.left - dp(2f), L.top - dp(2f), L.right + dp(2f), L.bottom + dp(2f))
        p.shader = LinearGradient(L.left, L.top, L.right, L.bottom, intArrayOf(0xFFF4F6F8.toInt(), 0xFF8B939C.toInt(), 0xFFD9DEE3.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad + dp(2f), rad + dp(2f), p); p.shader = null
        // 14 Nov 2026, Mechack: one tape, seen two ways. Behind the lens is the open compartment exactly as
        // unfolding shows it: the same floor, the tape on the same spindles, through clear glass
        p.shader = LinearGradient(L.left, L.top, L.right, L.bottom, 0xFF3A424B.toInt(), 0xFF1D2329.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(L, rad, rad, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(L, rad, rad, Path.Direction.CW); c.clipPath(path)
        val tape = Deck.tape
        if (tape != null && !lifting) {
            c.save(); c.translate(G.tx, G.ty)
            val fl = if (Deck.flipping) cos(Deck.flip * PI).toFloat() else 1f
            c.rotate(G.rot); c.scale(G.k, G.k * max(0.02f, kotlin.math.abs(fl)))
            val facing = if (Deck.flipping && Deck.flip > 0.5f) (if (Deck.facing == 'A') 'B' else 'A') else Deck.facing
            turn = G.rot; cassette(c, tape, true, detail, facing, spindle = true, shadow = 5f); turn = 0f
            c.restore()
        }
        fill(0x1A101822); c.drawRect(L, p)
        // the glass's glare slides as the phone tilts
        val gd = (if (G.wide) Light.y else Light.x) * 0.2f
        p.shader = if (G.wide) LinearGradient(0f, L.top, 0f, L.bottom, intArrayOf(0, Color.WHITE.a(0.2f), 0), floatArrayOf(0f, 0.22f + gd, 0.34f + gd), Shader.TileMode.CLAMP)
            else LinearGradient(L.left, 0f, L.right, 0f, intArrayOf(0, Color.WHITE.a(0.2f), 0), floatArrayOf(0f, 0.22f + gd, 0.34f + gd), Shader.TileMode.CLAMP)
        c.drawRect(L, p)
        p.shader = RadialGradient(L.centerX(), L.centerY(), max(L.width(), L.height()) * 0.6f, intArrayOf(0, Color.BLACK.a(0.22f)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP); c.drawRect(L, p)
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
        val fs = max(dp(9f), G.u * 0.032f)
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
        engrave(c, "1.2V NI-MH", B.centerX(), B.top + B.height() * 0.86f, fs * 0.82f, F, Paint.Align.CENTER, 0.12f)
    }

    // ---- the open compartment: the tape in all its glory --------------------------------------------
    // 11 Nov 2026, Mechack: the open deck read flat, a "paper copy" round a beautiful tape. A real
    // compartment is a hole in a machine: the hole is cut through a raised, chamfered lip; its walls
    // fall away, dark where they face from the light and lit where they face it; the floor is a pressed
    // steel chassis with its screws; the cassette stands on its spindles above it and casts a shadow.
    // The keys sit in a satin plate screwed to the body, and the body darkens towards its edges as a
    // curved, lit object does.
    fun bay(c: Canvas, G: PlayerGeom, live: Live) {
        val F = finish()
        val detail = 1 - Deck.conc
        val body = G.body
        val R = G.radius
        val u = G.u
        fill(F.stops[2]); if (R > 0) p.setShadowLayer(dp(20f), 0f, dp(9f), 0x99000000.toInt()); c.drawRoundRect(body, R, R, p); p.clearShadowLayer()
        metal(c, body, R, F.stops, false, F.lacquer, F.aniso)
        falloff(c, body, R)
        bevel(c, body, R)
        hingeEdge(c, G)

        // The readout and keys are on the face, which is the door: open, they have swung away with it

        val C = G.comp ?: return
        // in the tape's millimetres, now the hole is cut to the tape (14 Nov 2026)
        val cr = 3.2f * G.k
        val lip = 2.6f * G.k
        // 1 the lip: a raised frame of the lid's metal, chamfered down into the hole
        val O = RectF(C.left - lip, C.top - lip, C.right + lip, C.bottom + lip)
        raisedPlate(c, O, cr + lip, F, !G.wide)
        for ((sx, sy) in listOf(O.left + lip * 0.5f to O.top + lip * 0.5f, O.right - lip * 0.5f to O.top + lip * 0.5f,
            O.left + lip * 0.5f to O.bottom - lip * 0.5f, O.right - lip * 0.5f to O.bottom - lip * 0.5f)) screw(c, sx, sy, lip * 0.3f, (sx * 7 + sy * 3) % 90f)

        // 2 the hole: four walls between the lip and the floor
        val d = lip * 1.1f
        val Fl = RectF(C.left + d, C.top + d * 1.15f, C.right - d, C.bottom - d * 0.85f)
        c.save(); path.reset(); path.addRoundRect(C, cr, cr, Path.Direction.CW); c.clipPath(path)
        val wall = 0xFF2B323A.toInt()
        fun quad(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float, col: Int) {
            path.reset(); path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.lineTo(dx, dy); path.close(); fill(col); c.drawPath(path, p)
        }
        quad(C.left, C.top, C.right, C.top, Fl.right, Fl.top, Fl.left, Fl.top, darken(wall, 0.72f))            // faces down, away from the light
        quad(C.left, C.top, Fl.left, Fl.top, Fl.left, Fl.bottom, C.left, C.bottom, darken(wall, 0.55f))         // faces right
        quad(C.right, C.top, C.right, C.bottom, Fl.right, Fl.bottom, Fl.right, Fl.top, lighten(wall, 0.06f))    // faces left, towards the light
        quad(C.left, C.bottom, Fl.left, Fl.bottom, Fl.right, Fl.bottom, C.right, C.bottom, lighten(wall, 0.2f)) // faces up, catching it

        // 3 the floor: pressed steel, with the chassis plate the mechanism is built on
        val fr = cr * 0.6f
        c.save(); path.reset(); path.addRoundRect(Fl, fr, fr, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(Fl.left, Fl.top, Fl.right, Fl.bottom, 0xFF3A424B.toInt(), 0xFF1D2329.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(Fl, p)
        p.shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); p.alpha = 70
        c.drawRect(Fl, p); p.alpha = 255; p.shader = null
        drive(c, G)
        chassis(c, G)
        // the deck's ambient darkness gathering towards the corners of the hole
        p.shader = RadialGradient(Fl.centerX(), Fl.centerY(), max(Fl.width(), Fl.height()) * 0.72f, intArrayOf(0, 0, Color.BLACK.a(0.4f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(Fl, p)
        // the lip's shadow falls into the hole from above and from the left
        p.shader = LinearGradient(0f, Fl.top, 0f, Fl.top + d * 2.6f, Color.BLACK.a(0.62f), 0, Shader.TileMode.CLAMP); c.drawRect(Fl, p)
        p.shader = LinearGradient(Fl.left, 0f, Fl.left + d * 2f, 0f, Color.BLACK.a(0.45f), 0, Shader.TileMode.CLAMP); c.drawRect(Fl, p)
        p.shader = null
        c.restore()

        // 4 the mechanism and the tape on its spindles
        val m = G.at(0f, 0f)
        c.save(); c.translate(m.x, m.y); c.rotate(G.rot); c.scale(G.k, G.k); mechanism(c); c.restore()
        val tape = Deck.tape
        if (tape != null) {
            c.save(); c.translate(G.tx, G.ty)
            val fl = if (Deck.flipping) cos(Deck.flip * PI).toFloat() else 1f
            c.rotate(G.rot); c.scale(G.k, G.k * max(0.02f, kotlin.math.abs(fl)))
            val facing = if (Deck.flipping && Deck.flip > 0.5f) (if (Deck.facing == 'A') 'B' else 'A') else Deck.facing
            turn = G.rot; cassette(c, tape, true, detail, facing, spindle = true, shadow = 5f); turn = 0f
            c.restore()
        }
        c.restore()
        // the chamfer where the lip turns down into the hole: lit on the far (lower) edge, dark on the near
        s.strokeWidth = dp(1.6f)
        s.shader = LinearGradient(0f, C.top, 0f, C.bottom, intArrayOf(Color.BLACK.a(0.7f), Color.BLACK.a(0.2f), Color.WHITE.a(0.55f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        r.set(C.left - dp(0.8f), C.top - dp(0.8f), C.right + dp(0.8f), C.bottom + dp(0.8f)); c.drawRoundRect(r, cr, cr, s)
        s.shader = null

        // 5 the battery bay at the foot, its AA in place
        G.cell?.let { cellBay(c, G, it, F, live.cellPop) }
    }

    /**
     * The battery bay (13 Nov 2026): one AA, matching the face's 1.5V AA battery door. A smaller hole through
     * the same lip, its contacts at either end (a coil spring at the negative, a plate at the
     * positive) and the cell lying between them, drawn to the tape's scale.
     */
    private fun cellBay(c: Canvas, G: PlayerGeom, B: RectF, F: Finish, pop: Float = 0f) {
        val lip = 2.6f * G.k
        val cr = 3f * G.k
        val O = RectF(B.left - lip, B.top - lip, B.right + lip, B.bottom + lip)
        raisedPlate(c, O, cr + lip, F, !G.wide)
        // the hole
        p.shader = LinearGradient(B.left, B.top, B.right, B.bottom, 0xFF1A1F25.toInt(), 0xFF0A0D10.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(B, cr, cr, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(B, cr, cr, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(0f, B.top, 0f, B.top + lip * 2.4f, Color.BLACK.a(0.7f), 0, Shader.TileMode.CLAMP); c.drawRect(B, p)
        p.shader = LinearGradient(B.left, 0f, B.left + lip * 2f, 0f, Color.BLACK.a(0.5f), 0, Shader.TileMode.CLAMP); c.drawRect(B, p); p.shader = null
        // the cell, in millimetres, lying along the bay
        val k = G.k
        c.save(); c.translate(B.centerX(), B.centerY()); if (!G.wide) c.rotate(0f) else c.rotate(90f); c.scale(k, k)
        // 14 Nov 2026, Mechack: the Walkman's own battery, the flat "gumstick" NiMH (about 67 × 16.7 × 6 mm)
        val L = 66.8f; val D = 16.7f
        // contacts: a coil spring at the negative end, a plate at the positive
        s.shader = null; s.strokeWidth = 0.7f; s.color = 0xFFC9CED4.toInt()
        val sx0 = -L / 2 - 2.6f
        path.reset(); path.moveTo(sx0, -D * 0.22f)
        for (i in 0..5) { val xx = sx0 + i * 0.42f; path.lineTo(xx + 0.21f, if (i % 2 == 0) D * 0.22f else -D * 0.22f) }
        c.drawPath(path, s)
        fill(0xFFB9BEC4.toInt()); r.set(L / 2 + 0.2f, -D * 0.25f, L / 2 + 1.2f, D * 0.25f); c.drawRect(r, p)
        // its shadow on the floor, spreading and falling away as the spring lifts it
        fill(Color.BLACK.a(0.5f - 0.15f * pop)); p.setShadowLayer(2.2f + 3.5f * pop, 0.6f + 1.4f * pop, 1.2f + 2.6f * pop, Color.BLACK.a(0.8f - 0.2f * pop))
        r.set(-L / 2, -D / 2, L / 2, D / 2); c.drawRoundRect(r, 1.5f, 1.5f, p); p.clearShadowLayer()
        // risen: nearer the eye, so a little larger and up the screen
        c.translate(-0.8f * pop, -1.6f * pop); c.scale(1f + 0.06f * pop, 1f + 0.06f * pop)
        // the pack: flat, its long edges rounded over, in the lime green these packs wear, lit along its upper edge
        p.shader = LinearGradient(0f, -D / 2, 0f, D / 2, intArrayOf(0xFFC6EC7A.toInt(), 0xFF9BD14B.toInt(), 0xFF86BE3A.toInt(), 0xFF4F7A1C.toInt()), floatArrayOf(0f, 0.18f, 0.8f, 1f), Shader.TileMode.CLAMP)
        r.set(-L / 2, -D / 2, L / 2, D / 2); c.drawRoundRect(r, 2.2f, 2.2f, p); p.shader = null
        // the white label across most of its face, with its printing
        fill(0xFFF4F5F1.toInt()); r.set(-L / 2 + 6.5f, -D / 2 + 2f, L / 2 - 5.5f, D / 2 - 2f); c.drawRoundRect(r, 0.6f, 0.6f, p)
        s.strokeWidth = 0.18f; s.color = 0x22000000; c.drawRoundRect(r, 0.6f, 0.6f, s)
        print(c, "+", -L / 2 + 9f, 1.4f, 4.4f, 0xFF1E1E1E.toInt(), true, Paint.Align.CENTER, 0f)
        print(c, "Ni-MH", -L / 2 + 17.5f, -0.6f, 2.6f, 0xFF1E1E1E.toInt(), true, Paint.Align.CENTER, 0.2f)
        print(c, "1.2V  1400mAh", -L / 2 + 17.5f, 2.6f, 2f, 0xFF1E1E1E.toInt(), false, Paint.Align.CENTER, 0.1f)
        fill(0x55000000)
        for (i in 0 until 4) c.drawRect(-2f, -3.6f + i * 1.6f, 14f - (i % 2) * 3f, -3.2f + i * 1.6f, p)
        fill(0xFF1E1E1E.toInt()); c.drawRect(L / 2 - 8.5f, -2.4f, L / 2 - 8f, 2.4f, p)   // the "−" bar, as printed on the real thing
        // the metal contacts at each end
        p.shader = LinearGradient(0f, -3f, 0f, 3f, intArrayOf(0xFFF4F5F6.toInt(), 0xFF8D949B.toInt(), 0xFF4D5258.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        r.set(-L / 2 - 0.2f, -D * 0.26f, -L / 2 + 0.9f, D * 0.26f); c.drawRoundRect(r, 0.3f, 0.3f, p)
        r.set(L / 2 - 0.9f, -D * 0.2f, L / 2 + 0.2f, D * 0.2f); c.drawRoundRect(r, 0.3f, 0.3f, p); p.shader = null
        c.restore()
        c.restore()
        s.strokeWidth = dp(1.4f)
        s.shader = LinearGradient(0f, B.top, 0f, B.bottom, intArrayOf(Color.BLACK.a(0.7f), Color.BLACK.a(0.2f), Color.WHITE.a(0.5f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        r.set(B.left - dp(0.7f), B.top - dp(0.7f), B.right + dp(0.7f), B.bottom + dp(0.7f)); c.drawRoundRect(r, cr, cr, s)
        s.shader = null
    }

    /**
     * The door's hinge on the crease (13 Nov 2026): two chrome knuckles on their pin, each with a
     * leaf screwed to the door and one to the body, so the phone's own fold is the Walkman's hinge.
     * [x], [y0], [y1] place a crease running down the screen; [across] turns it to run across.
     */
    fun hinge(c: Canvas, at: Float, from: Float, to: Float, across: Boolean, alpha: Float = 1f) {
        if (alpha < 0.01f) return
        val F = finish()
        val len = to - from
        val kl = min(dp(78f), len * 0.12f)
        val bw = dp(11f); val leaf = dp(17f)
        c.save()
        if (across) { c.translate(0f, at); c.rotate(-90f); c.translate(-at, 0f) }
        for (f in floatArrayOf(0.2f, 0.8f)) {
            val cy = from + len * f
            val x = if (across) at else at
            // the leaves, one on each side, a shade lighter than the body, screwed down
            for (side in intArrayOf(-1, 1)) {
                val lx0 = if (side < 0) x - bw / 2 - leaf else x + bw / 2
                r.set(lx0, cy - kl * 0.42f, lx0 + leaf, cy + kl * 0.42f)
                fill(Color.BLACK.a(0.5f * alpha)); p.setShadowLayer(dp(3f), 0f, dp(1.5f), Color.BLACK.a(0.7f * alpha)); c.drawRoundRect(r, dp(3f), dp(3f), p); p.clearShadowLayer()
                p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(lighten(F.lid[0], 0.25f).a(alpha), F.lid[1].a(alpha), darken(F.stops[2], 0.2f).a(alpha)), null, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, dp(3f), dp(3f), p); p.shader = null
                s.strokeWidth = dp(0.7f); s.color = Color.WHITE.a(0.35f * alpha); c.drawLine(r.left + dp(2f), r.top + dp(0.6f), r.right - dp(2f), r.top + dp(0.6f), s)
                val sxx = r.centerX()
                screw(c, sxx, cy - kl * 0.22f, dp(2.6f), 30f + side * 20f)
                screw(c, sxx, cy + kl * 0.22f, dp(2.6f), 70f - side * 15f)
            }
            // the barrel: a polished cylinder, its pin showing at each end
            r.set(x - bw / 2, cy - kl / 2, x + bw / 2, cy + kl / 2)
            fill(Color.BLACK.a(0.6f * alpha)); p.setShadowLayer(dp(4f), dp(1f), dp(2f), Color.BLACK.a(0.8f * alpha)); c.drawRoundRect(r, bw / 2, bw / 2, p); p.clearShadowLayer()
            p.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(0xFF4D5258.toInt().a(alpha), 0xFFF4F5F6.toInt().a(alpha), 0xFFB9BEC4.toInt().a(alpha), 0xFF6B7178.toInt().a(alpha), 0xFF2A2E33.toInt().a(alpha)), floatArrayOf(0f, 0.3f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, bw / 2, bw / 2, p); p.shader = null
            // where door knuckle meets body knuckle
            s.strokeWidth = dp(1f); s.color = Color.BLACK.a(0.6f * alpha); c.drawLine(r.left, cy, r.right, cy, s)
            s.color = Color.WHITE.a(0.3f * alpha); c.drawLine(r.left, cy + dp(1f), r.right, cy + dp(1f), s)
            fill(0xFF8D949B.toInt().a(alpha)); c.drawCircle(x, r.top + bw * 0.25f, bw * 0.16f, p); c.drawCircle(x, r.bottom - bw * 0.25f, bw * 0.16f, p)
        }
        c.restore()
    }

    /**
     * The body's edge where it meets the bezel (14 Nov 2026). Mechack: the player and the phone's
     * bezel met artificially, a picture laid on glass. A unibody's metal doesn't stop at the glass:
     * it rolls over a chamfer and turns down out of sight. So round the whole screen, following
     * the screen's own rounded corners [rad], the metal darkens as it turns away, and a thin
     * machined chamfer just inside catches the light on the side the light comes from.
     */
    fun edge(c: Canvas, w: Float, h: Float, rad: Float) {
        val e = dp(9f)
        // the turn-down: a soft shade gathering at the edge
        s.shader = null; s.color = Color.BLACK.a(0.62f); s.strokeWidth = e
        s.maskFilter = BlurMaskFilter(e * 0.9f, BlurMaskFilter.Blur.NORMAL)
        r.set(0f, 0f, w, h); c.drawRoundRect(r, rad, rad, s)
        s.maskFilter = null
        // the chamfer: bright where it faces the light, gone where it faces away
        val ins = dp(2.6f)
        val lx = -0.62f + Light.x * 0.7f; val ly = -0.78f + Light.y * 0.7f
        val cx = w / 2; val cy = h / 2; val reach = max(w, h) * 0.6f
        s.strokeWidth = dp(1.3f)
        s.shader = LinearGradient(cx + lx * reach, cy + ly * reach, cx - lx * reach, cy - ly * reach,
            intArrayOf(Color.WHITE.a(0.42f), Color.WHITE.a(0.08f), Color.BLACK.a(0.4f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        r.set(ins, ins, w - ins, h - ins); c.drawRoundRect(r, max(0f, rad - ins), max(0f, rad - ins), s)
        // and the dark break just outside it, where the chamfer meets the turn-down
        s.shader = null; s.strokeWidth = dp(0.8f); s.color = Color.BLACK.a(0.45f)
        val o = ins - dp(1.1f)
        r.set(o, o, w - o, h - o); c.drawRoundRect(r, max(0f, rad - o), max(0f, rad - o), s)
    }

    /** The light falls off towards the body's edges, as on any curved, lit object. */
    private fun falloff(c: Canvas, R0: RectF, rad: Float) {
        c.save(); path.reset(); path.addRoundRect(R0, rad, rad, Path.Direction.CW); c.clipPath(path)
        p.shader = RadialGradient(R0.left + R0.width() * (0.42f + 0.14f * Light.x), R0.top + R0.height() * (0.38f + 0.12f * Light.y), kotlin.math.hypot(R0.width(), R0.height()) * 0.62f,
            intArrayOf(Color.WHITE.a(0.05f), 0, Color.BLACK.a(0.32f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(R0, p); p.shader = null
        c.restore()
    }

    /** The body's machined edge at the hinge: a bright chamfer, then a dark break where the door meets it. */
    private fun hingeEdge(c: Canvas, G: PlayerGeom) {
        val b = G.body
        if (G.wide) {
            fill(Color.BLACK.a(0.5f)); c.drawRect(b.left, b.bottom - dp(1.2f), b.right, b.bottom, p)
            p.shader = LinearGradient(0f, b.bottom - dp(5f), 0f, b.bottom - dp(1.2f), 0, Color.WHITE.a(0.22f), Shader.TileMode.CLAMP)
            c.drawRect(b.left, b.bottom - dp(5f), b.right, b.bottom - dp(1.2f), p); p.shader = null
        } else {
            fill(Color.BLACK.a(0.5f)); c.drawRect(b.left, b.top, b.left + dp(1.2f), b.bottom, p)
            p.shader = LinearGradient(b.left + dp(1.2f), 0f, b.left + dp(5f), 0f, Color.WHITE.a(0.24f), 0, Shader.TileMode.CLAMP)
            c.drawRect(b.left + dp(1.2f), b.top, b.left + dp(5f), b.bottom, p); p.shader = null
        }
    }

    /**
     * A satin plate standing on the body: the lid's metal brushed the other way, a soft shadow on
     * the body beneath, its top edge catching the light and a fine dark line where it meets the body.
     */
    private fun raisedPlate(c: Canvas, R0: RectF, rad: Float, F: Finish, vertical: Boolean) {
        fill(F.lid[1]); p.setShadowLayer(dp(7f), dp(1.2f), dp(3.5f), 0x99000000.toInt()); c.drawRoundRect(R0, rad, rad, p); p.clearShadowLayer()
        metal(c, R0, rad, intArrayOf(F.lid[0], F.lid[1], F.stops[1]), vertical, F.lacquer, F.aniso)
        // a soft sheen across the plate, brighter towards the light
        c.save(); path.reset(); path.addRoundRect(R0, rad, rad, Path.Direction.CW); c.clipPath(path)
        p.shader = LinearGradient(R0.left, R0.top, R0.right, R0.bottom, intArrayOf(Color.WHITE.a(0.12f), 0, Color.BLACK.a(0.16f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(R0, p); p.shader = null
        c.restore()
        s.strokeWidth = dp(0.8f); s.color = Color.BLACK.a(0.5f)
        r.set(R0.left - dp(0.4f), R0.top - dp(0.4f), R0.right + dp(0.4f), R0.bottom + dp(0.4f)); c.drawRoundRect(r, rad + dp(0.4f), rad + dp(0.4f), s)
        bevel(c, R0, rad, 0.85f)
        if (F.coach) { s.strokeWidth = dp(0.5f); s.color = 0x59D9BB86; r.set(R0.left + dp(2.2f), R0.top + dp(2.2f), R0.right - dp(2.2f), R0.bottom - dp(2.2f)); c.drawRoundRect(r, max(0f, rad - dp(2.2f)), max(0f, rad - dp(2.2f)), s) }
    }

    /** A cross-head screw, countersunk: a dished head lit from the upper left, its slots cut dark. */
    private fun screw(c: Canvas, x: Float, y: Float, rad: Float, turn: Float) {
        fill(Color.BLACK.a(0.45f)); c.drawCircle(x, y, rad * 1.18f, p)
        p.shader = RadialGradient(x - rad * 0.35f, y - rad * 0.4f, rad * 1.4f, intArrayOf(0xFFF1F3F5.toInt(), 0xFFA8AEB5.toInt(), 0xFF5D646C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p); p.shader = null
        c.save(); c.translate(x, y); c.rotate(turn)
        s.strokeWidth = rad * 0.26f; s.color = 0xFF2A2E33.toInt()
        c.drawLine(-rad * 0.62f, 0f, rad * 0.62f, 0f, s); c.drawLine(0f, -rad * 0.62f, 0f, rad * 0.62f, s)
        s.strokeWidth = rad * 0.08f; s.color = Color.WHITE.a(0.4f)
        c.drawLine(-rad * 0.62f, rad * 0.17f, rad * 0.62f, rad * 0.17f, s)
        c.restore()
    }

    private var grainBmp: Bitmap? = null
    /** Fine moulding grain for the deck's floor. */
    private fun grain(): Bitmap = grainBmp ?: run {
        val n = 96
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(11)
        val px = IntArray(n * n) { val v = rnd.nextInt(256); if (rnd.nextBoolean()) Color.argb(v / 6, 255, 255, 255) else Color.argb(v / 4, 0, 0, 0) }
        b.setPixels(px, 0, n, 0, 0, n, n)
        grainBmp = b; b
    }

    /**
     * The chassis under the tape, in the cassette's millimetres: a pressed plate with a raised rib
     * round it, the spindle bosses the reels sit on, locating posts and the plate's screws. Most of
     * it is hidden by the tape; what shows round the edges is what makes the hole a machine.
     */
    private fun chassis(c: Canvas, G: PlayerGeom) {
        c.save(); c.translate(G.tx, G.ty); c.rotate(G.rot); c.scale(G.k, G.k)
        r.set(-CW / 2 - 5f, -CH / 2 - 5f, CW / 2 + 5f, CH / 2 + 12f)
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF4A535D.toInt(), 0xFF262C33.toInt(), Shader.TileMode.CLAMP)
        p.setShadowLayer(1.6f, 0.4f, 0.8f, 0x80000000.toInt()); c.drawRoundRect(r, 4f, 4f, p); p.clearShadowLayer(); p.shader = null
        // the pressed rib: lit on its upper edge, shaded below
        s.strokeWidth = 0.5f; s.color = Color.WHITE.a(0.18f); r.inset(1.6f, 1.6f); c.drawRoundRect(r, 3f, 3f, s)
        s.color = Color.BLACK.a(0.35f); r.offset(0.35f, 0.5f); c.drawRoundRect(r, 3f, 3f, s)
        // lightening holes stamped in the plate, either side
        for (hx in floatArrayOf(-CW / 2 - 1.5f, CW / 2 + 1.5f)) {
            r.set(hx - 1.6f, -14f, hx + 1.6f, 10f); fill(0xFF0E1114.toInt()); c.drawRoundRect(r, 1.6f, 1.6f, p)
            s.strokeWidth = 0.3f; s.color = Color.WHITE.a(0.2f); r.offset(0f, 0.3f); c.drawRoundRect(r, 1.6f, 1.6f, s)
        }
        // spindle bosses under the hubs, and the locating posts the shell drops onto
        for (bx in floatArrayOf(-RDX, RDX)) {
            p.shader = RadialGradient(bx - 2f, RY - 2f, 9f, 0xFF6B747E.toInt(), 0xFF2A3036.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(bx, RY, 7.5f, p); p.shader = null
        }
        for ((qx, qy) in listOf(-44f to 24f, 44f to 24f)) {
            p.shader = RadialGradient(qx - 0.5f, qy - 0.5f, 2.2f, 0xFFDDE1E5.toInt(), 0xFF6E757C.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(qx, qy, 1.5f, p); p.shader = null
        }
        c.restore()
        // the plate's screws, drawn upright whatever the tape's turn
        for ((sx, sy) in listOf(-CW / 2 - 2.6f to -CH / 2 - 2.6f, CW / 2 + 2.6f to -CH / 2 - 2.6f, -CW / 2 - 2.6f to CH / 2 + 9f, CW / 2 + 2.6f to CH / 2 + 9f)) {
            val q = G.at(sx, sy)
            screw(c, q.x, q.y, 1.5f * G.k, sx * 3 + sy)
        }
    }

    /**
     * The drive beyond the tape's ends, on the compartment floor (14 Nov 2026): at the top the motor,
     * a steel can on its bracket with its wires, its brass pulley and the flat belt running under the
     * chassis to the capstan's flywheel; at the foot the reel gear train, nylon and brass, and the
     * bronze leaf spring that holds a cassette down. In the shell's millimetres, turned with the tape.
     */
    private fun drive(c: Canvas, G: PlayerGeom) {
        val e = G.ends
        if (e < 6f) return
        val end = CW / 2 + 1.2f
        c.save(); c.translate(G.tx, G.ty); c.rotate(G.rot); c.scale(G.k, G.k)
        // ---- the motor
        val mx = -end - e * 0.5f; val my = -6f; val mr = min(e * 0.42f, 8.5f)
        fill(0xFF15191D.toInt()); r.set(mx - mr - 1.6f, my - mr * 0.5f, mx + mr + 1.6f, my + mr * 0.5f); c.drawRoundRect(r, 0.8f, 0.8f, p)   // its bracket
        for (bx in floatArrayOf(mx - mr - 0.6f, mx + mr + 0.6f)) { p.shader = RadialGradient(bx - 0.3f, my - 0.3f, 1.2f, 0xFFDDE1E5.toInt(), 0xFF5D646C.toInt(), Shader.TileMode.CLAMP); c.drawCircle(bx, my, 0.75f, p); p.shader = null }
        p.setShadowLayer(1.6f, 0.5f, 1f, 0xAA000000.toInt())
        p.shader = RadialGradient(mx - mr * 0.35f, my - mr * 0.35f, mr * 1.5f, intArrayOf(0xFFE3E7EB.toInt(), 0xFF8E969F.toInt(), 0xFF3B4148.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, mr, p); p.clearShadowLayer(); p.shader = null
        s.strokeWidth = 0.3f; s.color = 0x55000000; c.drawCircle(mx, my, mr * 0.82f, s); c.drawCircle(mx, my, mr * 0.4f, s)
        s.color = 0x40FFFFFF; r.set(mx - mr * 0.82f, my - mr * 0.82f, mx + mr * 0.82f, my + mr * 0.82f); c.drawArc(r, 200f, 70f, false, s)
        // its wires: red and black from the tabs, away round the end
        s.strokeWidth = 0.55f; s.strokeCap = Paint.Cap.ROUND
        for ((dx, col) in listOf(-1.4f to 0xFFB3261E.toInt(), 1.4f to 0xFF1A1A1A.toInt())) {
            s.color = col; path.reset(); path.moveTo(mx + dx, my - mr + 0.6f); path.quadTo(mx + dx, my - mr - 4f, mx + dx * 3f - 6f, -CH / 2 - 0.5f); c.drawPath(path, s)
        }
        s.strokeCap = Paint.Cap.BUTT
        // the belt, from the pulley to the flywheel under the capstan (beneath the chassis from here)
        val px = mx; val py = my + mr * 0.15f
        s.strokeWidth = 0.7f; s.color = 0xFF0D0D0D.toInt()
        c.drawLine(px - 1.6f, py, 10f, 40f, s); c.drawLine(px + 1.6f, py, 17f, 40f, s)
        s.strokeWidth = 0.2f; s.color = 0x26FFFFFF; c.drawLine(px + 1.6f, py, 17f, 40f, s)
        p.shader = RadialGradient(px - 0.5f, py - 0.5f, 2.2f, 0xFFF3D99B.toInt(), 0xFF8A6A2E.toInt(), Shader.TileMode.CLAMP); c.drawCircle(px, py, 1.7f, p); p.shader = null
        fill(0xFF3B2E17.toInt()); c.drawCircle(px, py, 0.5f, p)
        // ---- the reel gear train
        val gx = end + e * 0.5f
        fun gear(x: Float, y: Float, rad: Float, teeth: Int, body: Int, rim: Int, ang: Float) {
            c.save(); c.translate(x, y); c.rotate(ang * 57.3f)
            fill(Color.BLACK.a(0.45f)); c.drawCircle(0.4f, 0.7f, rad + 0.4f, p)
            fill(rim)
            for (i in 0 until teeth) { c.save(); c.rotate(i * 360f / teeth); c.drawRect(-0.35f, -rad - 0.5f, 0.35f, -rad + 0.3f, p); c.restore() }
            p.shader = RadialGradient(-rad * 0.3f, -rad * 0.3f, rad * 1.3f, body, rim, Shader.TileMode.CLAMP); c.drawCircle(0f, 0f, rad, p); p.shader = null
            s.strokeWidth = 0.22f; s.color = 0x33000000; c.drawCircle(0f, 0f, rad * 0.62f, s)
            for (i in 0 until 3) { c.save(); c.rotate(i * 120f); c.drawLine(0f, rad * 0.25f, 0f, rad * 0.6f, s); c.restore() }
            fill(0xFF6E757C.toInt()); c.drawCircle(0f, 0f, 0.55f, p)
            c.restore()
        }
        val gr = min(e * 0.26f, 5.2f)
        gear(gx, -14f, gr, 22, 0xFFF1EFE8.toInt(), 0xFFBDB8AA.toInt(), Deck.angL)
        gear(gx + gr * 0.25f, -14f + gr + gr * 0.62f + 0.6f, gr * 0.62f, 14, 0xFFF0D392.toInt(), 0xFF9A7A3A.toInt(), -Deck.angL * 1.6f)
        gear(gx, 12f, gr, 22, 0xFFF1EFE8.toInt(), 0xFFBDB8AA.toInt(), Deck.angR)
        // the leaf spring that presses a cassette down, screwed to the floor
        s.strokeWidth = 1.4f; s.color = 0xFFB08A4E.toInt(); s.strokeCap = Paint.Cap.ROUND
        path.reset(); path.moveTo(end + 0.6f, 24f); path.quadTo(gx, 30f, end + e * 0.85f, 27f); c.drawPath(path, s)
        s.strokeWidth = 0.3f; s.color = 0x66FFF1C9; c.drawPath(path, s); s.strokeCap = Paint.Cap.BUTT
        c.restore()
        // the screws, upright on screen
        val q1 = G.at(end + e * 0.85f, 27f); screw(c, q1.x, q1.y, 1.2f * G.k, 30f)
        val q2 = G.at(mx - mr - 0.6f, my); val q3 = G.at(mx + mr + 0.6f, my)
        screw(c, q2.x, q2.y, 0.9f * G.k, 10f); screw(c, q3.x, q3.y, 0.9f * G.k, 50f)
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

    /**
     * Inside of the door when the phone is open: the shelf is kept here. Full-bleed (13 Nov 2026),
     * it is the back of the face: the face's own brushed metal runs round the rim, and inside it a
     * moulded panel, chamfered where it steps down, screwed at its corners, with the moulded groove
     * along the hinge side where the index lives.
     */
    fun doorInside(c: Canvas, area: RectF, hingeRight: Boolean, rad: Float = dp(18f), hingeBottom: Boolean = false) {
        val F = finish()
        val inside = F.inside
        if (rad > 0f) {
            p.shader = LinearGradient(area.left, area.top, area.right, area.bottom, inside[0], inside[1], Shader.TileMode.CLAMP)
            c.drawRoundRect(area, rad, rad, p); p.shader = null
        } else {
            // the rim: the face's metal, brushed as the lid is
            fill(F.lid[1]); c.drawRect(area, p)
            metal(c, area, 0f, intArrayOf(F.lid[0], F.lid[1], F.stops[2]), !hingeBottom, F.lacquer, F.aniso)
        }
        val rimO = if (rad > 0f) 0f else dp(12f); val rimH = if (rad > 0f) 0f else dp(5f)
        // [hingeBottom]: the phone on its side, the door below the crease, so its hinge edge is its top
        val P = RectF(
            area.left + (if (!hingeBottom && !hingeRight) rimH else rimO),
            area.top + (if (hingeBottom) rimH else rimO),
            area.right - (if (hingeRight && !hingeBottom) rimH else rimO),
            area.bottom - rimO)
        val pr = if (rad > 0f) rad else dp(14f)
        if (rad == 0f) {
            // the step down from rim to panel: dark where it faces away from the light, lit where it faces it
            fill(Color.BLACK.a(0.55f)); r.set(P.left - dp(1.5f), P.top - dp(1.5f), P.right + dp(1f), P.bottom + dp(1f)); c.drawRoundRect(r, pr + dp(1.5f), pr + dp(1.5f), p)
        }
        p.shader = LinearGradient(P.left, P.top, P.right, P.bottom, inside[0], inside[1], Shader.TileMode.CLAMP)
        c.drawRoundRect(P, pr, pr, p); p.shader = null
        c.save(); path.reset(); path.addRoundRect(P, pr, pr, Path.Direction.CW); c.clipPath(path)
        // fine moulding ribs
        s.strokeWidth = 1f; s.color = 0x09FFFFFF
        var yy = P.top + dp(14f); val step = dp(7f)
        while (yy < P.bottom) { c.drawLine(P.left, yy, P.right, yy, s); yy += step }
        if (rad == 0f) {
            // the rim's shadow falling onto the panel
            p.shader = LinearGradient(0f, P.top, 0f, P.top + dp(16f), Color.BLACK.a(0.45f), 0, Shader.TileMode.CLAMP); c.drawRect(P, p)
            p.shader = LinearGradient(P.left, 0f, P.left + dp(12f), 0f, Color.BLACK.a(0.35f), 0, Shader.TileMode.CLAMP); c.drawRect(P, p)
            p.shader = null
        }
        // The trim along the hinge edge: one moulded groove, where the index lives
        if (!hingeBottom) {
            val gx = if (hingeRight) P.right - dp(14f) else P.left + dp(14f)
            s.strokeWidth = dp(1f); s.color = 0x33000000; c.drawLine(gx, P.top + dp(20f), gx, P.bottom - dp(20f), s)
            s.color = 0x14FFFFFF; c.drawLine(gx + dp(1f), P.top + dp(20f), gx + dp(1f), P.bottom - dp(20f), s)
        }
        c.restore()
        if (rad == 0f) {
            s.strokeWidth = dp(1f); s.shader = LinearGradient(P.left, P.top, P.right, P.bottom, intArrayOf(Color.BLACK.a(0.6f), Color.WHITE.a(0.28f)), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(P, pr, pr, s); s.shader = null
            // the panel's screws, in the outer rim, clear of the screen's rounded corners and the camera
            val q = dp(6f)
            val pts = if (hingeBottom) listOf(area.left + area.width() * 0.25f to area.bottom - q, area.left + area.width() * 0.75f to area.bottom - q)
                else { val ox = if (hingeRight) area.left + q else area.right - q; listOf(ox to area.top + area.height() * 0.25f, ox to area.top + area.height() * 0.75f) }
            for ((sx, sy) in pts) screw(c, sx, sy, dp(3f), (sx + sy) % 90f)
        }
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

    /** [area] holds the tapes; [bg], when given, is the full-bleed panel behind them (square-cornered). */
    fun shelf(c: Canvas, area: RectF, slots: List<Slot>, lean: FloatArray, header: Boolean, onDoor: Boolean, count: Int, scroll: Float = 0f, bg: RectF? = null, hingeBottom: Boolean = false) {
        val fade = 1 - Deck.conc * 0.75f
        val panel = bg ?: area
        val rad = if (bg != null) 0f else dp(18f)
        if (onDoor) doorInside(c, panel, true, rad, hingeBottom) else { fill(0xFF13171C.toInt()); c.drawRoundRect(panel, rad, rad, p) }
        c.save(); path.reset(); path.addRoundRect(panel, rad, rad, Path.Direction.CW); c.clipPath(path)
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
            if (sl.tape === inDeck || sl.tape === pending || sl.tape === carried) {
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
                } else if (sl.tape.mix) {
                    // a mixtape's J-card: the tape's own colour with its name written across, the way they were made
                    val cw = rc.height() * 0.62f
                    fill(sl.tape.shell.labelBg.a(fade)); r.set(-cw / 2, -cardH / 2, cw / 2, cardH / 2); c.drawRect(r, p)
                    fill(sl.tape.shell.accent.a(fade)); c.drawRect(-cw / 2, -cardH / 2, cw / 2, -cardH * 0.36f, p)
                    s.strokeWidth = dp(0.6f); s.color = INK.a(0.25f * fade)
                    var ly = -cardH * 0.12f; while (ly < cardH / 2) { c.drawLine(-cw * 0.42f, ly, cw * 0.42f, ly, s); ly += cardH * 0.12f }
                    c.save(); c.rotate(-90f)
                    handwrite(c, sl.tape.album, 0f, cw * 0.12f, cw * 0.32f, cardH * 0.86f, INK.a(fade), Paint.Align.CENTER)
                    c.restore()
                } else {
                    val cw = rc.height() * 0.62f
                    fill(sl.tape.card.a(fade)); r.set(-cw / 2, -cardH / 2, cw / 2, cardH / 2); c.drawRect(r, p)
                    fill(0xFFF2EBDD.toInt().a(fade)); c.drawRect(-cw / 2, cardH * 0.08f, cw / 2, cardH * 0.24f, p)
                    c.drawCircle(0f, -cardH * 0.14f, cw * 0.24f, p)
                    print(c, sl.tape.artist.uppercase().take(16), 0f, cardH * 0.2f, cw * 0.11f, sl.tape.card, true, Paint.Align.CENTER)
                }
            } else if (sl.tape === Library.BLANK) {
                // a blank, still in its cellophane: clear shell, a label waiting for a name
                val kk = rc.width() / (CW + 4)
                c.save(); c.scale(kk, kk)
                cassette(c, sl.tape, false, fade)
                c.restore()
                s.strokeWidth = dp(1f); s.color = 0x47FFFFFF; s.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
                r.set(-rc.width() / 2 - dp(3f), -rc.height() / 2 - dp(3f), rc.width() / 2 + dp(3f), rc.height() / 2 + dp(3f)); c.drawRoundRect(r, dp(8f), dp(8f), s); s.pathEffect = null
                p.shader = LinearGradient(-rc.width() / 2, -rc.height() / 2, rc.width() / 2, rc.height() / 2,
                    intArrayOf(Color.WHITE.a(0.14f), 0, Color.WHITE.a(0.06f), 0), floatArrayOf(0f, 0.3f, 0.55f, 1f), Shader.TileMode.CLAMP)
                r.set(-rc.width() / 2, -rc.height() / 2, rc.width() / 2, rc.height() / 2); c.drawRoundRect(r, dp(5f), dp(5f), p); p.shader = null
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
