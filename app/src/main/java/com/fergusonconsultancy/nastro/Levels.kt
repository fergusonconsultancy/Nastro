package com.fergusonconsultancy.nastro

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * What the music is doing, for the hi-fi's meters and speaker cones (11 Nov 2026).
 *
 * Android only measures a playing sound for apps allowed the microphone, and a cassette player
 * has no business asking for that. So each song is read from the phone, decoded off the main
 * thread, and measured in 50 ms steps: the loudness of the left and right channels, and of the
 * bass below about 150 Hz. The meters then show the real song at the point the tape has reached.
 * Decoding runs far faster than playing, so the measurement is ready well ahead of the music.
 */
object Levels {
    const val HOP = 0.05

    private class Env(val id: Long, frames: Int) {
        val l = FloatArray(frames); val r = FloatArray(frames); val bass = FloatArray(frames)
        @Volatile var n = 0
        @Volatile var cancelled = false
    }

    private val cache = LinkedHashMap<Long, Env>()
    @Volatile private var working: Env? = null

    /** Start measuring [tr] if it isn't already. Keeps the last few songs. */
    fun prepare(ctx: Context, tr: Track) {
        synchronized(cache) {
            if (cache.containsKey(tr.id)) return
            working?.cancelled = true
            val env = Env(tr.id, (tr.durSecs / HOP).toInt() + 200)
            cache[tr.id] = env
            while (cache.size > 4) { val k = cache.keys.first(); if (k == tr.id) break; cache.remove(k) }
            working = env
            Thread({ runCatching { decode(ctx.applicationContext, tr, env) } }, "nastro-levels").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        }
    }

    /**
     * Left, right and bass at [secs] into [trackId], each 0..1 on the meters' scale (−42 dB to 0 dB
     * full scale), written into [out]. False when that part of the song hasn't been measured.
     */
    fun at(trackId: Long, secs: Double, out: FloatArray): Boolean {
        val env = synchronized(cache) { cache[trackId] } ?: return false
        val i = (secs / HOP).toInt()
        if (i < 0 || i >= env.n) return false
        out[0] = scale(env.l[i]); out[1] = scale(env.r[i]); out[2] = scale(env.bass[i] * 1.6f)
        return true
    }

    private fun scale(rms: Float): Float {
        if (rms <= 1e-5f) return 0f
        val db = 20f * kotlin.math.log10(rms)
        return ((db + 42f) / 42f).coerceIn(0f, 1f)
    }

    private fun decode(ctx: Context, tr: Track, env: Env) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, tr.uri, null)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val c = MediaCodec.createDecoderByType(mime); codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var chans = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // running sums for the current 50 ms step
            var sl = 0.0; var sr = 0.0; var sb = 0.0; var count = 0
            var lp = 0.0
            var hop = (rate * HOP).toInt().coerceAtLeast(1)
            var a = 1 - exp(-2 * PI * 150.0 / rate)
            while (!env.cancelled) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    chans = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    hop = (rate * HOP).toInt().coerceAtLeast(1)
                    a = 1 - exp(-2 * PI * 150.0 / rate)
                } else if (oi >= 0) {
                    val out = c.getOutputBuffer(oi)
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        out.order(ByteOrder.nativeOrder())
                        val ch = chans.coerceAtLeast(1)
                        if (floatPcm) {
                            val fb = out.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (f in 0 until frames) {
                                val L = fb.get(f * ch).toDouble(); val R = if (ch > 1) fb.get(f * ch + 1).toDouble() else L
                                lp += a * ((L + R) * 0.5 - lp)
                                sl += L * L; sr += R * R; sb += lp * lp; count++
                                if (count >= hop) { push(env, sl, sr, sb, count); sl = 0.0; sr = 0.0; sb = 0.0; count = 0 }
                            }
                        } else {
                            val sb16 = out.asShortBuffer()
                            val frames = sb16.remaining() / ch
                            for (f in 0 until frames) {
                                val L = sb16.get(f * ch) / 32768.0; val R = if (ch > 1) sb16.get(f * ch + 1) / 32768.0 else L
                                lp += a * ((L + R) * 0.5 - lp)
                                sl += L * L; sr += R * R; sb += lp * lp; count++
                                if (count >= hop) { push(env, sl, sr, sb, count); sl = 0.0; sr = 0.0; sb = 0.0; count = 0 }
                            }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (env.n >= env.l.size) break
            }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }

    private fun push(env: Env, sl: Double, sr: Double, sb: Double, n: Int) {
        val i = env.n
        if (i >= env.l.size) return
        env.l[i] = sqrt(sl / n).toFloat(); env.r[i] = sqrt(sr / n).toFloat(); env.bass[i] = sqrt(sb / n).toFloat()
        env.n = i + 1
    }
}
