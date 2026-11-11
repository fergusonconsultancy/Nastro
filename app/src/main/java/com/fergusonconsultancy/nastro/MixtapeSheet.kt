package com.fergusonconsultancy.nastro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Making a mixtape (11 Nov 2026). The way it was done: pick a blank, write its name on the label,
 * then put the songs on one after another, watching the tape fill. Side A fills, then side B; the
 * tape is the shortest that holds them, C60, C90 or C120, split at the song nearest half way as
 * every album on the shelf is.
 *
 * Opened from the blank tape at the head of the shelf, or by holding a mixtape to write on it again.
 */
class MixtapeSheet(ctx: Context, private val done: (id: Long, deleted: Boolean) -> Unit) : FrameLayout(ctx) {
    private val dens = resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * dens + 0.5f).toInt()

    private val INK = 0xFFE6EBF0.toInt()
    private val MUTED = 0xFF8D98A5.toInt()
    private val LED = 0xFFFFAD33.toInt()
    private val GROUND = 0xFF12161B.toInt()
    private val RULE = 0xFF232A33.toInt()
    private val BIRO = 0xFF1F2C6B.toInt()
    private val CARD = 0xFFF4F1E8.toInt()

    private val print: Typeface = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/BarlowCondensed-SemiBold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
    private val ui: Typeface = runCatching { Typeface.Builder(ctx.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' 450").build() }.getOrNull() ?: Typeface.SANS_SERIF
    private val hand: Typeface = runCatching { Typeface.Builder(ctx.assets, "fonts/Caveat.ttf").setFontVariationSettings("'wght' 600").build() }.getOrNull() ?: Typeface.SERIF
    private val painter by lazy { Painter(ctx) }
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    // What is being made
    private var editing: Mixtapes.Mix? = null
    private val chosen = ArrayList<Long>()
    private var shellId = Shells.ALL[0].id
    private var confirmDelete = false
    private var tab = 0                       // 0 on the tape, 1 add songs
    private var query = ""
    private var found: List<Song> = emptyList()

    private val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val title = text("NEW MIXTAPE", 15f, INK, print, 0.24f)
    private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val name = EditText(ctx)
    private val shells = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val summary = text("", 12.5f, MUTED, ui)
    private val fill = SideFill(ctx)
    private val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val onTape = ListView(ctx)
    private val adding = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val search = EditText(ctx)
    private val songsList = ListView(ctx)
    private val emptyNote = text("", 14f, MUTED, ui).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(28), dp(24), dp(28)) }

    val showing get() = visibility == View.VISIBLE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val h = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        (panel.layoutParams as LayoutParams).apply { width = minOf(w, dp(600)); height = (h * 0.94f).toInt() }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun text(s: String, size: Float, color: Int, face: Typeface, track: Float = 0f) = TextView(context).apply {
        text = s; textSize = size; setTextColor(color); typeface = face; letterSpacing = track; includeFontPadding = false
    }

    private fun pill(label: String, selected: Boolean, color: Int = LED, onTap: () -> Unit) = text(label, 12.5f, if (selected) 0xFF14100A.toInt() else MUTED, print, 0.12f).apply {
        setPadding(dp(13), dp(9), dp(13), dp(9))
        background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(if (selected) color else 0xFF1A2027.toInt()); setStroke(dp(1), if (selected) color else RULE) }
        setOnClickListener { Sfx.play(Sfx.S.STOP, 0.4f); onTap() }
    }

    private fun build() {
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(actions)
        panel.addView(head)

        // The label: write its name, in biro, on the card
        name.apply {
            typeface = hand; textSize = 28f; setTextColor(BIRO); setHintTextColor(BIRO.a(0.38f)); hint = "write its name…"
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(4).toFloat() }
            setPadding(dp(14), dp(6), dp(14), dp(4))
            setOnEditorActionListener { v, _, _ -> hideKeyboard(v); v.clearFocus(); true }
            addTextChangedListener(after { shellsRefresh() })
        }
        panel.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(shells) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        panel.addView(fill, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        panel.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        panel.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(6) })

        val content = FrameLayout(context)
        listStyle(onTape); listStyle(songsList)
        onTape.adapter = tapeAdapter
        songsList.adapter = songAdapter
        songsList.setOnItemClickListener { _, _, pos, _ -> found.getOrNull(pos)?.let { toggle(it.id) } }
        search.apply {
            typeface = ui; textSize = 15f; setTextColor(INK); setHintTextColor(MUTED); hint = "Find a song, artist or album"
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply { setColor(0xFF1A2027.toInt()); cornerRadius = dp(999).toFloat(); setStroke(dp(1), RULE) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
            addTextChangedListener(after { query = it.trim().lowercase(); filter() })
        }
        adding.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        adding.addView(songsList, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(onTape, LayoutParams(-1, -1))
        content.addView(adding, LayoutParams(-1, -1))
        content.addView(emptyNote, LayoutParams(-1, -2, Gravity.TOP))
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun listStyle(l: ListView) {
        l.divider = GradientDrawable().apply { setColor(RULE) }; l.dividerHeight = dp(1)
        l.selector = GradientDrawable().apply { setColor(0x14FFFFFF) }
        l.isVerticalScrollBarEnabled = false
        l.isFastScrollEnabled = false
        l.cacheColorHint = 0
    }

    private fun after(f: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
    }

    // ---- opening and closing -----------------------------------------------------------------------
    /** [tape] null: a new blank. Otherwise the mixtape to write on again. */
    fun open(tape: Tape?) {
        val m = tape?.let { Mixtapes.get(it.albumId) }
        editing = m
        chosen.clear(); m?.tracks?.filterTo(chosen) { Library.song(it) != null }
        shellId = m?.shell?.takeIf { Shells.byId(it) != null } ?: Shells.ALL[(System.currentTimeMillis() / 1000 % Shells.ALL.size).toInt()].id
        name.setText(m?.name ?: "")
        query = ""; search.setText("")
        confirmDelete = false
        tab = if (chosen.isEmpty()) 1 else 0
        title.text = if (m == null) "NEW MIXTAPE" else "MIXTAPE"
        filter(); refresh()
        visibility = View.VISIBLE
        alpha = 0f; animate().alpha(1f).setDuration(180).start()
        panel.translationY = dp(700).toFloat()
        panel.animate().translationY(0f).setDuration(340).setInterpolator(ease).start()
        Sfx.play(Sfx.S.DOOR_OPEN, 0.5f)
    }

    fun cancel() = close()

    private fun close() {
        hideKeyboard(this)
        Sfx.play(Sfx.S.DOOR_SHUT, 0.4f)
        panel.animate().translationY(panel.height.toFloat()).setDuration(240).setInterpolator(ease).start()
        animate().alpha(0f).setDuration(240).withEndAction { visibility = View.GONE }.start()
    }

    private fun save() {
        if (chosen.isEmpty()) {
            summary.text = "Put at least one song on it first."; summary.setTextColor(LED)
            tab = 1; refresh(); return
        }
        val nm = name.text.toString().trim().ifBlank { "Mixtape" }
        val m = editing
        val id = m?.id ?: Mixtapes.newId()
        Mixtapes.put(Mixtapes.Mix(id, nm, shellId, chosen.toList(), m?.created ?: System.currentTimeMillis()))
        Sfx.play(Sfx.S.SEAT, 0.7f)
        close()
        done(id, false)
    }

    private fun delete() {
        val m = editing ?: return
        if (!confirmDelete) { confirmDelete = true; refresh(); return }
        Mixtapes.remove(m.id)
        close()
        done(m.id, true)
    }

    private fun hideKeyboard(v: View) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ---- what is on it -----------------------------------------------------------------------------
    private fun toggle(id: Long) {
        if (id in chosen) chosen.remove(id) else { chosen.add(id); Sfx.play(Sfx.S.WIND_KEY, 0.3f) }
        confirmDelete = false
        refresh()
    }

    private fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in chosen.indices) return
        val t = chosen[i]; chosen[i] = chosen[j]; chosen[j] = t
        refresh()
    }

    private fun filter() {
        val q = query
        found = if (q.isEmpty()) Library.songs else {
            val words = q.split(' ').filter { it.isNotEmpty() }
            Library.songs.filter { s -> words.all { s.key.contains(it) } }
        }
        songAdapter.notifyDataSetChanged()
        songsList.setSelection(0)
        refreshEmpty()
    }

    private fun refresh() {
        actions.removeAllViews()
        if (editing != null) actions.addView(pill(if (confirmDelete) "THROW AWAY?" else "DELETE", confirmDelete, 0xFFE0505A.toInt()) { delete() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        actions.addView(pill("CANCEL", false) { cancel() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        actions.addView(pill("SAVE", true) { save() })

        tabs.removeAllViews()
        tabs.addView(pill("ON THE TAPE · ${chosen.size}", tab == 0) { tab = 0; hideKeyboard(search); refresh() })
        tabs.addView(pill("ADD SONGS", tab == 1) { tab = 1; refresh() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        onTape.visibility = if (tab == 0) View.VISIBLE else View.GONE
        adding.visibility = if (tab == 1) View.VISIBLE else View.GONE

        // How it falls on the tape: the same rule as every album on the shelf
        val songs = chosen.mapNotNull { Library.song(it) }
        val t = Tape.build(1L, "", "mix", songs.map { Triple(it.id, it.title, Pair(it.durSecs, 0)) })
        fill.tape = t; fill.invalidate()
        summary.setTextColor(MUTED)
        summary.text = if (t == null) "A blank C60: 30 minutes a side." else {
            val a = t.a.sumOf { it.durSecs }; val b = t.b.sumOf { it.durSecs }
            "${songs.size} ${if (songs.size == 1) "song" else "songs"} on a C${t.length}  ·  side A ${mmss(a)}  ·  side B ${mmss(b)}  ·  ${mmss(t.sideSecs)} a side"
        }
        tapeAdapter.notifyDataSetChanged(); songAdapter.notifyDataSetChanged()
        shellsRefresh()
        refreshEmpty()
    }

    private fun refreshEmpty() {
        emptyNote.visibility = when {
            tab == 0 && chosen.isEmpty() -> { emptyNote.text = "Nothing on it yet. Add songs, in the order you want them."; View.VISIBLE }
            tab == 1 && found.isEmpty() -> { emptyNote.text = if (Library.songs.isEmpty()) "No songs on this phone yet." else "Nothing matches “$query”."; View.VISIBLE }
            else -> View.GONE
        }
        if (tab == 1 && found.isNotEmpty()) emptyNote.visibility = View.GONE
    }

    private fun mmss(secs: Double): String { val s = secs.toInt(); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    // ---- the shells to choose from -----------------------------------------------------------------
    private fun shellsRefresh() {
        val nm = name.text.toString().trim().ifBlank { "Mixtape" }
        if (shells.childCount != Shells.ALL.size) {
            shells.removeAllViews()
            for ((i, sh) in Shells.ALL.withIndex()) {
                shells.addView(ShellChip(context, sh).apply {
                    setOnClickListener { Sfx.play(Sfx.S.STOP, 0.4f); shellId = sh.id; shellsRefresh() }
                }, LinearLayout.LayoutParams(dp(104), dp(70)).apply { if (i > 0) marginStart = dp(6) })
            }
        }
        for (i in 0 until shells.childCount) (shells.getChildAt(i) as ShellChip).let { it.label = nm; it.isSelected = it.shell.id == shellId; it.invalidate() }
    }

    private inner class ShellChip(ctx: Context, val shell: Shell) : View(ctx) {
        var label = "Mixtape"
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = LED }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val t = Tape(0L, "", label, emptyList(), emptyList(), 1800.0, 60, shell, mix = true)
            val k = minOf((width - dp(10)) / (PlayerGeom.CW + 2), (height - dp(10)) / (PlayerGeom.CH + 2))
            c.save(); c.translate(width / 2f, height / 2f); c.scale(k, k)
            painter.cassette(c, t, false, 1f)
            c.restore()
            if (isSelected) { r.set(dp(1).toFloat(), dp(1).toFloat(), width - dp(1).toFloat(), height - dp(1).toFloat()); c.drawRoundRect(r, dp(8).toFloat(), dp(8).toFloat(), ring) }
        }
    }

    /** The two sides as the tape fills: each song a length of tape, the blank after it showing what is left. */
    private inner class SideFill(ctx: Context) : View(ctx) {
        var tape: Tape? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = print; color = MUTED; textSize = dp(12).toFloat() }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val t = tape
            val rowH = height / 2f
            val lab = dp(22).toFloat()
            for ((i, side) in listOf('A', 'B').withIndex()) {
                val y = i * rowH + rowH * 0.18f
                val h = rowH * 0.56f
                c.drawText(side.toString(), 0f, y + h * 0.85f, tp)
                r.set(lab, y, width.toFloat(), y + h)
                p.color = 0xFF1A2027.toInt(); c.drawRoundRect(r, h / 2, h / 2, p)
                if (t == null) continue
                val list = if (side == 'A') t.a else t.b
                val L = t.sideSecs
                val span = width - lab
                c.save(); c.clipPath(android.graphics.Path().apply { addRoundRect(r, h / 2, h / 2, android.graphics.Path.Direction.CW) })
                for ((j, tr) in list.withIndex()) {
                    val x0 = lab + (tr.start / L * span).toFloat(); val x1 = lab + ((tr.start + tr.durSecs) / L * span).toFloat()
                    // tape brown, alternating so each song can be told from the next
                    p.color = if (j % 2 == 0) 0xFF6B4A35.toInt() else 0xFF8A6246.toInt()
                    c.drawRect(x0, y, x1 - dp(1), y + h, p)
                }
                c.restore()
            }
        }
    }

    // ---- the lists ---------------------------------------------------------------------------------
    private class Row(val root: LinearLayout, val num: TextView, val line1: TextView, val line2: TextView, val tools: LinearLayout, val mark: TextView)

    private fun row(): Row {
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
        val num = text("", 14f, MUTED, print).apply { gravity = Gravity.END }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val l1 = text("", 15f, INK, ui).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val l2 = text("", 12.5f, MUTED, ui).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }
        col.addView(l1); col.addView(l2)
        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = text("", 20f, LED, print).apply { gravity = Gravity.CENTER }
        root.addView(num, LinearLayout.LayoutParams(dp(30), -2).apply { marginEnd = dp(12) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tools)
        root.addView(mark, LinearLayout.LayoutParams(dp(40), -2))
        return Row(root, num, l1, l2, tools, mark).also { root.tag = it }
    }

    private fun tool(label: String, onTap: () -> Unit) = text(label, 16f, INK, print).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(0xFF1A2027.toInt()) }
        setOnClickListener { Sfx.play(Sfx.S.STOP, 0.3f); onTap() }
    }

    private val tapeAdapter = object : BaseAdapter() {
        override fun getCount() = chosen.size
        override fun getItem(i: Int) = chosen[i]
        override fun getItemId(i: Int) = chosen[i]
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val r = (convert?.tag as? Row) ?: row()
            val s = Library.song(chosen[i])
            r.num.text = (i + 1).toString().padStart(2, '0')
            r.line1.text = s?.title ?: "Missing song"
            r.line1.setTextColor(INK)
            r.line2.text = listOfNotNull(s?.artist?.takeIf { it.isNotBlank() && it != "<unknown>" }, s?.let { mmss(it.durSecs) }).joinToString("  ·  ")
            r.tools.removeAllViews()
            r.tools.addView(tool("▲") { move(i, -1) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            r.tools.addView(tool("▼") { move(i, 1) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            r.tools.addView(tool("✕") { chosen.removeAt(i); refresh() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            r.mark.visibility = View.GONE
            r.num.visibility = View.VISIBLE
            return r.root
        }
    }

    private val songAdapter = object : BaseAdapter() {
        override fun getCount() = found.size
        override fun getItem(i: Int) = found[i]
        override fun getItemId(i: Int) = found[i].id
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val r = (convert?.tag as? Row) ?: row()
            val s = found[i]
            val on = s.id in chosen
            r.num.visibility = View.GONE
            r.line1.text = s.title
            r.line1.setTextColor(if (on) LED else INK)
            r.line2.text = listOf(s.artist, s.album, mmss(s.durSecs)).filter { it.isNotBlank() && it != "<unknown>" }.joinToString("  ·  ")
            r.tools.removeAllViews()
            r.mark.visibility = View.VISIBLE
            r.mark.text = if (on) "✓" else "+"
            r.mark.setTextColor(if (on) LED else MUTED)
            return r.root
        }
    }

    // Last, so every list and adapter above exists before it is wired up
    init {
        visibility = View.GONE
        setBackgroundColor(0x8C05070A.toInt())
        isClickable = true
        // a stray tap above the sheet must not lose a half-made tape
        setOnClickListener { hideKeyboard(this) }
        panel.background = GradientDrawable().apply {
            setColor(GROUND)
            cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), RULE)
        }
        panel.isClickable = true
        panel.setPadding(dp(20), dp(20), dp(20), dp(8))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        build()
    }
}
