package com.fergusonconsultancy.nastro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Unfolded, Nastro is a home hi-fi (11 Nov 2026, Mechack's choice of option A): the Walkman you
 * carry is the folded phone; open it and you are home at the stack.
 *
 * From his three references: the twin cassette deck (doors with windows, piano-key transports,
 * a glowing display between them with a counter for each deck), the gloss black units with
 * champagne cheeks, gold coachlines and chrome feet, the amplifier's orange bar meters and big
 * volume knob, and cherry wood speakers either side. Below the amplifier, a drawer of tapes.
 *
 * Everything here answers to something real: deck 1 plays, deck 2 is where mixtapes are made, the
 * meters and the speaker cones follow the song itself ([Levels]), the volume knob is the phone's
 * volume, and the switches are the deck's Mega Bass and auto reverse.
 *
 * Laid out in the mockup's own units (the stack 604 wide), scaled to the screen.
 */
class HiFi(ctx: Context, private val painter: Painter) {
    private val app = ctx.applicationContext
    private val dens = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    private val GOLD = 0xFFD9BB86.toInt()
    private val AMBER = 0xFFFFAD33.toInt()
    private val RED = 0xFFFF4A3A.toInt()
    private val INK = 0xFF1F2C6B.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val r = RectF()
    private val printS: Typeface = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/BarlowCondensed-SemiBold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
    private val hand: Typeface = runCatching { Typeface.Builder(ctx.assets, "fonts/Caveat.ttf").setFontVariationSettings("'wght' 650").build() }.getOrNull() ?: Typeface.SERIF

    // What the stage provides
    var onKey: ((Deck.Key) -> Unit)? = null
    var onOpenShelf: (() -> Unit)? = null
    var onNewMixtape: (() -> Unit)? = null
    var onEditMixtape: ((Tape) -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onHaptic: ((Int) -> Unit)? = null
    /** A door has shut on its tape: the stage's clunk (sound and haptic). */
    var onClunk: (() -> Unit)? = null
    var volume: () -> Float = { 0.7f }
    var setVolume: (Float) -> Unit = {}

    // ---- layout, in the mockup's units -----------------------------------------------------------
    private var u = 1f
    private var ox = 0f; private var oy = 0f              // where the stack's (0, 0) falls on screen
    private val screen = RectF()
    private fun X(v: Float) = ox + v * u
    private fun Y(v: Float) = oy + v * u

    // The stack, 604 wide: deck 262 tall, amplifier 170, drawer 170, 22 between (the feet)
    private val SW = 604f
    private val deckR = RectF(); private val ampR = RectF(); private val drawerR = RectF()
    private val spkL = RectF(); private val spkR = RectF()
    private val door1 = RectF(); private val door2 = RectF()
    private val win1 = RectF(); private val win2 = RectF()
    private val display = RectF()
    private val mixBtn = RectF()
    private val meters = RectF()
    private val volKnob = RectF()
    private val bassSw = RectF(); private val revSw = RectF(); private val setupBtn = RectF()
    private val tray = RectF(); private val handle = RectF()
    private var tableY = 0f

    class HKey(val id: String, val rect: RectF)
    private val keys1 = ArrayList<HKey>()
    private val keys2 = ArrayList<HKey>()

    fun layout(L: Float, T: Float, W: Float, H: Float, full: RectF) {
        screen.set(full)
        val m = dp(14f)
        // stack and speakers together are 900 wide; the stack is 656 tall with a little headroom
        u = min((W - m * 2) / 900f, (H - m * 2) / 720f)
        ox = L + (W - SW * u) / 2
        oy = T + (H - 656f * u) / 2 + 14f * u
        deckR.set(X(0f), Y(0f), X(SW), Y(262f))
        ampR.set(X(0f), Y(284f), X(SW), Y(454f))
        drawerR.set(X(0f), Y(476f), X(SW), Y(646f))
        spkL.set(X(-148f), Y(222f), X(-12f), Y(656f))
        spkR.set(X(SW + 12f), Y(222f), X(SW + 148f), Y(656f))
        tableY = Y(656f)

        // the twin deck: two doors, the display between
        val dw = SW * 0.3f; val dh = 262f * 0.56f; val dy = 262f * 0.16f
        val lx = SW * 0.05f; val rx = SW - SW * 0.05f - dw
        door1.set(X(lx), Y(dy), X(lx + dw), Y(dy + dh))
        door2.set(X(rx), Y(dy), X(rx + dw), Y(dy + dh))
        for ((d, w) in listOf(door1 to win1, door2 to win2)) {
            val ww = d.width() * 0.84f
            w.set(d.centerX() - ww / 2, d.top + d.height() * 0.1f, d.centerX() + ww / 2, d.top + d.height() * 0.72f)
        }
        val dx0 = lx + dw + SW * 0.03f; val dx1 = rx - SW * 0.03f
        display.set(X(dx0), Y(dy), X(dx1), Y(dy + dh * 0.6f))
        val mr = 262f * 0.085f
        val mcx = (dx0 + dx1) / 2; val mcy = dy + dh * 0.74f + 262f * 0.17f
        mixBtn.set(X(mcx - mr), Y(mcy - mr), X(mcx + mr), Y(mcy + mr))
        // piano keys under each door: deck 1 eject, rewind, wind, stop, play; deck 2 record, play, eject
        val kh = 262f * 0.1f; val ky = dy + dh + 262f * 0.08f
        keys1.clear(); keys2.clear()
        val kw = (dw - 20f) / 5
        listOf("eject", "rew", "ff", "stop", "play").forEachIndexed { j, id ->
            keys1 += HKey(id, RectF(X(lx + 4 + j * (kw + 3)), Y(ky), X(lx + 4 + j * (kw + 3) + kw), Y(ky + kh)))
        }
        val kw2 = (dw - 14f) / 3
        listOf("rec", "play2", "edit").forEachIndexed { j, id ->
            keys2 += HKey(id, RectF(X(rx + 4 + j * (kw2 + 3)), Y(ky), X(rx + 4 + j * (kw2 + 3) + kw2), Y(ky + kh)))
        }

        // the amplifier: lamps and switches left, meters, the volume knob right
        val ay = 284f; val ah = 170f
        meters.set(X(SW * 0.2f), Y(ay + ah * 0.24f), X(SW * 0.68f), Y(ay + ah * 0.7f))
        val vr = ah * 0.3f; val vx = SW * 0.845f; val vy = ay + ah * 0.52f
        volKnob.set(X(vx - vr), Y(vy - vr), X(vx + vr), Y(vy + vr))
        bassSw.set(X(26f), Y(ay + ah * 0.53f), X(26f + 26f), Y(ay + ah * 0.53f + 12f))
        revSw.set(X(26f), Y(ay + ah * 0.68f), X(26f + 26f), Y(ay + ah * 0.68f + 12f))
        setupBtn.set(X(26f), Y(ay + ah * 0.81f), X(26f + 46f), Y(ay + ah * 0.81f + 14f))

        // the drawer: open, its tapes standing on end
        val ty0 = 476f
        tray.set(X(20f), Y(ty0 + 170f * 0.22f), X(SW - 20f), Y(ty0 + 170f * 0.82f))
        handle.set(X(SW / 2 - 46f), Y(ty0 + 170f - 24f), X(SW / 2 + 46f), Y(ty0 + 170f - 14f))
    }

    // ---- state -------------------------------------------------------------------------------
    private val lv = FloatArray(3)
    private var mL = 0f; private var mR = 0f; private var mB = 0f
    private var pkL = 0f; private var pkR = 0f; private var pkAtL = 0L; private var pkAtR = 0L
    private var lastTrack = -1L
    /** Which mixtape deck 2 shows: an index into the mixtapes, the blank after them. */
    private var d2 = 0
    private var pressedId: String? = null
    private var pressedAt = 0L
    private var spineScroll = 0f
    private val spines = ArrayList<Pair<Tape, RectF>>()
    private val scroller: OverScroller = OverScroller(ctx)
    /** Where a tape set off from on its way into deck 1, on screen. */
    val flightFrom = RectF()
    var power = 1f

    // ---- the doors and the hinge (12 Nov 2026) --------------------------------------------------
    // Mechack: the cassette doors open with the hinge itself, so unfolding the phone opens the deck.
    // The phone's hinge angle drives both doors: half open, they are half open; flat, they lie fully
    // open with the tapes in their holders. Play, or a tap on a door, shuts it with a clunk. Folding
    // part way again hands the doors back to the hinge.
    /** The hinge angle in degrees from the phone's sensor, or -1 where the phone has none. */
    var hinge = -1f
    private var hingeOpen = 0f
    private var shut1 = false; private var shut2 = false
    private var o1 = 0f; private var o2 = 0f              // how far each door stands open, 0..1
    private var openedSound = false
    private var flatSeen = false
    private val MAX_TILT = 38f                           // degrees: a front-loading door's full swing
    private val warp = android.graphics.Matrix()
    private val src = FloatArray(8); private val dst = FloatArray(8)

    /** The phone has just been unfolded: both doors go back to following the hinge. */
    fun unfolded() { shut1 = false; shut2 = false; o1 = 0f; o2 = 0f; hingeOpen = 0f; openedSound = false; flatSeen = false }

    private fun doors(dt: Float) {
        // Below about 20° the inner screen is dark anyway; the doors reach fully open as the phone lies flat
        val target = if (hinge >= 0f) ((hinge - 20f) / 155f).coerceIn(0f, 1f) else power
        hingeOpen += (target - hingeOpen) * (1 - exp(-dt / 0.05f))
        // folding back from flat hands the doors to the hinge again; a door shut while half open stays shut
        if (hinge >= 165f) flatSeen = true
        if (flatSeen && hinge in 0f..150f) { shut1 = false; shut2 = false; flatSeen = false }
        val t1 = if (shut1) 0f else hingeOpen; val t2 = if (shut2) 0f else hingeOpen
        // a door falls open on its damper; it is pushed shut quickly
        o1 += (t1 - o1) * (1 - exp(-dt / (if (t1 < o1 && shut1) 0.05f else 0.09f)))
        o2 += (t2 - o2) * (1 - exp(-dt / (if (t2 < o2 && shut2) 0.05f else 0.09f)))
        if (abs(o1 - t1) < 0.002f) o1 = t1; if (abs(o2 - t2) < 0.002f) o2 = t2
        if (!openedSound && hingeOpen > 0.9f) { openedSound = true; Sfx.play(Sfx.S.DOOR_OPEN, 0.7f) }
        if (hingeOpen < 0.5f) openedSound = false
    }

    private fun shut(which: Int) {
        if (which == 1) { if (shut1 || o1 < 0.05f) return; shut1 = true } else { if (shut2 || o2 < 0.05f) return; shut2 = true }
        Sfx.play(Sfx.S.DOOR_SHUT, 0.8f); onClunk?.invoke()
    }

    /** A tape is on its way into deck 1: its door closes over it as it seats. */
    fun seat1() { shut1 = true }

    private fun reopen(which: Int) {
        if (which == 1) shut1 = false else shut2 = false
        Sfx.play(Sfx.S.DOOR_OPEN, 0.7f); onHaptic?.invoke(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        // with the phone flat, the hinge holds the doors open; with no hinge sensor, open fully
        if (hinge < 0f) hingeOpen = 1f
    }

    private fun mixes(): List<Tape> = Library.tapes.filter { it.mix } + Library.BLANK
    private fun deck2Tape(): Tape { val m = mixes(); d2 = d2.coerceIn(0, m.size - 1); return m[d2] }

    /** True while something on the stack is still moving. */
    fun busy(): Boolean = abs(o1 - (if (shut1) 0f else hingeOpen)) > 0.002f || abs(o2 - (if (shut2) 0f else hingeOpen)) > 0.002f || mL > 0.01f || mR > 0.01f || mB > 0.01f || pkL > 0.01f || pkR > 0.01f || !scroller.isFinished ||
        (pressedId != null && SystemClock.uptimeMillis() - pressedAt < 250)

    // ---- drawing ------------------------------------------------------------------------------
    fun draw(c: Canvas, dt: Float, lifting: Boolean) {
        if (scroller.computeScrollOffset()) spineScroll = scroller.currX.toFloat()
        measure(dt)
        doors(dt)
        room(c)
        speaker(c, spkL); speaker(c, spkR)
        unit(c, drawerR); drawer(c)
        unit(c, ampR); amp(c)
        unit(c, deckR); deck(c, lifting)
        flight(c, lifting)
        if (power < 1f) { p.shader = null; p.color = Color.BLACK.a(0.75f * (1 - power)); c.drawRect(screen, p) }
    }

    /** The meters' ballistics: quick to rise, slow to fall, a peak held for a moment. */
    private fun measure(dt: Float) {
        val tr = Audio.track
        var l = 0f; var rr = 0f; var b = 0f
        if (tr != null) {
            if (tr.id != lastTrack) { Levels.prepare(app, tr); lastTrack = tr.id }
            if (Audio.isPlaying() && Levels.at(tr.id, Audio.positionSecs(), lv)) { l = lv[0]; rr = lv[1]; b = lv[2] }
        }
        l *= power; rr *= power; b *= power
        fun ball(cur: Float, tgt: Float) = if (tgt > cur) cur + (tgt - cur) * (1 - exp(-dt / 0.03f)) else cur + (tgt - cur) * (1 - exp(-dt / 0.35f))
        mL = ball(mL, l); mR = ball(mR, rr); mB = if (b > mB) b else mB + (b - mB) * (1 - exp(-dt / 0.12f))
        val now = SystemClock.uptimeMillis()
        if (mL >= pkL) { pkL = mL; pkAtL = now } else if (now - pkAtL > 900) pkL = max(mL, pkL - dt * 0.6f)
        if (mR >= pkR) { pkR = mR; pkAtR = now } else if (now - pkAtR > 900) pkR = max(mR, pkR - dt * 0.6f)
    }

    private fun room(c: Canvas) {
        // a dark green room (the portfolio's Visconti), the stack on a table
        p.shader = LinearGradient(0f, screen.top, 0f, tableY, intArrayOf(0xFF0F1F17.toInt(), 0xFF09140F.toInt(), 0xFF060B08.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(screen.left, screen.top, screen.right, tableY, p)
        p.shader = RadialGradient(screen.centerX(), Y(300f), max(screen.width(), screen.height()) * 0.6f, intArrayOf(0x22D9BB86, 0), null, Shader.TileMode.CLAMP)
        c.drawRect(screen.left, screen.top, screen.right, tableY, p)
        p.shader = LinearGradient(0f, tableY, 0f, screen.bottom, intArrayOf(0xFF2E241A.toInt(), 0xFF17110C.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(screen.left, tableY, screen.right, screen.bottom, p)
        p.shader = LinearGradient(0f, tableY, 0f, tableY + 2 * u, Color.WHITE.a(0.18f), 0, Shader.TileMode.CLAMP)
        c.drawRect(screen.left, tableY, screen.right, tableY + 2 * u, p)
        p.shader = null
        // the wordmark above the stack
        label(c, "NASTRO", X(SW / 2), Y(-26f), 22f * u, GOLD, 0.9f, Paint.Align.CENTER, 14f * u)
    }

    private fun unit(c: Canvas, R: RectF) {
        val rad = 3f * u
        // the shadow it throws on whatever it stands on, and its chrome feet
        p.shader = null; p.color = Color.BLACK.a(0.55f); p.setShadowLayer(6f * u, 0f, 3f * u, Color.BLACK.a(0.8f))
        c.drawRect(R.left + 6 * u, R.bottom - 6 * u, R.right - 6 * u, R.bottom, p); p.clearShadowLayer()
        for (fx in floatArrayOf(R.left + 40 * u, R.right - 40 * u)) {
            p.shader = LinearGradient(0f, R.bottom, 0f, R.bottom + 10 * u, intArrayOf(0xFFF4F5F6.toInt(), 0xFF9AA0A6.toInt(), 0xFF5D6268.toInt(), 0xFFC9CDD1.toInt()), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
            r.set(fx - 14 * u, R.bottom, fx + 14 * u, R.bottom + 10 * u); c.drawRoundRect(r, 2 * u, 2 * u, p)
        }
        // gloss black front
        p.shader = LinearGradient(0f, R.top, 0f, R.bottom, intArrayOf(0xFF26292E.toInt(), 0xFF121417.toInt(), 0xFF0B0C0E.toInt(), 0xFF16181B.toInt()), floatArrayOf(0f, 0.08f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(R, rad, rad, p)
        // champagne cheeks
        for (cx in floatArrayOf(R.left, R.right - 12 * u)) {
            p.shader = LinearGradient(cx, 0f, cx + 12 * u, 0f, intArrayOf(0xFF6B5A3C.toInt(), GOLD, 0xFF7A6644.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            r.set(cx, R.top, cx + 12 * u, R.bottom); c.drawRoundRect(r, rad, rad, p)
        }
        // the room's light across the top of the gloss
        p.shader = LinearGradient(R.left, R.top, R.left + R.width() * 0.6f, R.top + R.height() * 0.6f, Color.WHITE.a(0.09f), 0, Shader.TileMode.CLAMP)
        c.drawRect(R.left + 12 * u, R.top, R.right - 12 * u, R.top + R.height() * 0.42f, p)
        p.shader = null
        p.color = Color.WHITE.a(0.35f); c.drawRect(R.left, R.top, R.right, R.top + 1.2f * u, p)
        s.shader = null; s.strokeWidth = 0.8f * u; s.color = GOLD.a(0.45f)
        c.drawLine(R.left + 14 * u, R.bottom - 10 * u, R.right - 14 * u, R.bottom - 10 * u, s)
    }

    // ---- the twin deck ------------------------------------------------------------------------
    private fun deck(c: Canvas, lifting: Boolean) {
        label(c, "NASTRO", X(22f), Y(16f), 11f * u, GOLD, 1f, Paint.Align.LEFT, 5f * u)
        label(c, "DOUBLE CASSETTE DECK  ·  AUTO REVERSE", X(90f), Y(16f), 7f * u, GOLD, 0.6f, Paint.Align.LEFT, 2f * u)
        val tape1 = if (lifting) null else Deck.tape
        doorWith(c, door1, win1, "DECK 1  ·  PLAY", o1) { winR ->
            if (tape1 != null) {
                val k = min(winR.width() * 0.9f / PlayerGeom.CW, winR.height() * 0.86f / PlayerGeom.CH)
                c.save(); c.translate(winR.centerX(), winR.centerY() + winR.height() * 0.04f)
                val fl = if (Deck.flipping) cos(Deck.flip * PI).toFloat() else 1f
                c.scale(k, k * max(0.02f, abs(fl)))
                val facing = if (Deck.flipping && Deck.flip > 0.5f) (if (Deck.facing == 'A') 'B' else 'A') else Deck.facing
                painter.cassette(c, tape1, true, 1 - Deck.conc, facing)
                c.restore()
            }
        }
        val t2 = deck2Tape()
        doorWith(c, door2, win2, if (t2 === Library.BLANK) "DECK 2  ·  REC" else "DECK 2  ·  MIXTAPES", o2) { winR ->
            val k = min(winR.width() * 0.9f / PlayerGeom.CW, winR.height() * 0.86f / PlayerGeom.CH)
            c.save(); c.translate(winR.centerX(), winR.centerY() + winR.height() * 0.04f); c.scale(k, k)
            painter.cassette(c, t2, false, 1f)
            c.restore()
        }
        val now = SystemClock.uptimeMillis()
        val m = Deck.mode
        for (K in keys1) {
            val lit = (K.id == "play" && m == Deck.Mode.PLAY) || (K.id == "ff" && (m == Deck.Mode.FF || m == Deck.Mode.AMS)) || (K.id == "rew" && m == Deck.Mode.REW)
            pianoKey(c, K, lit, AMBER, now)
        }
        for (K in keys2) pianoKey(c, K, K.id == "rec" && t2 === Library.BLANK, RED, now)
        vfd(c)
        // the mixtape button: record onto deck 2
        val cx = mixBtn.centerX(); val cy = mixBtn.centerY(); val rr = mixBtn.width() / 2
        label(c, "MIXTAPE", cx, mixBtn.top - 6f * u, 7f * u, GOLD, 0.7f, Paint.Align.CENTER, 2f * u)
        p.shader = null; p.color = Color.BLACK.a(0.5f); c.drawCircle(cx, cy + 2 * u, rr * 1.06f, p)
        p.shader = RadialGradient(cx - rr * 0.35f, cy - rr * 0.4f, rr * 1.6f, intArrayOf(0xFFF3E5C6.toInt(), GOLD, 0xFF6B5A3C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rr, p)
        val pressed = pressedId == "mix" && now - pressedAt < 220
        p.shader = RadialGradient(cx - rr * 0.2f, cy - rr * 0.25f, rr * 0.7f, intArrayOf(0xFFFF6A5A.toInt(), 0xFFC8102E.toInt(), 0xFF5E0008.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + (if (pressed) 0.8f * u else 0f), rr * 0.48f, p); p.shader = null
    }

    /**
     * A front-loading door: hinged along its foot, it tilts its top towards you as it opens, and the
     * tape rides in the holder on its back, seen through the window. Behind it, as it falls open, the
     * well: the spindles the hubs drop onto, the head and capstans.
     */
    private inline fun doorWith(c: Canvas, d: RectF, w: RectF, caption: String, ajar: Float, inside: (RectF) -> Unit) {
        val th = Math.toRadians((ajar * MAX_TILT).toDouble())
        // the well behind the door
        p.shader = LinearGradient(0f, d.top, 0f, d.bottom, 0xFF020203.toInt(), 0xFF0E1013.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(d, 4 * u, 4 * u, p); p.shader = null
        if (ajar > 0.01f) {
            val my = w.centerY()
            for (dx in floatArrayOf(-0.21f, 0.21f)) {
                val sx = w.centerX() + dx * w.width()
                p.shader = RadialGradient(sx - 1.5f * u, my - 1.5f * u, 7f * u, intArrayOf(0xFFE9E4DA.toInt(), 0xFF8F8A80.toInt()), null, Shader.TileMode.CLAMP)
                c.drawCircle(sx, my, 5.5f * u, p); p.shader = null
                p.color = 0xFF111111.toInt()
                for (k in 0 until 6) { val a = k * PI / 3; c.drawCircle(sx + cos(a).toFloat() * 4.2f * u, my + sin(a).toFloat() * 4.2f * u, 1f * u, p) }
            }
            // the head and the capstans at the well's foot, where the tape's open edge meets them
            val hy = w.bottom + 4 * u
            p.shader = LinearGradient(w.centerX() - 9 * u, 0f, w.centerX() + 9 * u, 0f, intArrayOf(0xFF8D949B.toInt(), 0xFFF1F3F5.toInt(), 0xFF8D949B.toInt()), null, Shader.TileMode.CLAMP)
            r.set(w.centerX() - 9 * u, hy - 5 * u, w.centerX() + 9 * u, hy + 3 * u); c.drawRoundRect(r, 1.5f * u, 1.5f * u, p); p.shader = null
            p.color = 0xFFB9BEC4.toInt()
            for (dx in floatArrayOf(-0.33f, 0.33f)) c.drawCircle(w.centerX() + dx * w.width(), hy - 1 * u, 1.6f * u, p)
            // the door's own shadow falling back into the well
            p.shader = LinearGradient(0f, d.bottom - d.height() * cos(th).toFloat(), 0f, d.bottom, intArrayOf(0, Color.BLACK.a(0.75f * ajar)), null, Shader.TileMode.CLAMP)
            c.drawRect(d, p); p.shader = null
        }
        // the door, warped about its foot: the top comes down and towards you, so it widens
        c.save()
        if (ajar > 0.001f) {
            val topY = d.bottom - d.height() * cos(th).toFloat()
            val spread = d.width() * 0.11f * sin(th).toFloat()
            src[0] = d.left; src[1] = d.top; src[2] = d.right; src[3] = d.top; src[4] = d.right; src[5] = d.bottom; src[6] = d.left; src[7] = d.bottom
            dst[0] = d.left - spread; dst[1] = topY; dst[2] = d.right + spread; dst[3] = topY; dst[4] = d.right; dst[5] = d.bottom; dst[6] = d.left; dst[7] = d.bottom
            warp.setPolyToPoly(src, 0, dst, 0, 4)
            c.concat(warp)
        }
        p.shader = null; p.color = 0xFF0B0C0E.toInt(); c.drawRoundRect(d, 4 * u, 4 * u, p)
        s.shader = null; s.strokeWidth = 1f * u; s.color = 0xFF3A3D42.toInt(); c.drawRoundRect(d, 4 * u, 4 * u, s)
        r.set(d.left + 4 * u, d.top + 4 * u, d.right - 4 * u, d.bottom - 4 * u)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF1D2024.toInt(), 0xFF0D0E10.toInt(), Shader.TileMode.CLAMP); c.drawRoundRect(r, 3 * u, 3 * u, p)
        // the window: dark, the tape lit from inside, smoked glass over it
        p.shader = null; p.color = 0xFF050608.toInt(); c.drawRoundRect(w, 3 * u, 3 * u, p)
        c.save(); path.reset(); path.addRoundRect(w, 3 * u, 3 * u, Path.Direction.CW); c.clipPath(path)
        inside(w)
        p.shader = LinearGradient(w.left, w.top, w.right, w.bottom, intArrayOf(Color.WHITE.a(0.16f), Color.WHITE.a(0.03f), 0, Color.WHITE.a(0.05f)), floatArrayOf(0f, 0.35f, 0.36f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(w, p)
        p.shader = LinearGradient(0f, w.top, 0f, w.top + w.height() * 0.25f, Color.BLACK.a(0.5f * (1 - ajar)), 0, Shader.TileMode.CLAMP); c.drawRect(w, p)
        p.shader = null
        c.restore()
        s.strokeWidth = 0.8f * u; s.color = Color.WHITE.a(0.12f); c.drawRoundRect(w, 3 * u, 3 * u, s)
        label(c, caption, d.centerX(), d.top + d.height() * 0.88f, 7f * u, GOLD, 0.7f, Paint.Align.CENTER, 2f * u)
        // tilted up, the door's face turns towards the room's light
        if (ajar > 0.01f) { p.color = Color.WHITE.a(0.07f * ajar); c.drawRoundRect(d, 4 * u, 4 * u, p) }
        c.restore()
    }

    /** A piano key: ivory, its top edge catching the light; pressed, it sinks and darkens. */
    private fun pianoKey(c: Canvas, K: HKey, lit: Boolean, lamp: Int, now: Long) {
        val R = K.rect
        val down = (pressedId == K.id && now - pressedAt < 220) || lit
        val dy = if (down) 1.2f * u else 0f
        p.shader = null; p.color = Color.BLACK.a(0.6f); r.set(R.left, R.top + 1.5f * u, R.right, R.bottom + 1.5f * u); c.drawRoundRect(r, 2.5f * u, 2.5f * u, p)
        r.set(R.left, R.top + dy, R.right, R.bottom + dy)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, if (down) 0xFFDCD3C0.toInt() else 0xFFEFE7D6.toInt(), if (down) 0xFFA69A81.toInt() else 0xFFB9AD93.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 2.5f * u, 2.5f * u, p); p.shader = null
        if (!down) { p.color = Color.WHITE.a(0.55f); c.drawRect(r.left + 1.5f * u, r.top + 1 * u, r.right - 1.5f * u, r.top + 2.6f * u, p) }
        val cx = r.centerX(); val cy = r.centerY(); val h = r.height()
        p.color = 0xFF2B2B2B.toInt()
        path.reset()
        when (K.id) {
            "play", "play2" -> { path.moveTo(cx - h * 0.12f, cy - h * 0.22f); path.lineTo(cx + h * 0.2f, cy); path.lineTo(cx - h * 0.12f, cy + h * 0.22f); path.close() }
            "ff" -> { tri(cx - h * 0.2f, cy, h, true); tri(cx + h * 0.02f, cy, h, true) }
            "rew" -> { tri(cx + h * 0.2f, cy, h, false); tri(cx - h * 0.02f, cy, h, false) }
            "stop" -> path.addRect(cx - h * 0.16f, cy - h * 0.16f, cx + h * 0.16f, cy + h * 0.16f, Path.Direction.CW)
            "eject", "edit" -> {
                path.moveTo(cx - h * 0.2f, cy + h * 0.02f); path.lineTo(cx, cy - h * 0.22f); path.lineTo(cx + h * 0.2f, cy + h * 0.02f); path.close()
                path.addRect(cx - h * 0.2f, cy + h * 0.1f, cx + h * 0.2f, cy + h * 0.18f, Path.Direction.CW)
            }
            "rec" -> { p.color = 0xFFC8102E.toInt(); path.addCircle(cx, cy, h * 0.18f, Path.Direction.CW) }
        }
        c.drawPath(path, p)
        if (lit) {
            p.color = lamp; p.setShadowLayer(3f * u, 0f, 0f, lamp)
            c.drawCircle(R.centerX(), R.top - 5f * u, 2.2f * u, p); p.clearShadowLayer()
        }
    }

    private fun tri(x: Float, y: Float, h: Float, right: Boolean) {
        val w = h * 0.18f * (if (right) 1 else -1)
        path.moveTo(x, y - h * 0.2f); path.lineTo(x + w, y); path.lineTo(x, y + h * 0.2f); path.close()
    }

    /** The fluorescent display between the decks: deck 1's mode and counter, deck 2's, the title running along. */
    private fun vfd(c: Canvas) {
        val D = display
        p.shader = null; p.color = 0xFF060403.toInt(); c.drawRoundRect(D, 4 * u, 4 * u, p)
        s.strokeWidth = 1f * u; s.color = 0xFF3A3D42.toInt(); c.drawRoundRect(D, 4 * u, 4 * u, s)
        val a = power
        val m = Deck.mode
        val mode = when (m) { Deck.Mode.PLAY -> "PLAY"; Deck.Mode.FF -> "FF"; Deck.Mode.REW -> "REW"; Deck.Mode.AMS -> "SRCH"; else -> "STOP" }
        val w = D.width()
        val sm = min(11f * u, w / 12f); val big = min(24f * u, w / 6f)
        seg(c, "1 $mode", D.left + 8 * u, D.top + 8 * u, sm, AMBER.a(a))
        seg(c, Deck.sideLetter.toString(), D.right - 8 * u - sm * 0.6f, D.top + 8 * u, sm, AMBER.a(a))
        seg(c, Deck.counter().toString().padStart(3, '0'), D.left + 8 * u, D.top + 14 * u + sm, big, AMBER.a(a))
        // deck 2: the mixtape waiting there, by how many songs it holds
        val t2 = deck2Tape()
        val n2 = if (t2 === Library.BLANK) "REC" else (t2.a.size + t2.b.size).toString().padStart(2, '0')
        seg(c, n2, D.right - 8 * u - (big * 0.6f * 1.32f) * n2.length + big * 0.6f * 0.32f, D.top + 14 * u + sm, big, (if (t2 === Library.BLANK) RED else AMBER).a(a))
        // the title stepping along
        val cur = Deck.current()
        val txt = ((cur.track?.number ?: 0).toString().padStart(2, '0') + " " + (cur.track?.title ?: "")).uppercase()
        val th = min(8f * u, w / 18f)
        val room = ((w - 16 * u) / (th * 0.6f * 1.32f)).toInt().coerceAtLeast(1)
        val shown = if (txt.length > room) {
            val run = "$txt    $txt    "
            val step = ((SystemClock.uptimeMillis() / 260) % (txt.length + 4)).toInt()
            run.substring(step, (step + room).coerceAtMost(run.length))
        } else txt
        seg(c, shown, D.left + 8 * u, D.bottom - 8 * u - th, th, AMBER.a(0.85f * a))
        p.shader = LinearGradient(D.left, D.top, D.right, D.bottom, intArrayOf(Color.WHITE.a(0.14f), Color.WHITE.a(0.02f), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(D, 4 * u, 4 * u, p); p.shader = null
    }

    /** Fluorescent starburst characters, glowing. [h] is the character height. */
    private fun seg(c: Canvas, text: String, x: Float, y: Float, h: Float, col: Int) {
        val cw = h * 0.6f
        val lw = cw * 0.17f; val gp = lw * 0.6f
        s.shader = null; s.strokeCap = Paint.Cap.ROUND; s.strokeWidth = lw; s.color = col
        s.setShadowLayer(lw * 1.6f, 0f, 0f, col)
        for ((i, ch) in text.withIndex()) {
            val x0 = x + i * cw * 1.32f
            for (name in painter.litSegments(ch)) {
                val sg = painter.SEGS[name] ?: continue
                val ax = x0 + sg[0] * cw; val ay = y + sg[1] * h; val bx = x0 + sg[2] * cw; val by = y + sg[3] * h
                val dx = bx - ax; val dy = by - ay; val len = hypot(dx, dy); val ux = dx / len; val uy = dy / len
                c.drawLine(ax + ux * gp, ay + uy * gp, bx - ux * gp, by - uy * gp, s)
            }
        }
        s.clearShadowLayer(); s.strokeCap = Paint.Cap.BUTT
    }

    // ---- the amplifier --------------------------------------------------------------------------
    private fun amp(c: Canvas) {
        val ay = ampR.top
        label(c, "STEREO AMPLIFIER", X(22f), ay + 16f * u, 7f * u, GOLD, 0.6f, Paint.Align.LEFT, 2f * u)
        // what is playing: a lamp for a tape, one for a mixtape
        val mix = Deck.tape?.mix == true
        lamp(c, X(30f), ay + 170f * u * 0.27f, "TAPE", !mix && Deck.mode == Deck.Mode.PLAY)
        lamp(c, X(30f), ay + 170f * u * 0.4f, "MIXTAPE", mix && Deck.mode == Deck.Mode.PLAY)
        toggle(c, bassSw, "MEGA BASS", Deck.megaBass)
        toggle(c, revSw, "AUTO REV", Deck.autoRev)
        // setup: behind it, the settings
        val su = setupBtn
        p.shader = null; p.color = 0xFF1A1C1F.toInt(); c.drawRoundRect(su, 2 * u, 2 * u, p)
        s.strokeWidth = 0.8f * u; s.color = GOLD.a(0.5f); c.drawRoundRect(su, 2 * u, 2 * u, s)
        label(c, "SETUP", su.centerX(), su.centerY() + 2.6f * u, 7f * u, GOLD, 0.8f, Paint.Align.CENTER, 1.5f * u)

        // the meters: a chrome bezel, two bars of orange segments, red above 0 dB
        val M = meters
        p.shader = LinearGradient(0f, M.top - 4 * u, 0f, M.bottom + 4 * u, intArrayOf(0xFFF4F5F6.toInt(), 0xFF9AA0A6.toInt(), 0xFF5D6268.toInt(), 0xFFC9CDD1.toInt()), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
        r.set(M.left - 4 * u, M.top - 4 * u, M.right + 4 * u, M.bottom + 4 * u); c.drawRoundRect(r, 3 * u, 3 * u, p)
        p.shader = null; p.color = 0xFF070505.toInt(); c.drawRoundRect(M, 2 * u, 2 * u, p)
        val n = 28
        for ((i, pair) in listOf(Triple("L", mL, pkL), Triple("R", mR, pkR)).withIndex()) {
            val (ch, lvl, pk) = pair
            val by = M.top + M.height() * (0.22f + i * 0.42f); val bh = M.height() * 0.2f
            label(c, ch, M.left + 8 * u, by + bh * 0.85f, 9f * u, AMBER, 0.8f * power + 0.1f, Paint.Align.LEFT, 0f)
            val seg = (M.width() - 34 * u) / n
            val pki = (pk * n).toInt().coerceAtMost(n - 1)
            for (k in 0 until n) {
                val on = k.toFloat() / n < lvl || (k == pki && pk > 0.03f)
                val col = if (k > n * 0.82f) RED else AMBER
                p.color = col.a(if (on) 1f else 0.1f)
                if (on) p.setShadowLayer(3f * u, 0f, 0f, col) else p.clearShadowLayer()
                c.drawRect(M.left + 24 * u + k * seg, by, M.left + 24 * u + k * seg + seg - 2 * u, by + bh, p)
            }
            p.clearShadowLayer()
        }
        label(c, "-40    -20    -10    -6    -3    0    +3 dB", M.centerX(), M.bottom + 16 * u, 7f * u, GOLD, 0.55f, Paint.Align.CENTER, 1f * u)
        p.shader = LinearGradient(M.left, M.top, M.right, M.bottom, intArrayOf(Color.WHITE.a(0.14f), Color.WHITE.a(0.02f), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(M, 2 * u, 2 * u, p); p.shader = null

        // the volume knob: champagne, spun, its mark turning with the phone's volume
        label(c, "VOLUME", volKnob.centerX(), ay + 16f * u, 7f * u, GOLD, 0.7f, Paint.Align.CENTER, 2f * u)
        knob(c, volKnob.centerX(), volKnob.centerY(), volKnob.width() / 2, volume())
    }

    private fun lamp(c: Canvas, x: Float, y: Float, text: String, on: Boolean) {
        p.shader = null; p.color = if (on) AMBER else 0xFF3A2A15.toInt()
        if (on) p.setShadowLayer(4f * u, 0f, 0f, AMBER)
        c.drawCircle(x, y, 3f * u, p); p.clearShadowLayer()
        label(c, text, x + 9 * u, y + 3 * u, 7f * u, GOLD, 0.75f, Paint.Align.LEFT, 1.5f * u)
    }

    private fun toggle(c: Canvas, R: RectF, text: String, on: Boolean) {
        p.shader = null; p.color = 0xFF111111.toInt(); c.drawRoundRect(R, R.height() / 2, R.height() / 2, p)
        s.strokeWidth = 0.8f * u; s.color = GOLD.a(0.5f); c.drawRoundRect(R, R.height() / 2, R.height() / 2, s)
        val kx = if (on) R.right - R.height() / 2 else R.left + R.height() / 2
        p.shader = RadialGradient(kx - R.height() * 0.15f, R.centerY() - R.height() * 0.2f, R.height() * 0.6f, intArrayOf(0xFFFFF3D8.toInt(), GOLD, 0xFF8A734C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(kx, R.centerY(), R.height() * 0.42f, p); p.shader = null
        if (on) { p.color = AMBER; p.setShadowLayer(3f * u, 0f, 0f, AMBER); c.drawCircle(R.right + 7 * u, R.centerY(), 2f * u, p); p.clearShadowLayer() }
        label(c, text, R.right + 14 * u, R.centerY() + 2.6f * u, 7f * u, GOLD, 0.75f, Paint.Align.LEFT, 1.5f * u)
    }

    private fun knob(c: Canvas, cx: Float, cy: Float, rad: Float, vol: Float) {
        s.shader = null; s.strokeWidth = 1.2f * u
        for (i in 0..20) {
            val a = Math.toRadians(135.0 + i * 13.5)
            val r0 = rad + 6 * u; val r1 = rad + (if (i % 5 == 0) 11 else 9) * u
            s.color = (if (i / 20f <= vol + 0.001f) AMBER else GOLD).a(if (i / 20f <= vol + 0.001f) 0.9f * power + 0.1f else 0.5f)
            c.drawLine(cx + cos(a).toFloat() * r0, cy + sin(a).toFloat() * r0, cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, s)
        }
        p.shader = null; p.color = Color.BLACK.a(0.5f); p.setShadowLayer(5f * u, 0f, 3f * u, Color.BLACK.a(0.7f)); c.drawCircle(cx, cy, rad, p); p.clearShadowLayer()
        p.shader = RadialGradient(cx - rad * 0.35f, cy - rad * 0.4f, rad * 1.8f, intArrayOf(0xFFE9D6AD.toInt(), 0xFF9C855B.toInt(), 0xFF4C3F2A.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, p)
        p.shader = RadialGradient(cx - rad * 0.3f, cy - rad * 0.35f, rad * 1.5f, intArrayOf(0xFFFFF3D8.toInt(), GOLD, 0xFF8A734C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 0.84f, p)
        // spun finish: fine concentric rings
        s.strokeWidth = 0.6f * u
        var rr = rad * 0.1f
        var odd = false
        while (rr < rad * 0.84f) { s.color = (if (odd) Color.WHITE.a(0.1f) else Color.BLACK.a(0.06f)); c.drawCircle(cx, cy, rr, s); rr += 1.6f * u; odd = !odd }
        p.shader = null
        val a = Math.toRadians(135.0 + vol * 270.0)
        p.color = 0xFF222222.toInt()
        c.drawCircle(cx + cos(a).toFloat() * rad * 0.62f, cy + sin(a).toFloat() * rad * 0.62f, rad * 0.07f, p)
    }

    // ---- the drawer of tapes ------------------------------------------------------------------
    private fun drawer(c: Canvas) {
        label(c, "THE SHELF", X(22f), drawerR.top + 18f * u, 9f * u, GOLD, 0.9f, Paint.Align.LEFT, 4f * u)
        t.typeface = hand; t.textSize = 15f * u; t.color = GOLD; t.textAlign = Paint.Align.RIGHT; t.letterSpacing = 0f; t.clearShadowLayer()
        c.drawText("${Library.tapes.size} tapes", X(SW - 22f), drawerR.top + 19f * u, t)
        val T = tray
        p.shader = null; p.color = 0xFF0A0B0D.toInt(); c.drawRoundRect(T, 2 * u, 2 * u, p)
        s.strokeWidth = 1f * u; s.color = 0xFF2B2E33.toInt(); c.drawRoundRect(T, 2 * u, 2 * u, s)
        c.save(); c.clipRect(T)
        val sw = 47f * u
        val tapes = Library.tapes
        spines.clear()
        val first = (spineScroll / sw).toInt().coerceAtLeast(0)
        var i = first
        while (i < tapes.size) {
            val x0 = T.left + 8 * u + i * sw - spineScroll
            if (x0 > T.right) break
            val tp = tapes[i]
            val R = RectF(x0, T.top + 8 * u, x0 + sw - 3 * u, T.bottom - 8 * u)
            spines += tp to R
            spine(c, tp, R, tp === Deck.tape)
            i++
        }
        p.shader = LinearGradient(0f, T.top, 0f, T.top + T.height() * 0.3f, Color.BLACK.a(0.7f), 0, Shader.TileMode.CLAMP); c.drawRect(T, p)
        // the ends fade into the drawer's depth
        p.shader = LinearGradient(T.left, 0f, T.left + 18 * u, 0f, Color.BLACK.a(0.8f), 0, Shader.TileMode.CLAMP); c.drawRect(T, p)
        p.shader = LinearGradient(T.right - 18 * u, 0f, T.right, 0f, 0, Color.BLACK.a(0.8f), Shader.TileMode.CLAMP); c.drawRect(T, p)
        p.shader = null
        c.restore()
        // the pull: the whole shelf comes out
        p.shader = LinearGradient(0f, handle.top, 0f, handle.bottom, intArrayOf(0xFFF4F5F6.toInt(), 0xFF9AA0A6.toInt(), 0xFF5D6268.toInt(), 0xFFC9CDD1.toInt()), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(handle, handle.height() / 2, handle.height() / 2, p); p.shader = null
    }

    private fun spine(c: Canvas, tp: Tape, R: RectF, inDeck: Boolean) {
        val sh = tp.shell
        val a = if (inDeck) 0.25f else 1f
        p.shader = null; p.color = sh.body.a(a); c.drawRoundRect(R, 2 * u, 2 * u, p)
        s.strokeWidth = 1f * u; s.color = Color.BLACK.a(0.6f * a); c.drawRoundRect(R, 2 * u, 2 * u, s)
        r.set(R.left + 3 * u, R.top + 6 * u, R.right - 3 * u, R.bottom - 6 * u)
        p.color = sh.labelBg.a(a); c.drawRoundRect(r, 1 * u, 1 * u, p)
        p.color = sh.accent.a(a); c.drawRect(r.left, r.top, r.right, r.top + 5 * u, p)
        c.save(); c.translate(R.centerX() + 4f * u, R.centerY() + 3 * u); c.rotate(-90f)
        painter.handwrite(c, tp.label, 0f, 0f, min(13f * u, R.width() * 0.4f), r.height() - 10 * u, INK.a(a), Paint.Align.CENTER)
        c.restore()
    }

    // ---- speakers -----------------------------------------------------------------------------
    private fun speaker(c: Canvas, R: RectF) {
        p.shader = null; p.color = Color.BLACK.a(0.6f); p.setShadowLayer(6f * u, 0f, 3f * u, Color.BLACK.a(0.8f))
        c.drawRect(R.left + 6 * u, R.bottom - 6 * u, R.right - 6 * u, R.bottom, p); p.clearShadowLayer()
        // cherry veneer
        p.shader = LinearGradient(R.left, R.top, R.right, R.bottom, intArrayOf(0xFF8E3B22.toInt(), 0xFF6A2614.toInt(), 0xFF4A180C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(R, 4 * u, 4 * u, p)
        c.save(); c.clipRect(R)
        s.shader = null; s.strokeWidth = 0.7f * u
        var gy = R.top + 3 * u; var k = 0
        while (gy < R.bottom) {
            s.color = if (k % 3 == 0) 0x26C96A44 else 0x332A0D05
            path.reset(); path.moveTo(R.left, gy)
            path.cubicTo(R.left + R.width() * 0.3f, gy - 2 * u, R.left + R.width() * 0.6f, gy + 3 * u, R.right, gy + 1 * u)
            c.drawPath(path, s); gy += 5.5f * u; k++
        }
        c.restore()
        p.shader = LinearGradient(R.left, R.top, R.left + R.width(), R.top + R.height() * 0.5f, Color.WHITE.a(0.1f), 0, Shader.TileMode.CLAMP)
        c.drawRoundRect(R, 4 * u, 4 * u, p); p.shader = null
        val cx = R.centerX()
        // tweeter
        val tw = R.width() * 0.36f; val ty = R.top + R.height() * 0.12f
        p.color = 0xFF121212.toInt(); r.set(cx - tw / 2, ty, cx + tw / 2, ty + tw); c.drawRoundRect(r, 3 * u, 3 * u, p)
        cone(c, cx, ty + tw / 2, tw * 0.3f, 1f, (mL + mR) * 0.5f * 0.3f)
        p.color = 0xFF3B3B3B.toInt(); c.drawCircle(cx, ty + tw / 2, tw * 0.12f, p)
        // woofer: the cone moves with the bass
        val wr = R.width() * 0.4f; val wy = R.top + R.height() * 0.6f
        p.color = 0xFF111111.toInt(); r.set(cx - wr - 6 * u, wy - wr - 6 * u, cx + wr + 6 * u, wy + wr + 6 * u); c.drawRoundRect(r, wr * 0.35f, wr * 0.35f, p)
        p.color = 0xFF1A1A1A.toInt(); c.drawCircle(cx, wy, wr, p)
        val push = mB * mB
        p.shader = RadialGradient(cx - wr * 0.3f, wy - wr * 0.3f, wr * 1.1f, 0xFF3A3A3A.toInt(), 0xFF0A0A0A.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, wy, wr * 0.92f, p); p.shader = null
        cone(c, cx, wy, wr * 0.78f * (1 + push * 0.035f), 1f + push * 0.5f, push)
        p.shader = RadialGradient(cx - wr * 0.08f, wy - wr * 0.09f - push * wr * 0.02f, wr * 0.3f, intArrayOf(0xFF9A9A9A.toInt(), 0xFF2A2A2A.toInt(), 0xFF090909.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, wy, wr * 0.24f * (1 + push * 0.06f), p); p.shader = null
        for ((sx, sy) in listOf(cx - wr - 1 to wy - wr - 1, cx + wr + 1 to wy - wr - 1, cx - wr - 1 to wy + wr + 1, cx + wr + 1 to wy + wr + 1)) {
            p.shader = RadialGradient(sx - u, sy - u, 3.5f * u, 0xFFF1F3F5.toInt(), 0xFF5D646C.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(sx, sy, 2.6f * u, p); p.shader = null
        }
        // the badge
        r.set(cx - 18 * u, R.bottom - 26 * u, cx + 18 * u, R.bottom - 16 * u); p.color = GOLD; c.drawRoundRect(r, 5 * u, 5 * u, p)
        label(c, "NASTRO", cx, R.bottom - 18.5f * u, 6f * u, 0xFF2A2016.toInt(), 1f, Paint.Align.CENTER, 1.5f * u)
    }

    /** A paper cone: lit from the upper left; pushed out by the bass, its highlight grows. */
    private fun cone(c: Canvas, cx: Float, cy: Float, rad: Float, gain: Float, push: Float) {
        p.shader = RadialGradient(cx - rad * 0.2f, cy - rad * 0.25f, rad * 1.05f,
            intArrayOf(lighten(0xFF4A4A4A.toInt(), push * 0.25f), 0xFF151515.toInt(), 0xFF050505.toInt()), floatArrayOf(0f, (0.5f * gain).coerceAtMost(0.9f), 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, p); p.shader = null
    }

    private fun lighten(col: Int, amt: Float): Int {
        val a = amt.coerceIn(0f, 1f)
        return Color.rgb((Color.red(col) + (255 - Color.red(col)) * a).toInt(), (Color.green(col) + (255 - Color.green(col)) * a).toInt(), (Color.blue(col) + (255 - Color.blue(col)) * a).toInt())
    }

    // ---- a tape on its way into deck 1 ----------------------------------------------------------
    private fun flight(c: Canvas, lifting: Boolean) {
        val pend = Deck.pending ?: return
        if (!lifting || flightFrom.isEmpty) return
        val tt = easeOut(((SystemClock.uptimeMillis() - Deck.pendingSince) / Deck.FLIGHT_MS.toFloat()).coerceIn(0f, 1f))
        val k1 = min(win1.width() * 0.9f / PlayerGeom.CW, win1.height() * 0.86f / PlayerGeom.CH)
        // from the drawer it comes out on end, so it turns as it flies; from deck 2 it is already upright
        val onEnd = flightFrom.height() > flightFrom.width()
        val k0 = if (onEnd) flightFrom.height() / PlayerGeom.CW else flightFrom.width() / PlayerGeom.CW
        val x = flightFrom.centerX() + (win1.centerX() - flightFrom.centerX()) * tt
        val y = flightFrom.centerY() + (win1.centerY() - flightFrom.centerY()) * tt - sin(tt * PI).toFloat() * dp(40f)
        c.save(); c.translate(x, y)
        if (onEnd) c.rotate(-90f * (1 - tt))
        val kk = (k0 + (k1 - k0) * tt) * (1 + sin(tt * PI).toFloat() * 0.08f)
        c.scale(kk, kk)
        painter.cassette(c, pend, false, 1f, shadow = 6f)
        c.restore()
    }

    private fun easeOut(v: Float): Float { val q = 1 - v.coerceIn(0f, 1f); return 1 - q * q * q }

    // ---- type ---------------------------------------------------------------------------------
    private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, col: Int, alpha: Float, align: Paint.Align, track: Float) {
        t.typeface = printS; t.textSize = size; t.color = col.a(alpha); t.textAlign = align
        t.letterSpacing = if (size > 0) track / size else 0f
        t.clearShadowLayer()
        c.drawText(text, x, y, t)
        t.letterSpacing = 0f
    }

    // ---- touch --------------------------------------------------------------------------------
    private var downX = 0f; private var downY = 0f
    private var dragging: String? = null       // "vol", "spines", "win1", "win2"
    private var volFrom = 0f; private var volAngle = 0f
    private var scrollFrom = 0f; private var moved = 0f
    private var vt: android.view.VelocityTracker? = null

    private fun RectF.hit(x: Float, y: Float, pad: Float = 4f * u) = x > left - pad && x < right + pad && y > top - pad && y < bottom + pad

    private fun press(id: String) { pressedId = id; pressedAt = SystemClock.uptimeMillis() }

    fun down(e: android.view.MotionEvent): Boolean {
        val x = e.x; val y = e.y
        downX = x; downY = y; moved = 0f; dragging = null
        for (K in keys1) if (K.rect.hit(x, y)) {
            press(K.id)
            when (K.id) {
                // eject opens the door; with it already open, the shelf comes out
                "eject" -> if (o1 < 0.5f) reopen(1) else { Sfx.play(Sfx.S.DOOR_OPEN, 0.6f); onOpenShelf?.invoke() }
                "rew" -> onKey?.invoke(Deck.Key.REW)
                "ff" -> onKey?.invoke(Deck.Key.FF)
                // stopped, stop has nothing to do: the hi-fi's doors are not the Walkman's
                "stop" -> if (Deck.mode != Deck.Mode.STOP) onKey?.invoke(Deck.Key.STOP)
                "play" -> { shut(1); onKey?.invoke(Deck.Key.PLAY) }
            }
            return true
        }
        for (K in keys2) if (K.rect.hit(x, y)) {
            press(K.id); Sfx.play(Sfx.S.WIND_KEY, 0.5f); onHaptic?.invoke(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val t2 = deck2Tape()
            when (K.id) {
                "rec" -> onNewMixtape?.invoke()
                "play2" -> if (t2 !== Library.BLANK) { shut(2); flightFrom.set(win2); Deck.load(t2, direct = true) } else onNewMixtape?.invoke()
                "edit" -> if (o2 < 0.5f) reopen(2) else if (t2 !== Library.BLANK) onEditMixtape?.invoke(t2) else onNewMixtape?.invoke()
            }
            return true
        }
        if (mixBtn.hit(x, y, 8f * u)) { press("mix"); Sfx.play(Sfx.S.WIND_KEY, 0.5f); onHaptic?.invoke(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onNewMixtape?.invoke(); return true }
        if (bassSw.hit(x, y, 8f * u) || hitLabel(bassSw, x, y)) { Sfx.play(Sfx.S.STOP, 0.4f); Deck.toggle("megaBass"); return true }
        if (revSw.hit(x, y, 8f * u) || hitLabel(revSw, x, y)) { Sfx.play(Sfx.S.STOP, 0.4f); Deck.toggle("autoRev"); return true }
        if (setupBtn.hit(x, y, 8f * u)) { Sfx.play(Sfx.S.DOOR_OPEN, 0.5f); onSettings?.invoke(); return true }
        if (hypot(x - volKnob.centerX(), y - volKnob.centerY()) < volKnob.width() * 0.75f) {
            dragging = "vol"; volFrom = volume(); volAngle = atan2(y - volKnob.centerY(), x - volKnob.centerX()); return true
        }
        if (handle.hit(x, y, 10f * u)) { Sfx.play(Sfx.S.DOOR_OPEN, 0.6f); onOpenShelf?.invoke(); return true }
        if (tray.hit(x, y, 0f)) {
            dragging = "spines"; scrollFrom = spineScroll; scroller.forceFinished(true)
            vt?.recycle(); vt = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
            return true
        }
        if (door1.hit(x, y, 0f)) { dragging = "win1"; return true }
        if (door2.hit(x, y, 0f)) { dragging = "win2"; return true }
        return true
    }

    private fun hitLabel(R: RectF, x: Float, y: Float) = x > R.right && x < R.right + 70 * u && y > R.top - 4 * u && y < R.bottom + 4 * u

    fun move(e: android.view.MotionEvent) {
        val x = e.x; val y = e.y
        moved = max(moved, hypot(x - downX, y - downY))
        when (dragging) {
            "vol" -> {
                // turn it round, as a knob is turned; a straight drag up or down works too
                val a = atan2(y - volKnob.centerY(), x - volKnob.centerX())
                var da = a - volAngle
                if (da > PI) da -= (2 * PI).toFloat(); if (da < -PI) da += (2 * PI).toFloat()
                volAngle = a
                volFrom = (volFrom + da / (1.5f * PI.toFloat())).coerceIn(0f, 1f)
                setVolume(volFrom)
            }
            "spines" -> {
                vt?.addMovement(e)
                spineScroll = (scrollFrom - (x - downX)).coerceIn(0f, maxSpine())
            }
        }
    }

    private fun maxSpine() = max(0f, Library.tapes.size * 47f * u + 16 * u - tray.width())

    fun up(e: android.view.MotionEvent) {
        val x = e.x; val y = e.y
        val dx = x - downX; val dy = y - downY
        when (dragging) {
            "spines" -> {
                if (moved < dp(8f)) {
                    spines.firstOrNull { it.second.contains(x, y) }?.let { (tp, R) ->
                        flightFrom.set(R); shut1 = true; Deck.load(tp, direct = true)
                    }
                } else {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    scroller.fling(spineScroll.toInt(), 0, (-v).toInt(), 0, 0, maxSpine().toInt(), 0, 0)
                }
                vt?.recycle(); vt = null
            }
            "win1" -> {
                if (abs(dx) > dp(40f) && abs(dx) > abs(dy) * 1.3f) Deck.turnOver()
                else if (moved < dp(8f) && o1 > 0.3f) shut(1)        // push the open door shut
            }
            "win2" -> {
                if (abs(dx) > dp(40f) && abs(dx) > abs(dy) * 1.3f) {
                    // step through the mixtapes waiting by deck 2
                    val n = mixes().size
                    d2 = ((d2 + (if (dx < 0) 1 else -1)) % n + n) % n
                    Sfx.play(Sfx.S.SEAT, 0.35f); onHaptic?.invoke(android.view.HapticFeedbackConstants.CLOCK_TICK)
                } else if (moved < dp(8f) && o2 > 0.3f) {
                    shut(2)
                } else if (moved < dp(8f)) {
                    val t2 = deck2Tape()
                    if (t2 === Library.BLANK) onNewMixtape?.invoke() else onEditMixtape?.invoke(t2)
                }
            }
        }
        dragging = null
    }

    /** A mixtape was just made: show it in deck 2. */
    fun showMixtape(id: Long?) {
        val m = mixes()
        d2 = m.indexOfFirst { it.albumId == id }.takeIf { it >= 0 } ?: 0
    }
}
