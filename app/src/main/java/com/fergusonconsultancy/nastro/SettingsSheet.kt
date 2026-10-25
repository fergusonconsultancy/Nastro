package com.fergusonconsultancy.nastro

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What lives behind the battery door. Rises from the foot of the screen; a tap outside, Close
 * or Back puts it away. Settings that change the shelf rebuild it without stopping the tape.
 */
class SettingsSheet(ctx: Context, private val shelfChanged: () -> Unit) : FrameLayout(ctx) {
    private val dens = resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * dens + 0.5f).toInt()

    private val INK = 0xFFE6EBF0.toInt()
    private val MUTED = 0xFF8D98A5.toInt()
    private val LED = 0xFFFFAD33.toInt()
    private val GROUND = 0xFF12161B.toInt()
    private val RULE = 0xFF232A33.toInt()

    private val print: Typeface = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/BarlowCondensed-SemiBold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
    private val ui: Typeface = runCatching { Typeface.Builder(ctx.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' 450").build() }.getOrNull() ?: Typeface.SANS_SERIF
    private val hand: Typeface = runCatching { Typeface.Builder(ctx.assets, "fonts/Caveat.ttf").setFontVariationSettings("'wght' 500").build() }.getOrNull() ?: Typeface.SERIF

    private val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx)
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val refreshers = mutableListOf<() -> Unit>()

    val showing get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        setBackgroundColor(0x8C05070A.toInt())
        isClickable = true
        setOnClickListener { hide() }
        scroll.background = GradientDrawable().apply {
            setColor(GROUND)
            cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), RULE)
        }
        scroll.isClickable = true
        scroll.isVerticalScrollBarEnabled = false
        panel.setPadding(dp(22), dp(22), dp(22), dp(30))
        scroll.addView(panel)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        (scroll.layoutParams as LayoutParams).width = minOf(w, dp(540))
        scroll.layoutParams.height = LayoutParams.WRAP_CONTENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (scroll.measuredHeight > h * 0.88f) {
            scroll.measure(MeasureSpec.makeMeasureSpec(scroll.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((h * 0.88f).toInt(), MeasureSpec.EXACTLY))
        }
    }

    private fun text(s: String, size: Float, color: Int, face: Typeface, track: Float = 0f) = TextView(context).apply {
        text = s; textSize = size; setTextColor(color); typeface = face; letterSpacing = track; includeFontPadding = false
    }

    private fun build() {
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("BEHIND THE BATTERY DOOR", 15f, INK, print, 0.24f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("CLOSE", false) { hide() })
        panel.addView(head)
        panel.addView(text("settings", 21f, MUTED, hand).apply { setPadding(0, dp(4), 0, dp(12)) })

        row("Finish", "Light blue and grey were the WM-EX610's own.", finishRow())
        row("Mechanism sounds", "Keys, door, tape seating, the motor winding.", choice(listOf("On" to true, "Off" to false), { Prefs.sounds }) { Prefs.sounds = it })
        row("Tape hiss", "A faint ferric hiss under the music.", choice(listOf("Off" to false, "Subtle" to true), { Prefs.hiss }) { Prefs.hiss = it })
        row("Fast wind", "Real is 30 times play speed, as on the machine.", choice(listOf("Real" to 30, "Quick" to 60), { Prefs.windX }) { Prefs.windX = it })
        row("Auto reverse", "Turn round at the end of a side instead of stopping.", choice(listOf("On" to true, "Off" to false), { Deck.autoRev }) { if (it != Deck.autoRev) Deck.toggle("autoRev") })
        row("Mega Bass", "The phone's bass boost on the tape.", choice(listOf("On" to true, "Off" to false), { Deck.megaBass }) { if (it != Deck.megaBass) Deck.toggle("megaBass") })
        row("Concentrato", "The deck empties as the tape winds faster.", choice(listOf("On" to true, "Off" to false), { Prefs.concentrato }) { Prefs.concentrato = it })
        row("Door swing on unfolding", "Turn off if it doubles up with Fold Motion.", choice(listOf("On" to true, "Off" to false), { Prefs.doorSwing }) { Prefs.doorSwing = it })
        row("Singles tapes", "An artist's singles share one tape.", choice(listOf("On" to true, "Off" to false), { Prefs.singles }) { if (it != Prefs.singles) { Prefs.singles = it; shelfChanged() } })
        row("Shelf order", "Artist and album have an A–Z index in the door's edge.", choice(listOf("Artist" to "artist", "Album" to "album", "Newest" to "newest"), { Prefs.order }) { if (it != Prefs.order) { Prefs.order = it; shelfChanged() } })
        row("Haptics", "A click under every key.", choice(listOf("On" to true, "Off" to false), { Prefs.haptics }) { Prefs.haptics = it })
    }

    private fun row(title: String, sub: String, control: View) {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        val lab = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lab.addView(text(title, 15f, INK, ui))
        lab.addView(text(sub, 12.5f, MUTED, ui).apply { setPadding(0, dp(3), dp(10), 0); setLineSpacing(0f, 1.2f) })
        r.addView(lab, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(control)
        panel.addView(View(context).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
        panel.addView(r)
    }

    private fun pill(label: String, selected: Boolean, onTap: () -> Unit) = text(label, 12.5f, if (selected) 0xFF14100A.toInt() else MUTED, print, 0.12f).apply {
        setPadding(dp(13), dp(9), dp(13), dp(9))
        background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(if (selected) LED else 0xFF1A2027.toInt()); setStroke(dp(1), if (selected) LED else RULE) }
        setOnClickListener { Sfx.play(Sfx.S.STOP, 0.4f); onTap() }
    }

    private fun <T> choice(opts: List<Pair<String, T>>, get: () -> T, set: (T) -> Unit): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun refresh() {
            box.removeAllViews()
            for ((i, o) in opts.withIndex()) {
                box.addView(pill(o.first.uppercase(), get() == o.second) { set(o.second); refreshAll() },
                    LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(4) })
            }
        }
        refresh(); refreshers += ::refresh
        return box
    }

    private fun finishRow(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = linkedMapOf(
            "blue" to intArrayOf(0xFFBFD4E8.toInt(), 0xFF7593B3.toInt()), "grey" to intArrayOf(0xFFE2E5E9.toInt(), 0xFF979EA6.toInt()),
            "graphite" to intArrayOf(0xFF61676F.toInt(), 0xFF2A2E33.toInt()), "rosso" to intArrayOf(0xFFD2363E.toInt(), 0xFF6E0A12.toInt()),
        )
        fun refresh() {
            box.removeAllViews()
            for ((i, e) in swatches.entries.withIndex()) {
                val v = View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, e.value).apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dp(2), if (Prefs.finish == e.key) LED else 0x00000000)
                    }
                    contentDescription = e.key
                    setOnClickListener { Sfx.play(Sfx.S.STOP, 0.4f); Prefs.finish = e.key; refreshAll() }
                }
                box.addView(v, LinearLayout.LayoutParams(dp(32), dp(32)).apply { if (i > 0) marginStart = dp(8) })
            }
        }
        refresh(); refreshers += ::refresh
        return HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(box) }
    }

    private fun refreshAll() { refreshers.forEach { it() }; (parent as? View)?.invalidate() }

    fun show() {
        refreshAll()
        visibility = View.VISIBLE
        alpha = 0f; animate().alpha(1f).setDuration(180).start()
        scroll.translationY = dp(600).toFloat()
        scroll.animate().translationY(0f).setDuration(320).setInterpolator(ease).start()
    }

    fun hide() {
        Sfx.play(Sfx.S.SEAT, 0.4f)
        scroll.animate().translationY(scroll.height.toFloat()).setDuration(240).setInterpolator(ease).start()
        animate().alpha(0f).setDuration(240).withEndAction { visibility = View.GONE }.start()
    }
}
