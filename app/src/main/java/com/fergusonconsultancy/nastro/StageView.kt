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
    /** The blank tape at the head of the shelf: make a mixtape. */
    var onNewMixtape: (() -> Unit)? = null
    /** A mixtape held on the shelf: write on it again. */
    var onEditMixtape: ((Tape) -> Unit)? = null
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private var volAt = 0L
    private val live = Painter.Live()

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
    /** The full-bleed panel behind the shelf: the inside of the door, edge to edge. */
    private val shelfBg = RectF()
    private var slots: List<Painter.Slot> = emptyList()
    private var lean = FloatArray(0)
    private var leanV = FloatArray(0)
    private var shelfScroll = 0f
    private var shelfContent = 0f
    private val scroller = OverScroller(ctx)

    private var cellPopAt = 0L
    /** The AA's spring: up quickly, held while the settings open, settling back down. */
    private fun cellPop(now: Long): Float {
        val a = (now - cellPopAt).toFloat()
        if (cellPopAt == 0L || a > 1100f) return 0f
        return when {
            a < 160f -> easeOut(a / 160f) * 1.08f
            a < 240f -> 1.08f - (a - 160f) / 80f * 0.08f
            a < 700f -> 1f
            else -> 1f - easeOut((a - 700f) / 400f)
        }
    }

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

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Deck.listen(changed); Prefs.listen(changed); Light.start(context, changed) }
    override fun onDetachedFromWindow() { Light.stop(); Deck.unlisten(changed); Prefs.unlisten(changed); super.onDetachedFromWindow() }

    /** The screen's own corner radius, so the body's edge follows the glass. */
    private fun screenRadius(): Float {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.let { return it.radius.toFloat() }
        }
        return dp(24f)
    }

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
        // 29 Oct 2026: open means the app has (most of) the main screen. Read from the display,
        // not guessed from this window's proportions, which a split screen or the Fold8 Ultra's
        // near-square inner screen could put on the wrong side of a ratio
        val nowOpen = onMainScreen(w, h)
        display?.let { Light.rotation = it.rotation }
        // The crease runs down the inner screen's upright centre; turned on its side it runs
        // across. Then the open Walkman is laid out across it, everything still upright (11 Nov 2026:
        // turning the whole frame sideways left the type and the tape on their sides)
        stacked = nowOpen && w > h
        if (nowOpen && !open && oldw != 0) {
            // Unfolding: the door pops on its spring and swings open over the left half
            swing = if (Prefs.doorSwing) 0f else 1f; swingFrom = SystemClock.uptimeMillis()
            Deck.doorShutQuietly(); view = 0f; viewTarget = 0f
            Sfx.play(Sfx.S.DOOR_OPEN, 0.8f)
            // the door is open: the tape stops where it is (13 Nov 2026)
            Deck.setOpen(true)
        } else if (!nowOpen && open) {
            // Folding: the door has shut; the face appears with the clunk of the catch, and it plays
            swing = 1f; view = 0f; viewTarget = 0f
            post { Sfx.play(Sfx.S.DOOR_SHUT, 0.8f); clunk(); Deck.setOpen(false) }
        } else Deck.setOpen(nowOpen)
        open = nowOpen
        layoutAll()
    }

    /** True when this window is on the inner (main) screen and has most of it, not a split share. */
    private fun onMainScreen(w: Int, h: Int): Boolean {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") display?.getRealMetrics(m)
        if (m.widthPixels <= 0) return w > h * 0.8f
        val inner = minOf(m.widthPixels, m.heightPixels) / m.density >= 600f
        val most = w.toFloat() * h >= 0.7f * m.widthPixels * m.heightPixels
        return inner && most
    }

    // ---- turned on its side ---------------------------------------------------------------------
    // Open and on its side, the crease runs across the screen: the deck takes the top half and the
    // inside of the door, with the shelf, the bottom half.
    private var stacked = false
    private val fw get() = width
    private val fh get() = height
    private val pl get() = paddingLeft
    private val pt get() = paddingTop
    private val pr get() = paddingRight
    private val pb get() = paddingBottom

    /** The screen's own pixels per millimetre: the tape is drawn no bigger than a real one. */
    private val pxPerMm get() = resources.displayMetrics.xdpi / 25.4f

    /**
     * The scale to draw the tape at on this layout, in px per mm (14 Nov 2026). Mechack: the tape
     * behind the cover's lens looked bigger than the one in the open compartment; one tape, one size.
     * Each screen's upright layout records how big the tape can be there, as a fraction of its real
     * size; every layout draws it no bigger than both screens allow, so it is the same physical size
     * folded and open.
     */
    private fun matched(here: String?, other: String, geom: (Float) -> PlayerGeom): Float {
        // 14 Nov 2026: in pixels. The Fold 8 Ultra's two screens share one density (422 ppi), so equal
        // pixels are equal millimetres; the main screen's reported dpi can't be trusted to agree
        val free = geom(pxPerMm * 20f).k
        if (here != null) Prefs.setFit(here, free)
        val o = Prefs.fit(other)
        return if (o > 0f) min(free, o) else free
    }

    private fun layoutAll() {
        val L = pl.toFloat(); val T = pt.toFloat()
        val W = (fw - pl - pr).toFloat(); val H = (fh - pt - pb).toFloat()
        if (W <= 0 || H <= 0) return
        // Full-bleed: the open Walkman spans the whole main screen (the door's inside on the left, the
        // compartment on the right, meeting at the crease), and folded, the face fills the cover screen.
        // The parts keep clear of the camera and system bars; the metal runs to every edge.
        val Wf = fw.toFloat(); val Hf = fh.toFloat()
        if (open && stacked) {
            val half = Hf / 2f
            val g = dp(10f)
            val bleed = RectF(0f, 0f, Wf, half)
            val m = matched(null, "cover") { PlayerGeom.bay(L + g, T + g, W - g * 2, half - T - g * 2, bleed, it) }
            deck = PlayerGeom.bay(L + g, T + g, W - g * 2, half - T - g * 2, bleed, m)
            face = PlayerGeom.face(L + g, T + g, W - g * 2, half - T - g * 2, bleed, m)
            shelfBg.set(0f, half, Wf, Hf)
            shelfArea.set(L + dp(12f), half + dp(6f), L + W - dp(12f), T + H - dp(6f))
        } else if (open) {
            val half = Wf / 2f
            val g = dp(10f)
            val bleed = RectF(half, 0f, Wf, Hf)
            val bw = Wf - pr - half - g * 2
            val m = matched("main", "cover") { PlayerGeom.bay(half + g, T + g, bw, H - g * 2, bleed, it) }
            deck = PlayerGeom.bay(half + g, T + g, bw, H - g * 2, bleed, m)
            face = PlayerGeom.face(half + g, T + g, bw, H - g * 2, bleed, m)
            deck?.let { Prefs.setAnchor(it.tx - half, it.ty) }
            shelfBg.set(0f, 0f, half, Hf)
            shelfArea.set(L + dp(12f), T + dp(6f), half - dp(12f), T + H - dp(6f))
        } else {
            val v = easeOut(view)
            val off = v * Wf * 1.04f
            val g = dp(8f)
            val bleed = RectF(off, 0f, Wf + off, Hf)
            val m = matched(if (H > W) "cover" else null, "main") { PlayerGeom.face(L + g + off, T + g, W - g * 2, H - g * 2, bleed, it) }
            deck = PlayerGeom.face(L + g + off, T + g, W - g * 2, H - g * 2, bleed, m, if (H > W) Prefs.anchor() else null)
            val so = (1 - v) * Wf * 1.04f
            shelfBg.set(-so, 0f, Wf - so, Hf)
            shelfArea.set(L + dp(10f) - so, T + dp(6f), L + W - dp(10f) - so, T + H - dp(6f))
        }
        layoutShelf()
    }

    /** The shelf: the blank tape for a new mixtape, then the mixtapes, then every album. */
    private var shelfSrc: List<Tape>? = null
    private var shelfList: List<Tape> = emptyList()
    private fun shelfTapes(): List<Tape> {
        if (shelfSrc !== Library.tapes) { shelfSrc = Library.tapes; shelfList = listOf(Library.BLANK) + Library.tapes }
        return shelfList
    }

    private fun layoutShelf() {
        val tapes = shelfTapes()
        // as many columns as fit at a comfortable size: two on a half screen, more across the open phone on its side
        cols = (shelfArea.width() / dp(175f)).toInt().coerceIn(1, 6)
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
        // Mixtapes and the blank sit at the head of the shelf, outside the alphabet
        if (Prefs.order != "newest") shelfTapes().forEachIndexed { i, t ->
            if (t.mix) return@forEachIndexed
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
    override fun onDraw(c: Canvas) = scene(c)

    private fun scene(c: Canvas) {
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
        live.cellPop = cellPop(now)
        if (open) {
            if (swing >= 0.999f) {
                drawShelf(c)
                painter.bay(c, G, live)
            } else drawSwing(c, G, now, lifting)
        } else {
            drawShelf(c)
            painter.face(c, G, live)
        }
        // the tape in flight from the shelf, or from the hand that dropped it
        val pend = Deck.pending
        if (pend == null || pend !== dropTape) { dropFrom = null; dropTape = null }
        val df = dropFrom
        if (pend != null && lifting && df != null) {
            val tt = easeOut(((now - Deck.pendingSince) / Deck.FLIGHT_MS.toFloat()).coerceIn(0f, 1f))
            c.save()
            c.translate(lerp(df[0], G.tx, tt), lerp(df[1], G.ty, tt))
            c.rotate(G.rot)
            val kk = G.k * (1.03f - 0.03f * tt)
            c.scale(kk, kk)
            painter.cassette(c, pend, false, 1f, shadow = 9f - 4f * tt)
            c.restore()
        } else if (pend != null && lifting) {
            val sl = slots.firstOrNull { it.tape === pend }
            if (sl != null) {
                val tt = easeOut(((now - Deck.pendingSince) / Deck.FLIGHT_MS.toFloat()).coerceIn(0f, 1f))
                val fx = sl.rect.centerX(); val fy = sl.rect.centerY()
                val k0 = sl.rect.width() / (PlayerGeom.CW + 4); val k1 = G.k
                c.save()
                c.translate(lerp(fx, G.tx, tt), lerp(fy, G.ty, tt) - sin(tt * PI).toFloat() * dp(30f))
                c.rotate(G.rot * tt)
                val kk = lerp(k0, k1, tt) * (1 + sin(tt * PI).toFloat() * 0.06f)
                c.scale(kk, kk)
                painter.cassette(c, pend, false, 1f, shadow = 6f)
                c.restore()
            }
        }
        c.restore()
        // the crease is the Walkman's hinge: its knuckles join the door to the body (13 Nov 2026)
        if (open && stacked) painter.hinge(c, fh / 2f, 0f, fw.toFloat(), true)
        else if (open) painter.hinge(c, fw / 2f, 0f, fh.toFloat(), false)
        // a tape in the hand rides over everything, the hinge included
        drawCarry(c, G, now)
        // the metal turns down under the bezel, all round the glass
        painter.edge(c, fw.toFloat(), fh.toFloat(), screenRadius())

        val busy = Deck.animating() || Deck.mode == Deck.Mode.PLAY || swing < 1f || shake > 0 || abs(view - viewTarget) > 0.001f ||
            !scroller.isFinished || railWake > 0.01f || lean.any { abs(it) > 0.05f } || proximity.values.any { it > 0.02f } || (pressed != null && now - pressedAt < 200) || live.cellPop > 0f || carry != null || homeSlot != null
        if (busy) postInvalidateOnAnimation()
    }

    /** Unfolding is opening the door: the face swings on the crease out over the left half, and its inside becomes the shelf. */
    private fun drawSwing(c: Canvas, G: PlayerGeom, now: Long, lifting: Boolean) {
        val th = easeInOut(swing) * PI
        val cs = cos(th).toFloat()
        val shade = Paint()
        painter.bay(c, G, live)
        val W = fw.toFloat(); val H = fh.toFloat()
        if (stacked) {
            // the crease runs across: the face over the top half swings down onto the bottom half
            val cy = H / 2f
            shade.color = Color.BLACK.a(0.55f * (1 - swing)); c.drawRect(0f, 0f, W, cy, shade)
            c.save()
            c.translate(0f, cy); c.scale(1f, max(0.001f, abs(cs))); c.translate(0f, -cy)
            if (cs > 0) {
                face?.let { painter.face(c, it, live) }
                shade.color = Color.BLACK.a(0.5f * (1 - cs)); c.drawRect(0f, 0f, W, cy, shade)
            } else {
                drawShelf(c)
                shade.color = Color.BLACK.a(0.5f * (1 + cs)); c.drawRect(0f, cy, W, H, shade)
            }
            c.restore()
            return
        }
        val cx = W / 2f
        shade.color = Color.BLACK.a(0.55f * (1 - swing)); c.drawRect(cx, 0f, W, H, shade)
        c.save()
        c.translate(cx, 0f); c.scale(max(0.001f, abs(cs)), 1f); c.translate(-cx, 0f)
        if (cs > 0) {
            // the face, still shut over the compartment, turning away
            face?.let { painter.face(c, it, live) }
            shade.color = Color.BLACK.a(0.5f * (1 - cs)); c.drawRect(cx, 0f, W, H, shade)
        } else {
            // the inside of the door coming down onto the left half
            drawShelf(c)
            shade.color = Color.BLACK.a(0.5f * (1 + cs)); c.drawRect(0f, 0f, cx, H, shade)
        }
        c.restore()
    }

    private fun drawShelf(c: Canvas) {
        painter.shelf(c, shelfArea, slots, lean, true, open, Library.tapes.size, shelfScroll, shelfBg, open && stacked)
        // a tape is waiting in the open Walkman: shutting the door will play it
        if (open && Deck.resumeOnShut) {
            val cy = shelfArea.top + dp(56f) - shelfScroll
            if (cy > shelfArea.top) painter.handwrite(c, "fold shut to play", shelfArea.right - dp(42f), cy, dp(20f), shelfArea.width() * 0.5f, 0xFFFFAD33.toInt(), android.graphics.Paint.Align.RIGHT)
        }
        // 14 Nov 2026: no SETTINGS on the shelf. Open, the settings are behind the battery, as they are
        // behind the battery door folded: tap the AA and it springs up
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
        val cx = fw / 2f; val cy = fh / 2f
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
        // the hub's centre in the shell's millimetres, turned onto the screen as the tape is drawn
        val q = G.at(if (left) -21.25f else 21.25f, -2f)
        return Pair(q.x, q.y)
    }

    private fun RectF.near(x: Float, y: Float, pad: Float) = x > left - pad && x < right + pad && y > top - pad && y < bottom + pad

    private fun pressKey(k: Deck.Key) {
        if (!Deck.hold) Sfx.play(when (k) { Deck.Key.PLAY -> Sfx.S.PLAY; Deck.Key.STOP -> Sfx.S.STOP; else -> Sfx.S.WIND_KEY })
        val wasDoor = Deck.doorOpening
        Deck.press(k)
        if (!wasDoor && Deck.doorOpening) postDelayed({ Sfx.play(Sfx.S.DOOR_OPEN) }, 90)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = touch(e)

    // ---- carrying a tape (14 Nov 2026) --------------------------------------------------------------
    // Mechack: dragging a tape from the shelf and dropping it in the player is more analogue. Open,
    // hold a tape (or slide it sideways off the shelf towards the deck, upright) and it comes away in
    // your fingers; it grows to its real size as it nears the compartment and turns to lie as the
    // compartment holds it; let go over the hole and it drops onto the spindles. Let go elsewhere and
    // it goes back to its place. A tap still loads it; holding a mixtape and letting go unmoved still
    // opens it to write on.
    private var carry: Painter.Slot? = null
    private var carryX = 0f; private var carryY = 0f
    private var carryMoved = 0f
    private var carryHome = 0L                 // the moment a tape let go away from the deck began going back
    private val homeFrom = FloatArray(2)
    private var homeSlot: Painter.Slot? = null
    /** Where a dropped tape left the hand: its flight to the spindles starts there. x, y, scale, turn. */
    private var dropFrom: FloatArray? = null
    private var dropTape: Tape? = null
    private var downX = 0f; private var downY = 0f
    private var downSlot: Painter.Slot? = null

    private val pickUp = Runnable {
        val sl = downSlot ?: return@Runnable
        if (open && (shelfFrom?.third ?: 99f) < dp(8f)) startCarry(sl, downX, downY)
    }

    private fun startCarry(sl: Painter.Slot, x: Float, y: Float) {
        shelfFrom?.let { (_, s0, _) -> shelfScroll = s0 }
        shelfFrom = null; vt?.recycle(); vt = null
        removeCallbacks(longPress); holdSlot = null
        carry = sl; carryX = x; carryY = y; carryMoved = 0f
        painter.carried = sl.tape
        haptic(HapticFeedbackConstants.LONG_PRESS); Sfx.play(Sfx.S.WIND_KEY, 0.3f)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    /** How near the carried tape is to the compartment: 0 at the shelf, 1 over the hole. */
    private fun nearness(G: PlayerGeom, sl: Painter.Slot): Float {
        val d0 = hypot(G.win.centerX() - sl.rect.centerX(), G.win.centerY() - sl.rect.centerY())
        val d = hypot(G.win.centerX() - carryX, G.win.centerY() - carryY)
        return (1 - d / max(1f, d0)).coerceIn(0f, 1f)
    }

    private fun overDeck(G: PlayerGeom, x: Float, y: Float) = G.win.near(x, y, dp(28f))

    private fun drawCarry(c: Canvas, G: PlayerGeom, now: Long) {
        val sl = carry
        if (sl != null) {
            val t = nearness(G, sl)
            val e = t * t * (3 - 2 * t)
            val k0 = sl.rect.width() / (PlayerGeom.CW + 4) * 1.08f
            val over = overDeck(G, carryX, carryY)
            c.save()
            c.translate(carryX, carryY - dp(18f) * (1 - e))
            c.rotate(G.rot * e)
            val kk = lerp(k0, G.k * if (over) 1.03f else 1f, e)
            c.scale(kk, kk)
            painter.cassette(c, sl.tape, false, 1f, shadow = 9f)
            c.restore()
            return
        }
        // let go away from the deck: back to its place
        val hs = homeSlot ?: return
        val a = ((now - carryHome) / 260f).coerceIn(0f, 1f)
        if (a >= 1f) { homeSlot = null; painter.carried = null; return }
        val q = easeOut(a)
        val k0 = hs.rect.width() / (PlayerGeom.CW + 4)
        c.save(); c.translate(lerp(homeFrom[0], hs.rect.centerX(), q), lerp(homeFrom[1], hs.rect.centerY(), q))
        c.scale(k0 * (1.08f - 0.08f * q), k0 * (1.08f - 0.08f * q))
        painter.cassette(c, hs.tape, false, 1f, shadow = 6f); c.restore()
        postInvalidateOnAnimation()
    }

    private fun endCarry(G: PlayerGeom, x: Float, y: Float, up: Boolean) {
        val sl = carry ?: return
        carry = null
        if (up && overDeck(G, x, y) && sl.tape !== Library.BLANK) {
            // dropped in: the flight from the hand to the spindles is a short fall
            dropFrom = floatArrayOf(x, y, G.k, G.rot); dropTape = sl.tape
            painter.carried = null
            pick(sl)
        } else if (up && carryMoved < dp(8f) && sl.tape.mix) {
            painter.carried = null
            haptic(HapticFeedbackConstants.LONG_PRESS); onEditMixtape?.invoke(sl.tape)
        } else {
            homeSlot = sl; carryHome = SystemClock.uptimeMillis(); homeFrom[0] = x; homeFrom[1] = y
        }
        invalidate()
    }

    // Holding a mixtape on the shelf opens it to write on again
    private var holdSlot: Painter.Slot? = null
    private val longPress = Runnable {
        val sl = holdSlot ?: return@Runnable
        holdSlot = null
        if ((shelfFrom?.third ?: 99f) < dp(8f)) {
            shelfFrom = null; vt?.recycle(); vt = null
            haptic(HapticFeedbackConstants.LONG_PRESS)
            onEditMixtape?.invoke(sl.tape)
        }
    }

    private fun touch(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        if (needsPermission) { if (e.action == MotionEvent.ACTION_UP) onAskPermission?.invoke(); return true }
        val G = deck ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                wakeLegends(G, x, y)
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
                G.battery?.let { if (it.near(x, y, dp(4f))) {
                    if (open) {
                        // the AA springs up out of its bay on its coil; the settings are under it
                        cellPopAt = SystemClock.uptimeMillis(); Sfx.play(Sfx.S.WIND_KEY, 0.45f); haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                        postDelayed({ onOpenSettings?.invoke() }, 240)
                        invalidate()
                    } else { Sfx.play(Sfx.S.DOOR_OPEN, 0.5f); onOpenSettings?.invoke() }
                    return true
                } }
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
                    removeCallbacks(longPress); removeCallbacks(pickUp)
                    downX = x; downY = y
                    downSlot = slots.firstOrNull { it.rect.contains(x, y) && it.tape !== Library.BLANK }
                    if (open) {
                        // open: holding a tape lifts it off the shelf
                        if (downSlot != null) postDelayed(pickUp, 300)
                    } else {
                        holdSlot = slots.firstOrNull { it.rect.contains(x, y) && it.tape.mix && it.tape !== Library.BLANK }
                        if (holdSlot != null) postDelayed(longPress, 480)
                    }
                    return true
                }
                if (!open && view > 0.5f) { back(); return true }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (indexing) { indexAt(y); invalidate(); return true }
                if (carry != null) {
                    carryMoved = max(carryMoved, hypot(x - downX, y - downY))
                    carryX = x; carryY = y; invalidate(); return true
                }
                // upright and open, sliding a tape sideways towards the deck lifts it straight off the shelf
                val ds = downSlot
                if (open && !stacked && ds != null && shelfFrom != null && x - downX > dp(16f) && abs(x - downX) > abs(y - downY) * 1.6f) {
                    removeCallbacks(pickUp); startCarry(ds, x, y); carryMoved = x - downX; return true
                }
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
                removeCallbacks(pickUp)
                if (carry != null) { endCarry(G, x, y, e.actionMasked == MotionEvent.ACTION_UP); downSlot = null; return true }
                downSlot = null
                if (indexing) { indexing = false; railTouchedAt = SystemClock.uptimeMillis(); invalidate(); return true }
                if (wheelFrom != null) { wheelFrom = null; invalidate(); return true }
                if (wind != null) { wind = null; Deck.dragging = false; if (wasPlaying) Deck.press(Deck.Key.PLAY) }
                swipeFrom?.let { (x0, y0) ->
                    val dx = x - x0; val dy = y - y0
                    // upright in the window, turning the tape over is a sideways swipe; lying flat, an upward or downward one
                    val (along, across) = if (G.wide) abs(dy) to abs(dx) else abs(dx) to abs(dy)
                    if (along > dp(40f) && along > across * 1.3f) Deck.turnOver()
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
                removeCallbacks(longPress); holdSlot = null
                pressed?.let { pressedAt = SystemClock.uptimeMillis() - 60 }
                invalidate()
            }
        }
        return true
    }

    private fun pick(sl: Painter.Slot) {
        if (sl.tape === Library.BLANK) { Sfx.play(Sfx.S.WIND_KEY, 0.5f); onNewMixtape?.invoke(); return }
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
