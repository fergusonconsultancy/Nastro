package com.fergusonconsultancy.nastro

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import java.util.concurrent.Executors

/** One track as recorded onto a side: [start] is seconds from the start of that side. */
class Track(val id: Long, val title: String, val durSecs: Double, val number: Int, val start: Double) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/**
 * An album dubbed onto the shortest cassette that holds it. The tracks split into two sides at
 * the track nearest half way, as anyone taping an album did, and the rest of each side is blank.
 */
class Tape(
    val albumId: Long,
    val artist: String,
    val album: String,
    val a: List<Track>,
    val b: List<Track>,
    val sideSecs: Double,
    val length: Int,
    val shell: Shell,
    /** Whose cover goes on the J-card: the album itself, or the newest single on a singles tape. */
    val artId: Long = albumId,
    val singles: Boolean = false,
    /** When it arrived on the phone, newest track first: for "newest" shelf order. */
    var added: Long = 0L,
    /** A mixtape: songs of your own choosing, in your own order, under a name you wrote. */
    val mix: Boolean = false,
) {
    /** Shelf order and the index letter: artist, ignoring a leading "The". */
    val sortKey: String = artist.lowercase().removePrefix("the ").trim()
    val indexLetter: Char = sortKey.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
    val aEnd: Double = (a.lastOrNull()?.let { it.start + it.durSecs } ?: 0.0) / sideSecs
    val bEnd: Double = (b.lastOrNull()?.let { it.start + it.durSecs } ?: 0.0) / sideSecs
    val label: String = if (artist.isBlank() || artist == "<unknown>") album else "$artist – $album"
    val artUri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, artId)
    var art: Bitmap? = null

    /** J-card colours for when the album has no artwork: taken from the shell so they belong together. */
    val card: Int get() = CARDS[(albumId % CARDS.size).toInt().let { if (it < 0) -it else it }]

    companion object {
        private val CARDS = intArrayOf(
            0xFFC8553D.toInt(), 0xFF2D6A8E.toInt(), 0xFFE2B33C.toInt(), 0xFF4F7A4A.toInt(), 0xFF7A3B69.toInt(),
            0xFFD97A2B.toInt(), 0xFF1F4E5F.toInt(), 0xFFB23A48.toInt(), 0xFF3D3D6B.toInt(),
        )

        fun build(albumId: Long, artist: String, album: String, raw: List<Triple<Long, String, Pair<Double, Int>>>,
                  artId: Long = albumId, singles: Boolean = false, shell: Shell? = null, mix: Boolean = false): Tape? {
            if (raw.isEmpty()) return null
            val durs = raw.map { it.third.first.coerceAtLeast(1.0) }
            val total = durs.sum()
            var acc = 0.0
            var split = 0
            for (i in durs.indices) {
                if (acc + durs[i] / 2 > total / 2) break
                acc += durs[i]; split = i + 1
            }
            if (split == 0 && raw.size > 1) split = 1
            if (raw.size == 1) split = 1
            fun side(from: Int, to: Int): List<Track> {
                var t = 0.0
                return (from until to).map { i ->
                    Track(raw[i].first, raw[i].second, durs[i], i + 1, t).also { t += durs[i] }
                }
            }
            val sa = side(0, split)
            val sb = side(split, raw.size)
            val need = maxOf(sa.sumOf { it.durSecs }, sb.sumOf { it.durSecs })
            // C60, C90 or C120; anything longer gets a tape cut to length, rounded up to 15 minutes
            val length = when {
                need <= 1800 -> 60
                need <= 2700 -> 90
                need <= 3600 -> 120
                else -> (Math.ceil(need / 900.0).toInt() * 30)
            }
            val sh = shell ?: Shells.ALL[Math.floorMod(albumId * 31 + album.hashCode(), Shells.ALL.size.toLong()).toInt()]
            return Tape(albumId, artist, album, sa, sb, length * 30.0, length, sh, artId, singles, mix = mix)
        }
    }
}

/** A song on the phone, for choosing onto a mixtape. */
class Song(val id: Long, val title: String, val artist: String, val album: String, val durSecs: Double) {
    val key: String = "$title $artist $album".lowercase()
}

/** Reads the albums on the phone and turns each into a tape. Runs off the main thread. */
object Library {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Mixtapes first (newest made first), then the albums in shelf order. */
    var tapes: List<Tape> = emptyList()
        private set
    var loaded = false
        private set
    /** Every song on the phone, by artist, album and track: what a mixtape is chosen from. */
    var songs: List<Song> = emptyList()
        private set
    private var songById: Map<Long, Song> = emptyMap()
    private var albumTapes: List<Tape> = emptyList()

    /** The blank at the head of the shelf: picking it starts a new mixtape. Never goes in the deck. */
    val BLANK: Tape = Tape(Long.MIN_VALUE, "", "+ new mixtape", emptyList(), emptyList(), 1800.0, 60, Shells.ALL[1], mix = true)

    /** Settings that change the shelf (singles, order) rebuild it. */
    fun reload(ctx: Context, done: (List<Tape>) -> Unit) { loaded = false; load(ctx, done) }

    fun load(ctx: Context, done: (List<Tape>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val (albums, all) = runCatching { query(app) }.getOrDefault(Pair(emptyList(), emptyList()))
            main.post {
                albumTapes = albums; songs = all; songById = all.associateBy { it.id }
                tapes = mixtapes() + albums; loaded = true
                done(tapes)
            }
            // Covers arrive after the shelf is up
            for (t in albums) {
                val bmp = runCatching { app.contentResolver.loadThumbnail(t.artUri, Size(512, 512), null) }.getOrNull()
                if (bmp != null) main.post { t.art = bmp; Deck.notifyChanged() }
            }
        }
    }

    fun find(albumId: Long): Tape? = tapes.firstOrNull { it.albumId == albumId }

    fun song(id: Long): Song? = songById[id]

    /**
     * Mixtapes changed: rebuild them over the albums already read, without asking the phone again.
     * A mixtape in the deck that was rewritten starts again from the top of side A; one that was
     * thrown away leaves the deck for the next tape on the shelf.
     */
    fun refreshMixtapes(changedId: Long?, deleted: Boolean) {
        tapes = mixtapes() + albumTapes
        val inDeck = Deck.tape
        if (changedId != null && inDeck?.albumId == changedId) {
            val next = if (deleted) tapes.firstOrNull() else find(changedId)
            if (next != null) Deck.reseat(next, fromTop = true)
        } else Deck.relink()
    }

    private fun mixtapes(): List<Tape> = Mixtapes.all().sortedByDescending { it.created }.mapNotNull { m ->
        // Each song keeps its artist on the label's track list, since a mixtape mixes them
        val raw = m.tracks.mapNotNull { songById[it] }.map { s -> Triple(s.id, if (s.artist.isBlank() || s.artist == "<unknown>") s.title else "${s.title} - ${s.artist}", Pair(s.durSecs, 0)) }
        Tape.build(m.id, "", m.name, raw, shell = Shells.byId(m.shell), mix = true)?.also { it.added = m.created }
    }

    private fun query(ctx: Context): Pair<List<Tape>, List<Song>> {
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.DATE_ADDED,
        )
        val byAlbum = LinkedHashMap<Long, MutableList<Array<Any?>>>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000", null,
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val albumId = c.getLong(4)
                byAlbum.getOrPut(albumId) { mutableListOf() }.add(
                    arrayOf(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3), c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7), c.getLong(8))
                )
            }
        }
        val out = ArrayList<Tape>()
        fun artistOf(first: Array<Any?>) = (first[6] as String?)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: first[5] as String
        fun raw(rows: List<Array<Any?>>) = rows.map { Triple(it[0] as Long, it[1] as String, Pair((it[2] as Long) / 1000.0, it[3] as Int)) }
        // Singles (one or two tracks) by the same artist go onto one tape, the way they were taped off
        // the radio; a lone single keeps its own.
        val singlesBy = LinkedHashMap<String, MutableList<Pair<Long, MutableList<Array<Any?>>>>>()
        for ((albumId, rows) in byAlbum) {
            // TRACK is disc*1000 + track on many files; sorting by it keeps disc order too
            rows.sortWith(compareBy<Array<Any?>> { it[3] as Int }.thenBy { it[1] as String })
            if (Prefs.singles && rows.size <= 2) singlesBy.getOrPut(artistOf(rows.first()).lowercase()) { mutableListOf() } += albumId to rows
        }
        val merged = HashSet<Long>()
        for ((_, group) in singlesBy) {
            if (group.size < 2) continue
            val rows = group.flatMap { it.second }
            val artist = artistOf(rows.first())
            val id = -(1L + (artist.lowercase().hashCode().toLong() and 0x7FFFFFFF))
            Tape.build(id, artist, "Singles", raw(rows), artId = group.maxOf { it.first }, singles = true)?.let { it.added = rows.maxOf { r -> r[7] as Long }; out += it }
            group.forEach { merged += it.first }
        }
        for ((albumId, rows) in byAlbum) {
            if (albumId in merged) continue
            Tape.build(albumId, artistOf(rows.first()), rows.first()[4] as String, raw(rows))?.let { it.added = rows.maxOf { r -> r[7] as Long }; out += it }
        }
        when (Prefs.order) {
            "album" -> out.sortWith(compareBy<Tape> { it.album.lowercase().removePrefix("the ") }.thenBy { it.sortKey })
            "newest" -> out.sortWith(compareByDescending<Tape> { it.added }.thenBy { it.sortKey })
            else -> out.sortWith(compareBy<Tape> { it.sortKey }.thenBy { it.singles }.thenBy { it.album.lowercase() })
        }
        // Every song, for the mixtape editor: by artist (ignoring a leading "The"), album, then track
        val all = ArrayList<Song>()
        for ((_, rows) in byAlbum) for (r in rows) {
            val artist = (r[5] as String).takeIf { it.isNotBlank() && it != "<unknown>" } ?: (r[6] as String?) ?: ""
            all += Song(r[0] as Long, r[1] as String, artist, r[4] as String, (r[2] as Long) / 1000.0)
        }
        all.sortWith(compareBy<Song> { it.artist.lowercase().removePrefix("the ") }.thenBy { it.album.lowercase() })
        return out to all
    }
}

/**
 * Cassette shells drawn from the tapes in Mechack's photos, named generically with no makers'
 * marks. [clear] is how much of the tape inside shows through the plastic.
 */
class Shell(
    val id: String,
    val body: Int,
    val clear: Float,
    val win: String,
    val hub: Int,
    val label: String,
    val labelBg: Int,
    val accent: Int,
    val print: Int,
    val extra: IntArray = IntArray(0),
)

object Shells {
    fun byId(id: String?): Shell? = ALL.firstOrNull { it.id == id }
    private fun c(v: Long) = v.toInt()
    val ALL = listOf(
        Shell("ferric", c(0xFF161616), 0f, "wide", c(0xFFECE8DF), "top", c(0xFFEFECE4), c(0xFFD0242C), c(0xFFEFECE4)),
        Shell("clear", c(0xFFC9CDD1), 0.78f, "none", c(0xFFE9E9E6), "strip", c(0xFFF4F2EC), c(0xFFA3242A), c(0xFF3A3F45)),
        Shell("stripe", c(0xFF262A35), 0.62f, "small", c(0xFFA7ABB2), "band", c(0xFFE7E7EA), c(0xFFE3262F), c(0xFFE7E7EA),
            intArrayOf(c(0xFFF5B700), c(0xFFF26A1B), c(0xFFE3262F))),
        Shell("oval", c(0xFF3A3F4A), 0.6f, "oval", c(0xFFEEEEEC), "strip", c(0xFFF1F0EC), c(0xFF1F8A70), c(0xFFE9ECEF)),
        Shell("silver", c(0xFF151515), 0f, "small", c(0xFFD8D8D8), "full", c(0xFFC9CDD2), c(0xFFD4202A), c(0xFFFFFFFF), intArrayOf(c(0xFF6FA8DC))),
        Shell("yellow", c(0xFFD6D8D6), 0.82f, "none", c(0xFFF2C919), "strip", c(0xFFF7E04A), c(0xFF1A8A5A), c(0xFF1F2A24), intArrayOf(c(0xFFB0177F))),
        Shell("index", c(0xFF4C4A66), 0.58f, "small", c(0xFFE3E3E3), "index", c(0xFFF6F6F2), c(0xFF2A7FD4), c(0xFF151515)),
        Shell("darkI", c(0xFF1D2027), 0.55f, "wide", c(0xFFD9D9D9), "strip", c(0xFFF2F0EA), c(0xFF8E1F2C), c(0xFF8E1F2C), intArrayOf(c(0xFFB59B5E))),
        Shell("chrome", c(0xFF1C1B19), 0f, "small", c(0xFFCFCFCF), "tag", c(0xFFF5F3EE), c(0xFFD9C49A), c(0xFFD9C49A)),
    )
}
