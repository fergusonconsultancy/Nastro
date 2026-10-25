package com.fergusonconsultancy.nastro

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.abs
import kotlin.random.Random

/**
 * The mechanism's sounds (tools/synth_sounds.py). Generated from noise, shaped to measurements of
 * a real cassette mechanism: broadband clicks that barely ring (about 15 ms to fall 20 dB) with a
 * low knock through the body, arriving as a main click and smaller settling clicks; the motor
 * winding is a high whine near 1,465 Hz over a hum near 312 Hz. Plus an optional bed of tape hiss.
 */
object Sfx {
    enum class S { PLAY, STOP, WIND_KEY, DOOR_OPEN, SEAT, DOOR_SHUT, FLIP }

    private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    private var windId = 0; private var hissId = 0
    private var windStream = 0; private var hissStream = 0
    private val loaded = HashSet<Int>()

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val c = ctx.applicationContext
        ids[S.PLAY] = p.load(c, R.raw.key_play, 1)
        ids[S.STOP] = p.load(c, R.raw.key_stop, 1)
        ids[S.WIND_KEY] = p.load(c, R.raw.key_wind, 1)
        ids[S.DOOR_OPEN] = p.load(c, R.raw.door_open, 1)
        ids[S.SEAT] = p.load(c, R.raw.tape_seat, 1)
        ids[S.DOOR_SHUT] = p.load(c, R.raw.door_shut, 1)
        ids[S.FLIP] = p.load(c, R.raw.tape_flip, 1)
        windId = p.load(c, R.raw.wind_loop, 1)
        hissId = p.load(c, R.raw.hiss_loop, 1)
        pool = p
    }

    fun play(s: S, gain: Float = 1f) {
        if (!Prefs.sounds) return
        val p = pool ?: return
        val id = ids[s] ?: return
        if (id !in loaded) return
        // no two presses quite alike
        p.play(id, gain, gain, 1, 0, 0.97f + Random.nextFloat() * 0.06f)
    }

    /** Called from the deck's tick: the whirr follows the reels; the hiss sits under playing tape. */
    fun update(speed: Double, playing: Boolean) {
        val p = pool ?: return
        val sp = abs(speed)
        val wx = Prefs.windX.toDouble()
        val wantWind = Prefs.sounds && sp > 1.5 && windId in loaded
        if (wantWind) {
            val vol = (0.08 + sp / wx * 0.24).coerceAtMost(0.32).toFloat()
            val rate = (0.55 + sp / wx * 0.6).coerceIn(0.5, 1.4).toFloat()
            if (windStream == 0) windStream = p.play(windId, vol, vol, 0, -1, rate)
            else { p.setVolume(windStream, vol, vol); p.setRate(windStream, rate) }
        } else if (windStream != 0) { p.stop(windStream); windStream = 0 }

        val wantHiss = Prefs.sounds && Prefs.hiss && playing && sp > 0.5 && hissId in loaded
        if (wantHiss && hissStream == 0) hissStream = p.play(hissId, 0.06f, 0.06f, 0, -1, 1f)
        else if (!wantHiss && hissStream != 0) { p.stop(hissStream); hissStream = 0 }
    }
}
