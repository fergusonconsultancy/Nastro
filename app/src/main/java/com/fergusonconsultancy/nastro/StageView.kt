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
 * The whole screen. Folded, it is the Walkman's face: the door shut, the tape behind smoked
 * acrylic. Unfolded, the door has swung open on the crease: the right half is the compartment it
 * covered, with the tape at full colour, and the left half is the inside of the door, where the
 * shelf is kept. Opening the phone is opening the Walkman.
 */
class StageView(ctx: Context) : View(ctx) {
    private val painter = Painter(ctx)
    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    var needsPermission = false
    var onAskPermission: (() -> Unit)? = null

    private var open = false                 // unfolded: the compartment is open
    private var swing = 1f                   // the door swinging open after unfolding, 0..1
    private var swingFrom = 0L
    private var view = 0f                    // folded: 0 the face, 1 panned to the shelf
    private var viewTarget = 0f
    private var shake = 0f
    private var lastFrame = 0L

    private var deck: DeckGeom? = null
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
                Deck.Event.KEY -> performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                Deck.Event.FLIP -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                Deck.Event.REFUSED -> performHapticFeedback(HapticFeedbackConstants.REJECT)
                Deck.Event.CLUNK -> clunk()
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Deck.listen(changed) }
    override fun onDetachedFromWindow() { Deck.unlisten(changed); super.onDetachedFromWindow() }

    private fun clunk() {
        shake = 1f
        runCatching { vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
        invalidate()
    }

    fun libraryChanged() { layoutShelf(); invalidate() }

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
            // Unfolding: the door swings open over the left half
            swing = 0f; swingFrom = SystemClock.uptimeMillis()
            Deck.doorShutQuietly(); view = 0f; viewTarget = 0f
        } else if (!nowOpen && open) {
            // Folding: the door has shut; the face appears with the clunk of the catch
            swing = 1f; view = 0f; viewTarget = 0f
            post { clunk() }
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
            val dw = min((width - half) * 0.84f, H * 0.92f * 0.62f)
            deck = DeckGeom(half + ((width - half) - dw) / 2, T + H * 0.04f, dw, H * 0.92f, dens)
            shelfArea.set(L + dp(12f), T + dp(12f), half - dp(14f), T + H - dp(12f))
        } else {
            val v = easeOut(view)
            deck = DeckGeom(L + W * 0.045f + v * W * 1.05f, T + H * 0.03f, W * 0.91f, H * 0.94f, dens)
            shelfArea.set(L + dp(10f) - (1 - v) * W * 1.05f, T + dp(10f), L + W - dp(10f) - (1 - v) * W * 1.05f, T + H - dp(10f))
        }
        layoutShelf()
    }

    private fun layoutShelf() {
        val tapes = Library.tapes
        val cols = if (shelfArea.width() > dp(340f)) 2 else 1
        val gap = max(dp(10f), shelfArea.width() * 0.035f)
        val cw = (shelfArea.width() - gap * (cols + 1)) / cols
        val ch = cw * DeckGeom.CH / DeckGeom.CW
        val head = dp(70f)
        // Updated in place each frame; rebuilt only when the library changes
        if (slots.size != tapes.size || slots.firstOrNull()?.tape !== tapes.firstOrNull()) slots = tapes.mapIndexed { i, t -> Painter.Slot(t, i, RectF()) }
        for (sl in slots) {
            val i = sl.index
            val x0 = shelfArea.left + gap + (i % cols) * (cw + gap)
            val y0 = shelfArea.top + head + (i / cols) * (ch + gap) - shelfScroll
            sl.rect.set(x0, y0, x0 + cw, y0 + ch)
        }
        shelfContent = head + ((tapes.size + cols - 1) / cols) * (ch + gap) + gap
        if (lean.size != tapes.size) { lean = FloatArray(tapes.size); leanV = FloatArray(tapes.size) }
    }

    private fun maxScroll() = max(0f, shelfContent - shelfArea.height())

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
        if (open) {
            if (swing >= 0.999f) {
                painter.shelf(c, shelfArea, slots, lean, true, true, Library.tapes.size)
                painter.deck(c, G, true, now, pressed, now - pressedAt, proximity, lifting)
            } else drawSwing(c, G, now, lifting)
        } else {
            painter.shelf(c, shelfArea, slots, lean, true, false, Library.tapes.size)
            painter.deck(c, G, false, now, pressed, now - pressedAt, proximity, lifting)
        }
        // the tape in flight from the shelf
        val pend = Deck.pending
        if (pend != null && lifting) {
            val sl = slots.firstOrNull { it.tape === pend }
            if (sl != null) {
                val tt = easeOut(((now - Deck.pendingSince) / Deck.FLIGHT_MS.toFloat()).coerceIn(0f, 1f))
                val fx = sl.rect.centerX(); val fy = sl.rect.centerY()
                val k0 = sl.rect.width() / (DeckGeom.CW + 4); val k1 = G.k
                c.save()
                c.translate(lerp(fx, G.ccx - if (open) 4 * G.k else 0f, tt), lerp(fy, G.ccy, tt) - sin(tt * PI).toFloat() * dp(30f))
                c.rotate(-90f * tt)
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
            !scroller.isFinished || lean.any { abs(it) > 0.05f } || proximity.values.any { it > 0.02f } || (pressed != null && now - pressedAt < 200)
        if (busy) postInvalidateOnAnimation()
    }

    /** Unfolding is opening the door: the face swings on the crease out over the left half, and its inside becomes the shelf. */
    private fun drawSwing(c: Canvas, G: DeckGeom, now: Long, lifting: Boolean) {
        val cx = width / 2f
        val th = easeInOut(swing) * PI
        val cs = cos(th).toFloat()
        val shade = Paint()
        painter.deck(c, G, true, now, pressed, now - pressedAt, proximity, lifting)
        shade.color = Color.BLACK.a(0.55f * (1 - swing)); c.drawRect(cx, 0f, width.toFloat(), height.toFloat(), shade)
        c.save()
        c.translate(cx, 0f); c.scale(max(0.001f, abs(cs)), 1f); c.translate(-cx, 0f)
        if (cs > 0) {
            // the face, still shut over the compartment, turning away
            painter.deck(c, G, false, now, null, 0, proximity, lifting)
            shade.color = Color.BLACK.a(0.5f * (1 - cs)); c.drawRect(cx, 0f, width.toFloat(), height.toFloat(), shade)
        } else {
            // the inside of the door coming down onto the left half
            painter.shelf(c, shelfArea, slots, lean, true, true, Library.tapes.size)
            shade.color = Color.BLACK.a(0.5f * (1 + cs)); c.drawRect(0f, 0f, cx, height.toFloat(), shade)
        }
        c.restore()
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

    private fun hub(G: DeckGeom, left: Boolean): Pair<Float, Float> {
        // shell (sx, sy) after rotate(-90°): screen = (sy, -sx) × k
        val sx = if (left) -21.25f else 21.25f
        val shift = if (open) 4 * G.k else 0f
        return Pair(G.ccx - shift + (-2f) * G.k, G.ccy - sx * G.k)
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
                for (K in G.keys) if (hypot(x - K.x, y - K.y) < K.r * 1.3f) {
                    pressed = K.key; pressedAt = SystemClock.uptimeMillis(); Deck.press(K.key); invalidate(); return true
                }
                for (T in G.legends) if (x > T.x && x < T.x + T.w && y > T.y - T.h && y < T.y + T.h * 0.6f) {
                    Deck.toggle(T.id); proximity[T.id] = 1f; invalidate(); return true
                }
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
                if (shelfArea.contains(x, y) && (open || view > 0.5f)) {
                    shelfFrom = Triple(y, shelfScroll, 0f)
                    vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    return true
                }
                if (!open && view > 0.5f) { back(); return true }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
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
        val cols = if (shelfArea.width() > dp(340f)) 2 else 1
        for (j in lean.indices) {
            val d = (j - i) / cols
            if (j != i && (j - i) % cols == 0 && d != 0) leanV[j] += -kotlin.math.sign(d.toFloat()) * dp(9f) / abs(d).toFloat().pow(1f)
        }
        Deck.load(sl.tape, direct = open)
        invalidate()
    }

    private fun wakeLegends(G: DeckGeom, x: Float, y: Float) {
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
