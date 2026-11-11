package com.fergusonconsultancy.nastro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mixtapes (11 Nov 2026): a single tape of the songs you choose, in the order you choose, under a
 * name you write on the label, as everyone made them off the radio and their records. Kept as the
 * songs' ids, so a mixtape follows the music on the phone; a song that has gone is simply skipped.
 */
object Mixtapes {
    class Mix(val id: Long, val name: String, val shell: String, val tracks: List<Long>, val created: Long)

    /** Mixtape ids sit far below the singles tapes' negative ids, so the two can never meet. */
    private const val BASE = -(1L shl 40)
    fun isMix(id: Long) = id <= BASE

    private lateinit var sp: SharedPreferences
    private var cache: List<Mix>? = null

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("mixtapes", Context.MODE_PRIVATE)
    }

    fun all(): List<Mix> {
        cache?.let { return it }
        if (!::sp.isInitialized) return emptyList()
        val out = runCatching {
            val arr = JSONArray(sp.getString("list", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("tracks")
                Mix(o.getLong("id"), o.optString("name", "Mixtape"), o.optString("shell", ""), (0 until t.length()).map { t.getLong(it) }, o.optLong("created", 0L))
            }
        }.getOrDefault(emptyList())
        cache = out
        return out
    }

    fun get(id: Long): Mix? = all().firstOrNull { it.id == id }

    fun newId(): Long = BASE - System.currentTimeMillis()

    fun put(m: Mix) = write(all().filter { it.id != m.id } + m)

    fun remove(id: Long) = write(all().filter { it.id != id })

    private fun write(list: List<Mix>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().apply {
            put("id", m.id); put("name", m.name); put("shell", m.shell); put("created", m.created)
            put("tracks", JSONArray().apply { m.tracks.forEach { put(it) } })
        })
        sp.edit().putString("list", arr.toString()).apply()
        cache = list
    }
}
