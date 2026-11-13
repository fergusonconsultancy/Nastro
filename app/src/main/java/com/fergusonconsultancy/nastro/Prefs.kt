package com.fergusonconsultancy.nastro

import android.content.Context
import android.content.SharedPreferences

/** Everything behind the battery door. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val listeners = mutableListOf<() -> Unit>()

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    private fun b(k: String, d: Boolean) = if (::sp.isInitialized) sp.getBoolean(k, d) else d
    private fun s(k: String, d: String) = if (::sp.isInitialized) sp.getString(k, d) ?: d else d
    private fun i(k: String, d: Int) = if (::sp.isInitialized) sp.getInt(k, d) else d
    private fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v); is Int -> e.putInt(k, v) }
        e.apply(); changed()
    }

    /** visconti (the default) is the Alfa green; blue and grey were the WM-EX610's own; graphite and rosso are ours. */
    var finish: String get() = s("finish", "visconti"); set(v) = put("finish", v)
    var sounds: Boolean get() = b("sounds", true); set(v) = put("sounds", v)
    var hiss: Boolean get() = b("hiss", false); set(v) = put("hiss", v)
    /** Fast wind: 30 is the machine's own speed. */
    var windX: Int get() = i("windX", 30); set(v) = put("windX", v)
    var concentrato: Boolean get() = b("concentrato", true); set(v) = put("concentrato", v)
    var doorSwing: Boolean get() = b("doorSwing", true); set(v) = put("doorSwing", v)
    var haptics: Boolean get() = b("haptics", true); set(v) = put("haptics", v)
    var singles: Boolean get() = b("singles", true); set(v) = put("singles", v)
    /**
     * How big the tape can be drawn, as a fraction of its real size, on each screen's upright
     * layout (14 Nov 2026). The two screens share the smaller, so the tape behind the cover's lens
     * and the tape in the open compartment are the same physical size, as one tape would be.
     */
    fun fit(screen: String): Float = if (::sp.isInitialized) sp.getFloat("fit_$screen", 0f) else 0f
    fun setFit(screen: String, v: Float) {
        if (!::sp.isInitialized || kotlin.math.abs(fit(screen) - v) < 0.004f) return
        sp.edit().putFloat("fit_$screen", v).apply()
    }

    /** artist, album or newest */
    var order: String get() = s("order", "artist"); set(v) = put("order", v)
}
