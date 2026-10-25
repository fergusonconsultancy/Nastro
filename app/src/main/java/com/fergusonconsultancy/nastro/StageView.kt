package com.fergusonconsultancy.nastro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The whole screen. Folded, it is the player: a closed, sculpted face with only the hubs showing
 * through a slim lens. Unfolded, the door has swung open on the crease: the right half is the
 * compartment, with the tape as large as the screen allows, and the left half is the inside of
 * the door, where the shelf is kept. Opening the phone is opening the Walkman.
 */
class StageView(ctx: Context) : View(ctx) {
    private val painter = Painter(ctx)
    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    var needsPermission = false
    var onAskPermission: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private var volAt = 0L
    private val live = Painter.Live()
    private val settingsLink = RectF()

    private var open = false                 // unfolded: the compartment is open
    private var swing = 1f                   // the door swinging open after unfolding, 0..1
    private var swingFrom = 0L
    private var view = 0f                    // folded: 0 the face, 1 panned to the shelf
    private var viewTarget = 0f
    private var shake = 0f
    private var lastFrame = 0L

    private var deck: PlayerGeom? = null
    private var face: PlayerGeom? = null           // the face over the right half, for the swing
    private val shelfArea = RectF()
    private var slots: List<Painter.Slot> = emptyList()
    private var lean = FloatArray(0)
    private var leanV = FloatArray(0)
    private var shelfScroll = 0f
    private var shelfContent = 0f
    private val scroller = OverScroller(ctx)

    private var pressed: Deck.Key? = null
    private var pressedAt = 0L
    private val proximity = HashMap<String, Float>()

    private val vib = ctx.getSystemService(Vibrator::class.java)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = painter.ui; color = 0xFFE6EBF0.toInt(); textAlign = Paint.Align.CENTER }

    private val changed = { invalidate() }

    init {
        setBackgroundColor(0xFF07090B.toInt())
        Deck.onEvent = { e ->
            when (e) {
                Deck.Event.KEY -> haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                Deck.Event.FLIP -> { haptic(HapticFeedbackConstants.CLOCK_TICK); Sfx.play(Sfx.S.FLIP, 0.5f) }
                Deck.Event.REFUSED -> haptic(HapticFeedbackConstants.REJECT)
                Deck.Event.CLUNK -> { Sfx.play(Sfx.S.SEAT); clunk() }
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Deck.listen(changed); Prefs.listen(changed) }
    override fun onDetachedFromWindow() { Deck.unlisten(changed); Prefs.unlisten(changed); super.onDetachedFromWindow() }

    private fun clunk() {
        shake = 1f
        if (Prefs.haptics) runCatching { vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
        invalidate()
    }

    private fun haptic(kind: Int) { if (Prefs.haptics) performHapticFeedback(kind) }

    fun libraryChanged() { slots = emptyList(); letters = emptyList(); layoutShelf(); invalidate() }

    /** Back on the folded screen with the shelf showing: return to the face. */
    fun back(): Boolean {
        if (!open && viewTarget > 0.5f) { Deck.closeDoor(); invalidate(); return true }
        return false
    }

    // ---- layout --------------------------------------------------------------------------------
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val nowOpen = w > h * 0.8f
        if (nowOpen && !open && oldw != 0) {
            // Unfolding: the door pops on its spring and swings open over the left half
            swing = if (Prefs.doorSwing) 0f else 1f; swingFrom = SystemClock.uptimeMillis()
            Deck.doorShutQuietly(); view = 0f; viewTarget = 0f
            Sfx.play(Sfx.S.DOOR_OPEN, 0.8f)
        } else if (!nowOpen && open) {
            // Folding: the door has shut; the face appears with the clunk of the catch
            swing = 1f; view = 0f; viewTarget = 0f
            post { Sfx.play(Sfx.S.DOOR_SHUT, 0.8f); clunk() }
        }
        open = nowOpen
        layoutAll()
    }

    private fun layoutAll() {
        val L = paddingLeft.toFloat(); val T = paddingTop.toFloat()
        val W = (width - paddingLeft - paddingRight).toFloat(); val H = (height - paddingTop - paddingBottom).toFloat()
        if (W <= 0 || H <= 0) return
        if (open) {
            val half = width / 2f
            val m = (width - paddingRight - half) * 0.045f
            val bw = width - paddingRight - half - m * 2
            deck = PlayerGeom.bay(half + m, T + m, bw, H - m * 2)
            face = PlayerGeom.face(half + m, T + m, bw, H - m * 2)
            shelfArea.set(L + dp(12f), T + dp(12f), half - dp(14f), T + H - dp(12f))
        } else {
            val v = easeOut(view)
            deck = PlayerGeom.face(L + W * 0.05f + v * W * 1.05f, T + H * 0.03f, W * 0.9f, H * 0.94f)
            shelfArea.set(L + dp(10f) - (1 - v) * W * 1.05f, T + dp(10f), L + W - dp(10f) - (1 - v) * W * 1.05f, T + H - dp(10f))
        }
        layoutShelf()
    }

    private fun layoutShelf() {
        val tapes = Library.tapes
        cols = if (shelfArea.width() > dp(340f)) 2 else 1
        // room on the hinge side for the index
        val gap = max(dp(10f), shelfArea.width() * 0.035f)
        rail.set(shelfArea.right - dp(30f), shelfArea.top + dp(24f), shelfArea.right - dp(2f), shelfArea.bottom - dp(24f))
        val cw = (shelfArea.width() - dp(22f) - gap * (cols + 1)) / cols
        val ch = cw * PlayerGeom.CH / PlayerGeom.CW
        val head = dp(70f)
        // Updated in place each frame; rebuilt only when the library changes
        if (slots.size != tapes.size || slots.firstOrNull()?.tape !== tapes.firstOrNull()) slots = tapes.mapIndexed { i, t -> Painter.Slot(t, i, RectF()) }
        for (sl in slots) {
            val i = sl.index
            val x0 = shelfArea.left + gap + (i % cols) * (cw + gap)
            val y0 = shelfArea.top + head + (i / cols) * (ch + gap) - shelfScroll
            sl.rect.set(x0, y0, x0 + cw, y0 + ch)
        }
        rowH = ch + gap
        shelfContent = head + ((tapes.size + cols - 1) / cols) * (ch + gap) + gap
        if (letters.isEmpty() && tapes.isNotEmpty()) buildIndex()
        if (lean.size != tapes.size) { lean = FloatArray(tapes.size); leanV = FloatArray(tapes.size) }
    }

    private fun maxScroll() = max(0f, shelfContent - shelfArea.height())

    // ---- the index in the door's trim ---------------------------------------------------------
    private var letters: List<Char> = emptyList()
    private var firstOf: Map<Char, Int> = emptyMap()
    private var indexing = false
    private var indexLetter: Char? = null
    private var railWake = 0f
    private var railTouchedAt = 0L
    private var cols = 1
    private var rowH = 0f
    private val rail = RectF()

    private fun buildIndex() {
        val map = LinkedHashMap<Char, Int>()
        // Newest-first has no alphabet to index
        if (Prefs.order != "newest") Library.tapes.forEachIndexed { i, t ->
            val ch = if (Prefs.order == "album") t.album.lowercase().removePrefix("the ").firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#' else t.indexLetter
            if (ch !in map) map[ch] = i
        }
        firstOf = map; letters = map.keys.toList()
    }

    private fun railZone(x: Float, y: Float) = x > shelfArea.right - dp(40f) && x <= shelfArea.right + dp(8f) && y > shelfArea.top && y < shelfArea.bottom

    private fun indexAt(y: Float) {
        if (letters.isEmpty()) return
        val i = (((y - rail.top) / rail.height()) * letters.size).toInt().coerceIn(0, letters.size - 1)
        val ch = letters[i]
        if (ch != indexLetter) {
            indexLetter = ch
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            val first = firstOf[ch] ?: 0
            scroller.forceFinished(true)
            shelfScroll = ((first / cols) * rowH).coerceIn(0f, maxScroll())
        }
        railTouchedAt = SystemClock.uptimeMillis()
    }

    private fun drawIndex(c: Canvas, now: Long) {
        val target = if (indexing || now - railTouchedAt < 1200) 1f else 0f
        railWake += (target - railWake) * 0.25f
        if (railWake < 0.01f) return
        painter.indexRail(c, rail, letters, railWake, indexLetter)
        if (indexing) indexLetter?.let { painter.bigLetter(c, shelfArea, it, railWake) }
    }

    // ---- drawing -------------------------------------------------------------------------------
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        Deck.tick(now)

        if (needsPermission) { askScreen(c); return }
        if (Library.loaded && Library.tapes.isEmpty()) { message(c, "No albums on this phone yet", "Nastro tapes every album it finds in your music library."); return }
        if (Deck.tape == null) { message(c, "Loading the shelf", ""); postInvalidateOnAnimation(); return }

        // springs and pans
        if (scroller.computeScrollOffset()) shelfScroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
        if (!open) {
            // Folded, the shelf shows while the door is open or a tape is on its way in
            val lifting = Deck.pending != null && Deck.tape !== Deck.pending
            viewTarget = if ((Deck.doorOpening && Deck.pending == null) || lifting) 1f else 0f
            view += (viewTarget - view) * (1 - exp(-dt / 0.14f))
            if (abs(view - viewTarget) < 0.001f) view = viewTarget
        }
        if (swing < 1f) swing = ((now - swingFrom) / 750f).coerceIn(0f, 1f)
        for (i in lean.indices) { leanV[i] += (-lean[i] * 90 - leanV[i] * 12) * dt; lean[i] += leanV[i] * dt * 6 }
        if (shake > 0) shake = max(0f, shake - dt * 6)
        for (k in proximity.keys.toList()) proximity[k] = (proximity[k] ?: 0f) * exp(-dt / 0.5f)
        layoutAll()

        val G = deck ?: return
        c.save()
        if (shake > 0) c.translate(0f, (sin(now / 12.0) * shake * dp(1.6f)).toFloat())
        val lifting = Deck.pending != null && Deck.tape !== Deck.pending
        live.pressed = pressed; live.pressAge = now - pressedAt; live.proximity = proximity; live.lifting = lifting
        live.volume = volume(); live.volAge = now - volAt
        if (open) {
            if (swing >= 0.999f) {
                drawShelf(c)
                painter.bay(c, G, live)
            } else drawSwing(c, G, now, lifting)
        } else {
            drawShelf(c)
            painter.face(c, G, live)
        }
        // the tape in flight from the shelf
        val pend = Deck.pending
        if (pend != null && lifting) {
            val sl = slots.firstOrNull { it.tape === pend }
            if (sl != null) {
                val tt = easeOut(((now - Deck.pendingSince) / Deck.FLIGHT_MS.toFloat()).coerceIn(0f, 1f))
                val fx = sl.rect.centerX(); val fy = sl.rect.centerY()
                val k0 = sl.rect.width() / (PlayerGeom.CW + 4); val k1 = G.k
                c.save()
                c.translate(lerp(fx, G.tx, tt), lerp(fy, G.ty, tt) - sin(tt * PI).toFloat() * dp(30f))
                c.rotate(90f * tt)
                val kk = lerp(k0, k1, tt) * (1 + sin(tt * PI).toFloat() * 0.06f)
                c.scale(kk, kk)
                painter.cassette(c, pend, false, 1f, shadow = 6f)
                c.restore()
            }
        }
        c.restore()
        if (open) {
            // the crease
            val cx = width / 2f
            val p = Paint().apply { shader = android.graphics.LinearGradient(cx - dp(6f), 0f, cx + dp(6f), 0f, intArrayOf(0, 0x12FFFFFF, 0), null, android.graphics.Shader.TileMode.CLAMP) }
            c.drawRect(cx - dp(6f), 0f, cx + dp(6f), height.toFloat(), p)
        }

        val busy = Deck.animating() || Deck.mode == Deck.Mode.PLAY || swing < 1f || shake > 0 || abs(view - viewTarget) > 0.001f ||
            !scroller.isFinished || railWake > 0.01f || lean.any { abs(it) > 0.05f } || proximity.values.any { it > 0.02f } || (pressed != null && now - pressedAt < 200)
        if (busy) postInvalidateOnAnimation()
    }

    /** Unfolding is opening the door: the face swings on the crease out over the left half, and its inside becomes the shelf. */
    private fun drawSwing(c: Canvas, G: PlayerGeom, now: Long, lifting: Boolean) {
        val cx = width / 2f
        val th = easeInOut(swing) * PI
        val cs = cos(th).toFloat()
        val shade = Paint()
        painter.bay(c, G, live)
        shade.color = Color.BLACK.a(0.55f * (1 - swing)); c.drawRect(cx, 0f, width.toFloat(), height.toFloat(), shade)
        c.save()
        c.translate(cx, 0f); c.scale(max(0.001f, abs(cs)), 1f); c.translate(-cx, 0f)
        if (cs > 0) {
            // the face, still shut over the compartment, turning away
            face?.let { painter.face(c, it, live) }
            shade.color = Color.BLACK.a(0.5f * (1 - cs)); c.drawRect(cx, 0f, width.toFloat(), height.toFloat(), shade)
        } else {
            // the inside of the door coming down onto the left half
            drawShelf(c)
            shade.color = Color.BLACK.a(0.5f * (1 + cs)); c.drawRect(0f, 0f, cx, height.toFloat(), shade)
        }
        c.restore()
    }

    private fun drawShelf(c: Canvas) {
        painter.shelf(c, shelfArea, slots, lean, true, open, Library.tapes.size, shelfScroll)
        // the way to the settings when unfolded: on the shelf's heading, scrolling away with it
        val sy = shelfArea.top + dp(30f) - shelfScroll
        if (sy > shelfArea.top) {
            painter.label(c, "SETTINGS", shelfArea.right - dp(42f), sy, dp(12f), 0xFFD8E2EC.toInt())
            settingsLink.set(shelfArea.right - dp(130f), sy - dp(22f), shelfArea.right - dp(32f), sy + dp(10f))
        } else settingsLink.setEmpty()
        drawIndex(c, SystemClock.uptimeMillis())
    }

    private fun volume(): Float = runCatching {
        val m = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / m.toFloat()
    }.getOrDefault(0.7f)

    private fun setVolume(f: Float) {
        runCatching {
            val m = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val idx = (f * m).toInt().coerceIn(0, m)
            if (idx != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, idx, 0)
                haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        volAt = SystemClock.uptimeMillis()
    }

    private fun askScreen(c: Canvas) {
        message(c, "Nastro plays the music on this phone", "Tap anywhere to let it see your albums.")
    }

    private fun message(c: Canvas, title: String, sub: String) {
        val cx = width / 2f; val cy = height / 2f
        text.textSize = dp(20f); text.color = 0xFFE6EBF0.toInt(); c.drawText(title, cx, cy, text)
        text.textSize = dp(14f); text.color = 0xFF8D98A5.toInt(); c.drawText(sub, cx, cy + dp(28f), text)
    }

    // ---- touch ---------------------------------------------------------------------------------
    private var wind: Triple<Float, Float, Float>? = null    // hub centre x, y, last angle
    private var windLeft = true
    private var swipeFrom: Pair<Float, Float>? = null
    private var shelfFrom: Triple<Float, Float, Float>? = null  // y, scroll, max move
    private var vt: VelocityTracker? = null
    private var wasPlaying = false
    private var wheelFrom: Pair<Float, Float>? = null           // y, volume

    private fun hub(G: PlayerGeom, left: Boolean): Pair<Float, Float> {
        // shell (sx, sy) after rotate(+90°): screen = (-sy, sx) × k; the tape's open edge faces the hinge
        val sx = if (left) -21.25f else 21.25f
        return Pair(G.tx - (-2f) * G.k, G.ty + sx * G.k)
    }

    private fun RectF.near(x: Float, y: Float, pad: Float) = x > left - pad && x < right + pad && y > top - pad && y < bottom + pad

    private fun pressKey(k: Deck.Key) {
        if (!Deck.hold) Sfx.play(when (k) { Deck.Key.PLAY -> Sfx.S.PLAY; Deck.Key.STOP -> Sfx.S.STOP; else -> Sfx.S.WIND_KEY })
        val wasDoor = Deck.doorOpening
        Deck.press(k)
        if (!wasDoor && Deck.doorOpening) postDelayed({ Sfx.play(Sfx.S.DOOR_OPEN) }, 90)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        if (needsPermission) { if (e.action == MotionEvent.ACTION_UP) onAskPermission?.invoke(); return true }
        val G = deck ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                wakeLegends(G, x, y)
                if (open && settingsLink.near(x, y, dp(6f))) { onOpenSettings?.invoke(); return true }
                if (!open || swing >= 0.999f) {
                    for (K in G.keys) if (K.rect.near(x, y, dp(4f))) {
                        pressed = K.key; pressedAt = SystemClock.uptimeMillis(); pressKey(K.key); invalidate(); return true
                    }
                }
                for (T in G.legends) if (x > T.x - dp(6f) && x < T.x + T.w && y > T.y - T.h && y < T.y + T.h * 0.4f) {
                    if (!Deck.hold) Sfx.play(Sfx.S.STOP, 0.35f)
                    Deck.toggle(T.id); proximity[T.id] = 1f; invalidate(); return true
                }
                G.hold?.let { if (it.near(x, y, dp(10f))) { Sfx.play(Sfx.S.WIND_KEY, 0.5f); Deck.toggle("hold"); invalidate(); return true } }
                G.battery?.let { if (it.near(x, y, dp(4f))) { Sfx.play(Sfx.S.DOOR_OPEN, 0.5f); onOpenSettings?.invoke(); return true } }
                G.wheel?.let { if (it.near(x, y, dp(16f))) { wheelFrom = y to volume(); parent?.requestDisallowInterceptTouchEvent(true); return true } }
                val inWin = x > G.win.left && x < G.win.right && y > G.win.top && y < G.win.bottom
                if (inWin && Deck.door < 0.05f && Deck.pending == null && (open || view < 0.5f)) {
                    for (left in listOf(true, false)) {
                        val (hx, hy) = hub(G, left)
                        if (hypot(x - hx, y - hy) < Deck.PACK_R * G.k * 1.05f && !Deck.hold) {
                            wind = Triple(hx, hy, atan2(y - hy, x - hx)); windLeft = left
                            wasPlaying = Deck.mode == Deck.Mode.PLAY
                            Deck.stop(); Deck.dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                    swipeFrom = x to y; return true
                }
                if (railZone(x, y) && (open || view > 0.5f) && letters.size > 1) {
                    indexing = true; parent?.requestDisallowInterceptTouchEvent(true)
                    indexAt(y); invalidate(); return true
                }
                if (shelfArea.contains(x, y) && (open || view > 0.5f)) {
                    shelfFrom = Triple(y, shelfScroll, 0f)
                    vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    return true
                }
                if (!open && view > 0.5f) { back(); return true }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (indexing) { indexAt(y); invalidate(); return true }
                wheelFrom?.let { (y0, v0) -> setVolume((v0 + (y0 - y) / dp(220f)).coerceIn(0f, 1f)); invalidate(); return true }
                wakeLegends(G, x, y)
                wind?.let { (hx, hy, a0) ->
                    val a = atan2(y - hy, x - hx)
                    var da = a - a0
                    if (da > PI) da -= (2 * PI).toFloat(); if (da < -PI) da += (2 * PI).toFloat()
                    wind = Triple(hx, hy, a)
                    val (rl, rr) = Deck.packs()
                    // A reel turned under the finger pulls tape: angle × that reel's radius
                    Deck.windByHand((da * if (windLeft) rl else rr).toDouble())
                    invalidate()
                }
                shelfFrom?.let { (y0, s0, mv) ->
                    vt?.addMovement(e)
                    val dy = y - y0
                    shelfFrom = Triple(y0, s0, max(mv, abs(dy)))
                    shelfScroll = (s0 - dy).coerceIn(0f, maxScroll()); invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (indexing) { indexing = false; railTouchedAt = SystemClock.uptimeMillis(); invalidate(); return true }
                if (wheelFrom != null) { wheelFrom = null; invalidate(); return true }
                if (wind != null) { wind = null; Deck.dragging = false; if (wasPlaying) Deck.press(Deck.Key.PLAY) }
                swipeFrom?.let { (x0, y0) ->
                    val dx = x - x0; val dy = y - y0
                    // the tape stands upright in the window, so turning it over is a sideways swipe
                    if (abs(dx) > dp(40f) && abs(dx) > abs(dy) * 1.3f) Deck.turnOver()
                }
                swipeFrom = null
                shelfFrom?.let { (_, _, mv) ->
                    if (mv < dp(8f) && e.actionMasked == MotionEvent.ACTION_UP) {
                        slots.firstOrNull { it.rect.contains(x, y) }?.let { pick(it) }
                    } else {
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.yVelocity ?: 0f
                        scroller.fling(0, shelfScroll.toInt(), 0, (-v).toInt(), 0, 0, 0, maxScroll().toInt())
                    }
                }
                shelfFrom = null; vt?.recycle(); vt = null
                pressed?.let { pressedAt = SystemClock.uptimeMillis() - 60 }
                invalidate()
            }
        }
        return true
    }

    private fun pick(sl: Painter.Slot) {
        val i = sl.index
        // Membrane: the tapes either side lean into the gap it leaves, nearest most
        for (j in lean.indices) {
            val d = (j - i) / cols
            if (j != i && (j - i) % cols == 0 && d != 0) leanV[j] += -kotlin.math.sign(d.toFloat()) * dp(9f) / abs(d).toFloat().pow(1f)
        }
        Deck.load(sl.tape, direct = open)
        invalidate()
    }

    private fun wakeLegends(G: PlayerGeom, x: Float, y: Float) {
        // Giulietta: the printed switches brighten as the finger reaches towards them
        for (T in G.legends) {
            val d = hypot(x - (T.x + T.w / 2), y - T.y)
            val reach = dp(110f)
            if (d < reach) proximity[T.id] = max(proximity[T.id] ?: 0f, 1 - d / reach)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOut(v: Float): Float { val q = 1 - v.coerceIn(0f, 1f); return 1 - q * q * q }
    private fun easeInOut(t0: Float): Float { val t = t0.coerceIn(0f, 1f); return if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2 }
}
