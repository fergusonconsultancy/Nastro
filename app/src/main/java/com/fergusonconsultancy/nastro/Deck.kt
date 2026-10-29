package com.fergusonconsultancy.nastro

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The machine. Tape position, transport, reels and audio live here, so the screen, the
 * notification and headset buttons all drive the same deck.
 *
 * [x] runs along side A: 0 is the start of side A. Side B plays from x = 1 back towards 0.
 * [facing] is the label turned to the window; it decides which reel holds which part of the tape.
 */
object Deck {
    // Compact cassette, IEC 60094
    const val TAPE_MM_S = 47.6          // 4.76 cm/s
    const val WIND_X = 30.0             // fast wind, times play speed
    const val HUB_R = 11f               // mm
    const val PACK_R = 24.5f            // mm, full pack (C60/C90/C120 alike: thinner tape for longer)
    const val FLIGHT_MS = 540L          // shelf to compartment

    enum class Mode { STOP, PLAY, FF, REW, AMS }
    enum class Key { PLAY, STOP, FF, REW }
    enum class Event { KEY, CLUNK, FLIP, REFUSED }

    var tape: Tape? = null; private set
    var facing = 'A'; private set
    var x = 0.0; private set
    var dir = 1; private set
    var mode = Mode.STOP; private set
    var v = 0.0; private set                 // speed in multiples of play speed, signed along x
    private var amsTarget: Double? = null
    private var blank = false
    var angL = 0f; private set
    var angR = 0f; private set
    // The transport (29 Oct 2026). The head and pinch roller come forward into the cassette on
    // play and fall back on stop and while winding, as a real deck's do; everything that touches
    // the tape turns at the tape's own speed, the same speed that turns the reels.
    /** How far the head is in: 0 back, 1 against the tape (the pressure pad gives as it arrives). */
    var engage = 0f; private set
    /** The tape's speed past the head, mm/s, positive left to right on screen. */
    var tapeMmS = 0f; private set
    /** The cassette's two tape guides (rollers in the foot corners), radians. */
    var guideAng = 0f; private set
    /** The pinch roller, turning with the tape while it presses it to the capstan. */
    var pinchAng = 0f; private set
    /** The capstan: driven by the motor while the head is in. */
    var capstanAng = 0f; private set
    const val GUIDE_R = 1.4f
    const val PINCH_R = 2.9f
    const val CAPSTAN_R = 0.85f
    var door = 0f; private set
    private var doorTarget = 0f
    var flip = 0f; private set
    var flipping = false; private set
    var conc = 0f; private set               // Concentrato 0..1
    var dragging = false

    // The printed switches
    var autoRev = true; private set
    var megaBass = false; private set
    var hold = false; private set

    // Loading: the tape travelling from the shelf
    var pending: Tape? = null; private set
    var pendingSince = 0L; private set
    private var swappedAt = 0L

    var onEvent: ((Event) -> Unit)? = null
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    fun notifyChanged() = listeners.toList().forEach { it() }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var last = 0L
    private var lastSave = 0L

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Prefs.init(app)
        Sfx.init(app)
        val p = prefs()
        autoRev = p.getBoolean("autoRev", true)
        megaBass = p.getBoolean("megaBass", false)
    }

    private fun prefs() = app.getSharedPreferences("deck", Context.MODE_PRIVATE)

    /** After the library loads: put back the tape that was in the deck, where it was left. */
    fun restore() {
        if (tape != null) return
        val id = prefs().getLong("tape", -1L)
        val t = Library.find(id) ?: Library.tapes.firstOrNull() ?: return
        insert(t)
        notifyChanged()
    }

    // ---- Tape geometry ----------------------------------------------------------------------
    val sideLetter get() = if (dir > 0) 'A' else 'B'
    private fun sideList(): List<Track> = tape?.let { if (dir > 0) it.a else it.b } ?: emptyList()
    private fun sidePos(): Double = (if (dir > 0) x else 1 - x) * (tape?.sideSecs ?: 1.0)
    private fun xFromSidePos(s: Double): Double { val L = tape?.sideSecs ?: 1.0; return if (dir > 0) s / L else 1 - s / L }
    private fun sideEnd(): Double = tape?.let { if (dir > 0) it.aEnd else it.bEnd } ?: 0.0

    class Now(val track: Track?, val index: Int, val into: Double)
    fun current(): Now {
        val p = sidePos(); val L = sideList()
        for (i in L.indices.reversed()) if (p >= L[i].start - 0.01) return Now(L[i], i, p - L[i].start)
        return Now(L.firstOrNull(), 0, 0.0)
    }

    /** Radii of the left and right packs, mm. Equal areas: tape leaving one reel winds onto the other. */
    fun packs(): Pair<Float, Float> {
        val onLeft = if (facing == 'A') 1 - x else x
        fun r(f: Double) = sqrt(HUB_R * HUB_R + f * (PACK_R * PACK_R - HUB_R * HUB_R)).toFloat()
        return r(onLeft) to r(1 - onLeft)
    }

    /** The tape counter counts turns of the take-up reel, geared 2:1. */
    fun counter(): Int {
        val taken = if (dir > 0) x else 1 - x
        val r = sqrt(HUB_R * HUB_R + taken * (PACK_R * PACK_R - HUB_R * HUB_R))
        return (((r - HUB_R) / 0.0125) / 2).toInt() % 1000
    }

    // ---- Keys -----------------------------------------------------------------------------------
    fun press(key: Key) {
        if (hold) { onEvent?.invoke(Event.REFUSED); return }
        onEvent?.invoke(Event.KEY)
        val t = tape ?: return
        when (key) {
            Key.PLAY -> {
                if (door > 0.05f || doorTarget > 0f) { doorTarget = 0f }
                startService()
                mode = Mode.PLAY; amsTarget = null; blank = false
            }
            Key.STOP -> if (mode == Mode.STOP) openDoor() else stop()
            Key.FF, Key.REW -> {
                if (door > 0.05f) return
                val fwd = key == Key.FF
                if (mode == Mode.PLAY || mode == Mode.AMS) {
                    // Music sensor: wind to the next gap, or back to the start of this track, then play
                    val L = sideList(); val cur = current()
                    val target: Double? = if (fwd) L.getOrNull(cur.index + 1)?.start
                    else if (cur.into > 3 || cur.index == 0) cur.track?.start else L[cur.index - 1].start
                    if (target == null) { mode = Mode.FF; blank = true }
                    else { amsTarget = xFromSidePos(target); mode = Mode.AMS }
                } else {
                    mode = if (fwd) Mode.FF else Mode.REW; amsTarget = null
                }
            }
        }
        if (t.sideSecs > 0) wake()
        notifyChanged()
    }

    /** Stop without opening the door (notification, headset, audio focus loss). */
    fun stop() {
        mode = Mode.STOP; amsTarget = null; blank = false
        Sfx.update(0.0, false)
        save(); wake(); notifyChanged()
    }

    fun openDoor() { mode = Mode.STOP; doorTarget = 1f; save(); wake(); notifyChanged() }
    fun closeDoor() { if (pending == null) { doorTarget = 0f; wake() } }
    val doorOpening get() = doorTarget > 0.5f

    fun toggle(which: String) {
        if (which != "hold" && hold) { onEvent?.invoke(Event.REFUSED); return }
        when (which) {
            "autoRev" -> autoRev = !autoRev
            "megaBass" -> { megaBass = !megaBass; Audio.bass(megaBass) }
            "hold" -> hold = !hold
        }
        prefs().edit().putBoolean("autoRev", autoRev).putBoolean("megaBass", megaBass).apply()
        onEvent?.invoke(Event.KEY)
        notifyChanged()
    }

    /** Swipe: take the tape out in your mind's eye and turn it over. */
    fun turnOver() {
        if (hold || flipping || tape == null || door > 0.05f) return
        mode = Mode.STOP; flipping = true; flip = 0f
        onEvent?.invoke(Event.KEY); wake()
    }

    /** Winding by hand: [mm] of tape pulled rightward (in the shell's own frame) by a reel turned under a finger. */
    fun windByHand(mm: Double) {
        val t = tape ?: return
        val L = t.sideSecs * TAPE_MM_S
        val dx = (if (facing == 'A') 1 else -1) * mm / L
        val nx = (x + dx).coerceIn(0.0, 1.0)
        if (nx == x) return
        val moved = ((nx - x) * L * (if (facing == 'A') 1 else -1)).toFloat()
        val (rl, rr) = packs()
        angL += moved / rl; angR += moved / rr; x = nx
        Audio.release()
    }

    var pendingDirect = false; private set

    /**
     * Choose a tape from the shelf. Folded ([direct] false): the door opens, the tapes swap, it
     * shuts and plays. Unfolded ([direct] true): the door is already open, so the tape flies
     * straight into the compartment, seats with a clunk and plays.
     */
    fun load(t: Tape, direct: Boolean) {
        if (hold) { onEvent?.invoke(Event.REFUSED); return }
        if (t === tape && door < 0.05f) { if (mode == Mode.STOP) press(Key.PLAY); return }
        save()
        mode = Mode.STOP
        pending = t; pendingDirect = direct; pendingSince = SystemClock.uptimeMillis(); swappedAt = 0L
        if (!direct) doorTarget = 1f
        wake(); notifyChanged()
    }

    /** Unfolding opens the whole compartment, so the cover screen's door has nothing left to do. */
    fun doorShutQuietly() { door = 0f; doorTarget = 0f }

    private fun insert(t: Tape) {
        tape = t
        val p = prefs()
        // Every tape is where you left it
        x = p.getFloat("x_${t.albumId}", 0f).toDouble().coerceIn(0.0, 1.0)
        facing = if (p.getBoolean("b_${t.albumId}", false)) 'B' else 'A'
        dir = p.getInt("d_${t.albumId}", if (facing == 'A') 1 else -1)
        v = 0.0; amsTarget = null; blank = false
        Audio.release()
        p.edit().putLong("tape", t.albumId).apply()
    }

    /** The shelf was rebuilt (a setting changed): keep the same tape in the deck, where it is. */
    fun relink() {
        val t = tape ?: return
        Library.find(t.albumId)?.let { n ->
            tape = n
            Audio.track?.let { tr -> (n.a + n.b).firstOrNull { it.id == tr.id }?.let { Audio.relinkTrack(it) } }
        }
        notifyChanged()
    }

    fun save() {
        val t = tape ?: return
        prefs().edit()
            .putFloat("x_${t.albumId}", x.toFloat())
            .putBoolean("b_${t.albumId}", facing == 'B')
            .putInt("d_${t.albumId}", dir)
            .putLong("tape", t.albumId)
            .apply()
    }

    // ---- Time ----------------------------------------------------------------------------------
    private val ticker = object : Runnable {
        override fun run() {
            tick(SystemClock.uptimeMillis())
            if (animating()) main.postDelayed(this, 16) else if (mode == Mode.PLAY) main.postDelayed(this, 250)
        }
    }
    fun wake() { main.removeCallbacks(ticker); main.post(ticker) }
    fun animating() = (engage > 0f && engage < 1f) || mode == Mode.FF || mode == Mode.REW || mode == Mode.AMS || abs(v) > 0.002 && mode != Mode.PLAY ||
        door != doorTarget || flipping || pending != null || conc > 0.002f || (mode == Mode.PLAY && abs(v - dir) > 0.01)

    /** Advance the machine to [now] (uptime ms). Safe to call from every frame and from the ticker. */
    fun tick(now: Long) {
        val dt = if (last == 0L) 0.0 else ((now - last) / 1000.0).coerceIn(0.0, 0.05)
        last = now
        val t = tape ?: return
        val L = t.sideSecs

        var target = 0.0
        if (door < 0.02f && !flipping) target = when (mode) {
            Mode.PLAY -> dir.toDouble()
            Mode.FF -> dir * Prefs.windX.toDouble()
            Mode.REW -> -dir * Prefs.windX.toDouble()
            Mode.AMS -> amsTarget?.let { sign(it - x) * Prefs.windX.toDouble() } ?: 0.0
            Mode.STOP -> 0.0
        }
        // Motor: winding spins up over about a third of a second; play starts almost at once
        val tau = if (abs(target) > 2 || abs(v) > 2) 0.28 else 0.06
        v += (target - v) * (1 - exp(-dt / tau))
        if (abs(v) < 0.002 && target == 0.0) v = 0.0
        if (dragging) v = 0.0

        val audioMaster = Audio.isPlaying() && mode == Mode.PLAY
        val prevX = x
        x = if (audioMaster) {
            val tr = Audio.track
            if (tr != null) xFromSidePos(tr.start + Audio.positionSecs()).coerceIn(0.0, 1.0) else x
        } else (x + v * dt / L).coerceIn(0.0, 1.0)

        // Music sensor arrival
        amsTarget?.let { a ->
            if (mode == Mode.AMS && (a - prevX) * (a - x) <= 0) { x = a; amsTarget = null; mode = Mode.PLAY; v = dir.toDouble(); notifyChanged() }
        }
        // End of what was recorded: wind past the blank
        if (mode == Mode.PLAY && !audioMaster && sidePos() >= sideEnd() * L - 0.05 && sidePos() < L - 0.5) { mode = Mode.FF; blank = true }
        // End of the side
        if ((x <= 0 && v < 0) || (x >= 1 && v > 0)) {
            val atSideEnd = (dir > 0 && x >= 1) || (dir < 0 && x <= 0)
            v = 0.0
            if (atSideEnd && (mode == Mode.PLAY || blank) && autoRev) { dir = -dir; mode = Mode.PLAY; blank = false }
            else { mode = Mode.STOP; blank = false }
            onEvent?.invoke(Event.KEY); notifyChanged()
        }

        // Reels: each turns at tape speed over its own radius
        val (rl, rr) = packs()
        val mmPerS = (x - prevX) / dt.coerceAtLeast(1e-6) * L * TAPE_MM_S
        val rightward = (if (facing == 'A') 1 else -1) * mmPerS
        if (!dragging && dt > 0) { angL += (rightward / rl * dt).toFloat(); angR += (rightward / rr * dt).toFloat() }

        // The transport follows the reels: the head is in only for play, out for stop and winding.
        // It travels in about a tenth of a second, the click of a real mechanism, never springing back
        val headIn = mode == Mode.PLAY && door < 0.02f && !flipping
        val et = if (headIn) 1f else 0f
        engage += (et - engage) * (1 - exp(-dt / if (headIn) 0.035 else 0.045)).toFloat()
        if (abs(engage - et) < 0.003f) engage = et
        tapeMmS = if (dt > 0) rightward.toFloat() else 0f
        if (dt > 0) {
            guideAng += (rightward / GUIDE_R * dt).toFloat()
            // The pinch roller only turns while it holds the tape against the capstan
            if (engage > 0.6f) {
                pinchAng -= (rightward / PINCH_R * dt).toFloat()
                capstanAng += (rightward / CAPSTAN_R * dt).toFloat()
            }
        }

        // Concentrato: emptier the faster it winds
        val sp = abs(v)
        val u = ((sp - 4) / 18).coerceIn(0.0, 1.0)
        val ct = if (Prefs.concentrato) (u * u * (3 - 2 * u)).toFloat() else 0f
        conc += (ct - conc) * (1 - exp(-dt / 0.12)).toFloat()

        // Door and turning over
        door += (doorTarget - door) * (1 - exp(-dt / 0.11)).toFloat()
        if (abs(door - doorTarget) < 0.002f) door = doorTarget
        if (flipping) {
            flip += (dt / 0.55).toFloat()
            if (flip >= 1f) {
                flip = 0f; flipping = false
                facing = if (facing == 'A') 'B' else 'A'
                dir = if (facing == 'A') 1 else -1
                Audio.release(); onEvent?.invoke(Event.FLIP); save(); notifyChanged()
            }
        }

        // Loading
        pending?.let { p ->
            if (pendingDirect) {
                if (now - pendingSince > FLIGHT_MS) {
                    insert(p); pending = null; onEvent?.invoke(Event.CLUNK)
                    startService(); mode = Mode.PLAY; notifyChanged()
                }
                return@let
            }
            if (door > 0.9f && swappedAt == 0L) { insert(p); swappedAt = now; notifyChanged() }
            if (swappedAt != 0L && now - swappedAt > 420) doorTarget = 0f
            if (swappedAt != 0L && door < 0.02f && now - swappedAt > 500) {
                pending = null; onEvent?.invoke(Event.CLUNK)
                startService(); mode = Mode.PLAY; notifyChanged()
            }
        }

        syncAudio()
        Sfx.update(v, mode == Mode.PLAY)
        if (now - lastSave > 5000) { lastSave = now; save() }
    }

    // ---- Audio follows the tape -----------------------------------------------------------------
    private fun syncAudio() {
        val want = mode == Mode.PLAY && abs(v - dir) < 0.08 && door < 0.02f && !flipping && !dragging
        if (want) {
            val cur = current()
            val tr = cur.track ?: return
            if (Audio.track?.id != tr.id || !Audio.isActive()) Audio.play(app, tr, cur.into)
        } else if (Audio.isActive()) {
            Audio.pause()
        }
    }

    /** A track finished: the next one on the side, or the blank and the turn. */
    internal fun trackEnded(tr: Track) {
        val L = sideList(); val i = L.indexOf(tr)
        val next = L.getOrNull(i + 1)
        if (next != null) { x = xFromSidePos(next.start); Audio.play(app, next, 0.0) }
        else { x = xFromSidePos(tr.start + tr.durSecs); Audio.release(); mode = Mode.FF; blank = true }
        wake(); notifyChanged()
    }

    private fun startService() {
        runCatching { app.startForegroundService(Intent(app, PlaybackService::class.java)) }
    }
}

/** One MediaPlayer, prepared off the main thread's frame budget. */
object Audio {
    private var mp: MediaPlayer? = null
    private var bass: BassBoost? = null
    var track: Track? = null; private set
    private var prepared = false
    private var wantPlaying = false
    private var seeking = false
    private var offset = 0.0
    private var focus: AudioFocusRequest? = null

    fun isActive() = mp != null && wantPlaying
    fun isPlaying() = prepared && !seeking && runCatching { mp?.isPlaying == true }.getOrDefault(false)
    fun positionSecs(): Double = if (prepared && !seeking) (mp?.currentPosition ?: 0) / 1000.0 else offset
    val sessionId get() = mp?.audioSessionId ?: 0

    fun play(ctx: Context, tr: Track, offsetSecs: Double) {
        if (!requestFocus(ctx)) { Deck.stop(); return }
        if (track?.id == tr.id && mp != null) {
            wantPlaying = true; offset = offsetSecs
            if (prepared) {
                val p = mp!!
                // The tape may have been wound since this track paused: play from where it is now
                if (abs(p.currentPosition / 1000.0 - offsetSecs) > 0.8) { seeking = true; p.seekTo((offsetSecs * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
                if (!p.isPlaying) p.start()
            }
            return
        }
        release()
        track = tr; wantPlaying = true; offset = offsetSecs
        val p = MediaPlayer()
        mp = p
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setWakeMode(ctx, android.os.PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnSeekCompleteListener { if (mp === it) seeking = false }
        p.setOnPreparedListener {
            if (mp !== it) return@setOnPreparedListener
            prepared = true
            bass(Deck.megaBass)
            val ms = (offset * 1000).toInt()
            if (ms > 300) { seeking = true; it.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) }
            if (wantPlaying) it.start()
        }
        p.setOnCompletionListener { if (mp === it) Deck.trackEnded(tr) }
        p.setOnErrorListener { mp2, _, _ -> if (mp === mp2) Deck.trackEnded(tr); true }
        runCatching {
            p.setDataSource(ctx, tr.uri)
            p.prepareAsync()
        }.onFailure { Deck.trackEnded(tr) }
    }

    internal fun relinkTrack(tr: Track) { track = tr }

    fun pause() {
        wantPlaying = false
        runCatching { if (prepared && mp?.isPlaying == true) mp?.pause() }
    }

    fun release() {
        wantPlaying = false; prepared = false; seeking = false; track = null
        runCatching { bass?.release() }; bass = null
        runCatching { mp?.release() }; mp = null
    }

    /** Mega Bass: the platform's bass boost on this player's session. */
    fun bass(on: Boolean) {
        val p = mp ?: return
        runCatching {
            if (bass == null) bass = BassBoost(0, p.audioSessionId)
            bass?.setStrength(if (on) 1000 else 0)
            bass?.enabled = on
        }
    }

    private fun requestFocus(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) Deck.stop()
            }.build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}
