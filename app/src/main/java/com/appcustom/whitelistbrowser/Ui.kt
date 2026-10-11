package com.appcustom.whitelistbrowser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The app's look (the same as the admin page): warm off-white, deep green, rounded corners, Figtree for
 * text and Bricolage Grotesque for headings. The fonts are packed into the app at build time
 * (assets/fonts); if they're missing, the phone's own font is used.
 */
object Ui {
    // The colours, light or dark (set by applyTheme). ACCENT fills buttons and switches; ACCENT_TEXT is the
    // same green for text and icons (lighter in dark mode, so it stays readable).
    var INK = 0
        private set
    var INK2 = 0
        private set
    var MUTED = 0
        private set
    var HINT = 0
        private set
    var LINE = 0
        private set
    var LINE2 = 0
        private set
    var FIELD_LINE = 0
        private set
    var PAPER = 0
        private set
    var PAGE = 0
        private set
    var CARD = 0
        private set
    var SEG = 0
        private set
    var ACCENT = 0
        private set
    var ACCENT_TEXT = 0
        private set
    var SOFT = 0
        private set
    var OUTLINE = 0
        private set
    var AMBER_BG = 0
        private set
    var AMBER_INK = 0
        private set
    var RED_BG = 0
        private set
    var RED_INK = 0
        private set
    var DANGER = 0
        private set
    var TRACK_OFF = 0
        private set
    var HANDLE = 0
        private set
    var CHIP_OFF = 0                  // a button that isn't picked (chips, and the band behind choices)
    var BAR = 0                       // the top bar (and the phone's status bar)
        private set
    var dark = false
        private set

    init { palette(false) }

    private fun c(v: Long) = v.toInt()
    private fun palette(night: Boolean) {
        dark = night
        if (!night) {
            INK = c(0xFF1C2B2D); INK2 = c(0xFF3E4B49); MUTED = c(0xFF5B6B69); HINT = c(0xFF8A9491)
            LINE = c(0xFFE7E3DB); LINE2 = c(0xFFEFECE5); FIELD_LINE = c(0xFFD5D1C8)
            PAPER = c(0xFFF5F3EE); PAGE = c(0xFFF5F3EE); CARD = c(0xFFFFFFFF); SEG = c(0xFFEAE7E0)
            ACCENT = c(0xFF1F5F55); ACCENT_TEXT = c(0xFF1F5F55); SOFT = c(0xFFE1EEEA); OUTLINE = c(0xFFCFDDD8)
            AMBER_BG = c(0xFFFBEFD5); AMBER_INK = c(0xFF6B4700); RED_BG = c(0xFFF7E3DE); RED_INK = c(0xFF8E3322)
            DANGER = c(0xFFA33A2A); TRACK_OFF = c(0xFFBFBAB0); HANDLE = c(0xFFCFCBC2); BAR = c(0xFF1F3A3D); CHIP_OFF = c(0xFFE3DFD6)
        } else {
            // Dark: softer than near-black, so text and edges are easier to read.
            INK = c(0xFFECF2F0); INK2 = c(0xFFD2DDDA); MUTED = c(0xFFAEBFBB); HINT = c(0xFF8EA19D)
            LINE = c(0xFF3D4E4F); LINE2 = c(0xFF364748); FIELD_LINE = c(0xFF4A5D5E)
            PAPER = c(0xFF273536); PAGE = c(0xFF1F2B2C); CARD = c(0xFF304041); SEG = c(0xFF364748)
            ACCENT = c(0xFF3D8F7E); ACCENT_TEXT = c(0xFF9BE3C2); SOFT = c(0xFF2C4A44); OUTLINE = c(0xFF4A5D5E)
            AMBER_BG = c(0xFF4A3B20); AMBER_INK = c(0xFFF5D08A); RED_BG = c(0xFF4A2C28); RED_INK = c(0xFFF7B4A7)
            DANGER = c(0xFFC4553F); TRACK_OFF = c(0xFF56696A); HANDLE = c(0xFF56696A); BAR = c(0xFF1A3134); CHIP_OFF = c(0xFF425455)
        }
    }

    /**
     * "Use my phone's colours" (Android 12 and newer): the phone's own palette, taken from its wallpaper,
     * in place of the app's greens and greys. Warnings (amber, red) keep their meaning.
     */
    private fun phonePalette(ctx: Context, night: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        fun k(id: Int) = ctx.getColor(id)
        val R = android.R.color::class.java
        fun sys(name: String): Int = k(R.getField(name).getInt(null))
        if (!night) {
            ACCENT = sys("system_accent1_600"); ACCENT_TEXT = sys("system_accent1_700"); SOFT = sys("system_accent1_100")
            OUTLINE = sys("system_accent1_200"); PAPER = sys("system_neutral1_50"); PAGE = sys("system_neutral1_10")
            SEG = sys("system_neutral2_100"); LINE = sys("system_neutral2_100"); LINE2 = sys("system_neutral2_50")
            INK = sys("system_neutral1_900"); INK2 = sys("system_neutral2_800"); MUTED = sys("system_neutral2_600")
            TRACK_OFF = sys("system_neutral2_300"); HANDLE = sys("system_neutral2_300"); BAR = sys("system_accent1_800"); CHIP_OFF = sys("system_neutral2_200")
        } else {
            ACCENT = sys("system_accent1_500"); ACCENT_TEXT = sys("system_accent1_200"); SOFT = sys("system_accent1_800")
            OUTLINE = sys("system_neutral2_600"); PAPER = sys("system_neutral1_800"); PAGE = sys("system_neutral1_900")
            CARD = sys("system_neutral1_700"); SEG = sys("system_neutral2_700"); LINE = sys("system_neutral2_700")
            LINE2 = sys("system_neutral2_800"); INK = sys("system_neutral1_50"); INK2 = sys("system_neutral1_100")
            MUTED = sys("system_neutral2_200"); TRACK_OFF = sys("system_neutral2_500"); HANDLE = sys("system_neutral2_500")
            BAR = sys("system_accent1_900"); CHIP_OFF = sys("system_neutral2_600")
        }
    }

    // ---------- light or dark ----------
    // "system" (the phone's own setting, the default), "light" or "dark". Chosen in the ⋮ menu → Appearance.
    private const val LOOK = "look"
    fun choice(ctx: Context): String = ctx.getSharedPreferences(LOOK, Context.MODE_PRIVATE).getString("theme", "system") ?: "system"
    fun setChoice(ctx: Context, v: String) = ctx.getSharedPreferences(LOOK, Context.MODE_PRIVATE).edit().putString("theme", v).apply()
    /** Should the app be dark now? ([config] after the phone's setting changed.) */
    fun wantsDark(ctx: Context, config: android.content.res.Configuration = ctx.resources.configuration): Boolean = when (choice(ctx)) {
        "light" -> false
        "dark" -> true
        else -> (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    /** "Use my phone's colours" (only offered on Android 12 and newer). */
    val phoneColoursAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    fun usePhoneColours(ctx: Context) = phoneColoursAvailable &&
        ctx.getSharedPreferences(LOOK, Context.MODE_PRIVATE).getBoolean("phoneColours", false)
    fun setPhoneColours(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(LOOK, Context.MODE_PRIVATE).edit().putBoolean("phoneColours", on).apply()

    /** Picks the light or dark colours (and the phone's own, if chosen). Call before building any view. */
    fun applyTheme(ctx: Context) {
        val night = wantsDark(ctx)
        palette(night)
        if (usePhoneColours(ctx)) runCatching { phonePalette(ctx, night) }   // if anything's missing: the app's colours
    }

    /** The colours as "#RRGGBB", for pages the app shows (home page, blocked page). */
    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    @Volatile private var body: Typeface = Typeface.SANS_SERIF
    @Volatile private var bold: Typeface = Typeface.DEFAULT_BOLD
    @Volatile private var display: Typeface = Typeface.DEFAULT_BOLD
    private var loaded = false

    /** Loads the fonts once (from assets/fonts; the phone's own font if they aren't there). */
    @Synchronized fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        fun load(file: String, weight: Int, extra: String = ""): Typeface? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Typeface.Builder(ctx.assets, "fonts/$file").setFontVariationSettings("'wght' $weight$extra").build()
            } else {
                Typeface.createFromAsset(ctx.assets, "fonts/$file")   // older phones: the font's default style
            }
        }.getOrNull()
        load("Figtree.ttf", 400)?.let { body = it }
        load("Figtree.ttf", 700)?.let { bold = it }
        load("BricolageGrotesque.ttf", 700, ", 'opsz' 28")?.let { display = it }
    }

    /** The fonts, for views outside dialogs (e.g. the top bar). */
    val bodyFace: Typeface get() = body
    val boldFace: Typeface get() = bold
    val displayFace: Typeface get() = display

    fun dp(ctx: Context, v: Number) = (v.toFloat() * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun text(ctx: Context, s: CharSequence, sp: Float = 15f, color: Int = INK, weight: String = "body"): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = when (weight) { "bold" -> Ui.bold; "display" -> Ui.display; else -> Ui.body }
            setLineSpacing(0f, 1.15f)
        }

    fun label(ctx: Context, s: String): TextView = text(ctx, s, 13.5f, INK2, "bold")

    /** A white, rounded text box. */
    fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(INK)
            setHintTextColor(HINT)
            typeface = body
            background = rounded(CARD, dp(ctx, 12).toFloat(), FIELD_LINE, dp(ctx, 1))
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            minHeight = dp(ctx, 48)
        }

    /** A label with a small grey tag next to it ("Open  [This phone's default: photos blocked]"). */
    fun labelWithTag(ctx: Context, s: String, tag: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(ctx, s))
        addView(text(ctx, tag, 11.5f, MUTED).apply {
            background = rounded(SEG, dp(ctx, 8).toFloat())
            setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
    }

    /**
     * One kind (Photos, Videos, Sound) as "Open | Blocked": the chosen one filled (green, or red for Blocked), with an
     * optional line under it ([note]). [onChange] gets true for Blocked.
     */
    class OpenBlocked(ctx: Context, title: String, blocked: Boolean, private val onChange: (Boolean) -> Unit) {
        var blocked = blocked
            private set
        val note: TextView = Ui.text(ctx, "", 12f, Ui.MUTED).apply { visibility = View.GONE }
        private val open = segButton(ctx, "Open")
        private val block = segButton(ctx, "Blocked")
        val view: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(Ui.text(ctx, title, 15f, Ui.INK, "bold"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val seg = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.rounded(Ui.CHIP_OFF, Ui.dp(ctx, 12).toFloat())
                setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3))
                addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 40)))
                addView(block, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 40)))
            }
            top.addView(seg)
            addView(top)
            addView(note)
        }
        init {
            open.setOnClickListener { pick(false) }
            block.setOnClickListener { pick(true) }
            paint()
        }
        private fun pick(b: Boolean) { if (b == blocked) return; blocked = b; paint(); onChange(b) }
        private fun paint() {
            val r = Ui.dp(open.context, 9).toFloat()
            open.background = if (!blocked) Ui.rounded(Ui.ACCENT, r) else ColorDrawable(Color.TRANSPARENT)
            open.setTextColor(if (!blocked) Color.WHITE else Ui.INK2)
            block.background = if (blocked) Ui.rounded(Ui.DANGER, r) else ColorDrawable(Color.TRANSPARENT)
            block.setTextColor(if (blocked) Color.WHITE else Ui.INK2)
            open.contentDescription = "Open" + if (!blocked) ", chosen" else ""
            block.contentDescription = "Blocked" + if (blocked) ", chosen" else ""
        }
        private fun segButton(ctx: Context, label: String) = Button(ctx).apply {
            text = label; isAllCaps = false; typeface = Ui.bold; stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = Ui.dp(ctx, 72); minimumWidth = Ui.dp(ctx, 72)
            minHeight = Ui.dp(ctx, 40); minimumHeight = Ui.dp(ctx, 40)
            setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0)
        }
    }

    /** A box of text: "warn" (amber), "red", or "info" (neutral). */
    fun box(ctx: Context, s: CharSequence, kind: String = "info"): TextView =
        text(ctx, s, 14f, when (kind) { "red" -> RED_INK; "warn" -> AMBER_INK; else -> INK2 }).apply {
            background = rounded(when (kind) { "red" -> RED_BG; "warn" -> AMBER_BG; else -> SEG }, dp(ctx, 14).toFloat())
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

    /** A white row with a title, an optional line under it, and a switch. Returns the row and its switch. */
    fun switchRow(ctx: Context, title: String, sub: String? = null, checked: Boolean = false): Pair<LinearLayout, Switch> {
        val sw = Switch(ctx).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            // Drawn by the app, so it's always clear. On: a solid green track with a big white knob. Off: an outlined
            // track (a clear dark outline) with a smaller dark knob, so it stands out even on a white card.
            val on = intArrayOf(android.R.attr.state_checked)
            // Sized so the ball always stays inside the track: the track is exactly two balls wide (48dp), so Android
            // never widens the switch past the track, and the ball moves half the track's width.
            trackDrawable = android.graphics.drawable.StateListDrawable().apply {
                // On: outlined too (a deeper shade of the accent; lighter in dark mode), so it stands out even
                // when the accent is pale (e.g. with the phone's own colours).
                addState(on, GradientDrawable().apply {
                    setColor(ACCENT); setStroke(dp(ctx, 2), outlineOf(ACCENT)); cornerRadius = dp(ctx, 14).toFloat(); setSize(dp(ctx, 48), dp(ctx, 28))
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(SEG); setStroke(dp(ctx, 2), MUTED); cornerRadius = dp(ctx, 14).toFloat(); setSize(dp(ctx, 48), dp(ctx, 28))
                })
            }
            thumbDrawable = android.graphics.drawable.StateListDrawable().apply {
                // On: a white 20dp ball in a 24×28 slot (2dp from the track's end).
                addState(on, android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(ctx, 1), outlineOf(ACCENT)); setSize(dp(ctx, 20), dp(ctx, 20))
                }, dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 4)))
                // Off: a smaller dark 14dp ball, the outline's colour.
                addState(intArrayOf(), android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(MUTED); setSize(dp(ctx, 14), dp(ctx, 14))
                }, dp(ctx, 5), dp(ctx, 7), dp(ctx, 5), dp(ctx, 7)))
            }
            trackTintList = null
            thumbTintList = null
            switchMinWidth = dp(ctx, 48)
            showText = false
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, dp(ctx, 14).toFloat(), LINE, dp(ctx, 1))
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            minimumHeight = dp(ctx, 52)
            val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            words.addView(text(ctx, title, 15f, INK, "bold"))
            if (sub != null) words.addView(text(ctx, sub, 12.5f, MUTED))
            addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }

    /**
     * Segmented buttons ("Just this page | Whole site"). Side by side, or stacked when [vertical].
     * Calls [onChange] with the chosen index; [select] changes it from code.
     */
    class Segmented(ctx: Context, options: List<String>, selected: Int, vertical: Boolean, private val onChange: (Int) -> Unit) {
        val view = LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.CHIP_OFF, Ui.dp(ctx, 13).toFloat())     // (darker than the picked one, so it stands out)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        }
        private val buttons: List<Button> = options.mapIndexed { i, label ->
            Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Ui.bold
                minHeight = Ui.dp(ctx, 40); minimumHeight = Ui.dp(ctx, 40)
                stateListAnimator = null
                setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
                setOnClickListener { select(i) }
            }
        }
        var index = selected
            private set

        init {
            buttons.forEach { b ->
                val lp = if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 40))
                    else LinearLayout.LayoutParams(0, Ui.dp(ctx, 42), 1f)
                view.addView(b, lp)
            }
            paint()
        }

        fun select(i: Int) {
            index = i
            paint()
            onChange(i)
        }

        private fun paint() {
            buttons.forEachIndexed { i, b -> paintButton(i, b) }
        }

        private fun paintButton(i: Int, b: Button) {
            val on = i == index
            b.background = if (on) Ui.rounded(Ui.CARD, Ui.dp(b.context, 10).toFloat()) else ColorDrawable(Color.TRANSPARENT)
            b.setTextColor(if (on) Ui.ACCENT_TEXT else Ui.INK2)
            b.elevation = if (on) Ui.dp(b.context, 1).toFloat() else 0f
        }
    }

    /**
     * A row of on/off chips ("Photos · Videos · Sound"): side by side, or stacked when [vertical].
     * [selected] holds the indexes that are on.
     */
    class Chips(ctx: Context, labels: List<String>, icons: List<Int>, on: Set<Int>, vertical: Boolean) {
        val selected: MutableSet<Int> = on.toMutableSet()
        val view: LinearLayout = LinearLayout(ctx).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        private val chips: List<Button> = labels.mapIndexed { i, label ->
            Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Ui.bold
                stateListAnimator = null
                minHeight = Ui.dp(ctx, 44); minimumHeight = Ui.dp(ctx, 44)
                setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0)
                compoundDrawablePadding = Ui.dp(ctx, 6)
                val d = ctx.getDrawable(icons[i])?.mutate()
                d?.setBounds(0, 0, Ui.dp(ctx, 18), Ui.dp(ctx, 18))
                setCompoundDrawables(d, null, null, null)
                setOnClickListener { if (!selected.remove(i)) selected.add(i); paint() }
            }
        }

        init {
            chips.forEachIndexed { i, b ->
                val lp = if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 44))
                    else LinearLayout.LayoutParams(0, Ui.dp(ctx, 44), 1f)
                if (i > 0) { if (vertical) lp.topMargin = Ui.dp(ctx, 6) else lp.marginStart = Ui.dp(ctx, 8) }
                view.addView(b, lp)
            }
            paint()
        }

        private fun paint() {
            chips.forEachIndexed { i, b -> paintChip(i, b) }
        }

        private fun paintChip(i: Int, b: Button) {
            val on = i in selected
            val r = Ui.dp(b.context, 12).toFloat()
            // Not picked: grey, no border (white looked as if it were picked).
            b.background = if (on) Ui.rounded(Ui.ACCENT, r) else Ui.rounded(Ui.CHIP_OFF, r)
            val c = if (on) Color.WHITE else Ui.INK2
            b.setTextColor(c)
            b.compoundDrawables[0]?.setTint(c)
        }
    }

    /** A round coloured badge with an icon (for titles and list rows). */
    fun badge(ctx: Context, icon: Int, bg: Int, fg: Int, sizeDp: Int = 44, radiusDp: Int = 14): ImageView = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(fg)
        background = Ui.rounded(bg, Ui.dp(ctx, radiusDp).toFloat())
        val p = Ui.dp(ctx, sizeDp / 4)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, sizeDp), Ui.dp(ctx, sizeDp))
    }

    enum class Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    /**
     * A dialog in the app's look: a bottom sheet (longer screens) or a centred card (short questions).
     * The content scrolls if it doesn't fit, and the buttons always stay on screen (even with the
     * keyboard up).
     */
    class AppDialog(private val activity: Activity, sheet: Boolean, cancelable: Boolean = true) {
        val dialog = Dialog(activity)
        private val ctx: Context = activity
        private val narrow = activity.resources.configuration.screenWidthDp in 1 until 360
        /** Add the dialog's content here. */
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val side = Ui.dp(ctx, if (narrow) 14 else 20)
            setPadding(side, Ui.dp(ctx, if (sheet) 6 else 22), side, Ui.dp(ctx, 12))
        }
        private val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = Ui.dp(ctx, if (narrow) 12 else 20)
            setPadding(side, Ui.dp(ctx, 12), side, Ui.dp(ctx, if (sheet) 18 else 18))
        }

        init {
            Ui.init(ctx)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val r = Ui.dp(ctx, if (narrow) 18 else 24).toFloat()
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Ui.PAPER)
                    cornerRadii = if (sheet) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
                }
            }
            val scroll = MaxHeightScrollView(ctx, reservedDp = if (sheet) (if (narrow) 84 else 120) else (if (narrow) 110 else 160))
            if (sheet) {
                // The handle at the top of a sheet, in a wider strip that can be dragged: down to close,
                // up to make the sheet (nearly) full screen.
                val grip = android.widget.FrameLayout(ctx)
                grip.addView(View(ctx).apply { background = Ui.rounded(Ui.HANDLE, Ui.dp(ctx, 3).toFloat()) },
                    android.widget.FrameLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 5), Gravity.CENTER))
                root.addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26)))
                var startY = 0f
                grip.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; true }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dy = e.rawY - startY
                            if (dy > 0) root.translationY = dy                         // follows the finger down
                            else if (dy < -Ui.dp(ctx, 40) && !expanded) expand(root, scroll)  // pulled up: full screen
                            true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            val dy = e.rawY - startY
                            if (cancelable && dy > root.height * 0.25f) {
                                root.animate().translationY(root.height.toFloat()).setDuration(160).withEndAction { dismiss() }.start()
                            } else {
                                root.animate().translationY(0f).setDuration(160).start()
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            scroll.addView(content)
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (sheet) root.addView(View(ctx).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1)))
            root.addView(buttons)
            dialog.setContentView(root)
            dialog.setCancelable(cancelable)
            dialog.setCanceledOnTouchOutside(cancelable)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.48f)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                if (sheet) {
                    setGravity(Gravity.BOTTOM)
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                } else {
                    setGravity(Gravity.CENTER)
                    val w = ctx.resources.displayMetrics.widthPixels - Ui.dp(ctx, if (narrow) 20 else 44)
                    setLayout(minOf(w, Ui.dp(ctx, 440)), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
        }

        /** Pulled up: the sheet fills (nearly) the whole screen, and its content gets all of that room. */
        private var expanded = false
        private fun expand(root: LinearLayout, scroll: MaxHeightScrollView) {
            expanded = true
            scroll.reservedDp = if (narrow) 70 else 96
            root.minimumHeight = (ctx.resources.displayMetrics.heightPixels * 0.9f).toInt()
            scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
            root.requestLayout()
        }

        /** The heading, with an optional line under it and an optional icon badge beside it. */
        fun title(t: String, sub: String? = null, icon: Int = 0, iconBg: Int = Ui.SOFT, iconFg: Int = Ui.ACCENT_TEXT): AppDialog {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != 0) row.addView(Ui.badge(ctx, icon, iconBg, iconFg).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(ctx, 12)
            })
            val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            words.addView(Ui.text(ctx, t, if (narrow) 19f else 22f, Ui.INK, "display"))
            if (sub != null) words.addView(Ui.text(ctx, sub, 14f, Ui.MUTED))
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            add(row)
            titleView = row
            return this
        }

        /** The heading, once [title] has added it (e.g. to notice taps on it). */
        var titleView: View? = null
            private set

        /** Adds a view to the content, with the usual space above it. */
        fun add(v: View, gapDp: Int = 12): AppDialog {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (content.childCount > 0) lp.topMargin = Ui.dp(ctx, gapDp)
            content.addView(v, lp)
            return this
        }

        /** Adds a button at the bottom. PRIMARY and DANGER fill the remaining width. */
        fun button(label: String, kind: Kind, onClick: (AppDialog) -> Unit): Button {
            val b = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (narrow) 15f else 16f)
                typeface = Ui.bold
                stateListAnimator = null
                minHeight = Ui.dp(ctx, 48); minimumHeight = Ui.dp(ctx, 48)
                setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), 0)
                val r = Ui.dp(ctx, 14).toFloat()
                when (kind) {
                    Kind.PRIMARY -> { background = Ui.rounded(Ui.ACCENT, r); setTextColor(Color.WHITE) }
                    Kind.DANGER -> { background = Ui.rounded(Ui.DANGER, r); setTextColor(Color.WHITE) }
                    Kind.SECONDARY -> { background = Ui.rounded(Ui.CARD, r, Ui.OUTLINE, Ui.dp(ctx, 1)); setTextColor(Ui.ACCENT_TEXT) }
                    Kind.GHOST -> { background = ColorDrawable(Color.TRANSPARENT); setTextColor(Ui.MUTED) }
                }
                setOnClickListener { onClick(this@AppDialog) }
            }
            val fill = kind == Kind.PRIMARY || kind == Kind.DANGER
            val lp = if (fill) LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 50))
            if (buttons.childCount > 0) lp.marginStart = Ui.dp(ctx, 10)
            buttons.addView(b, lp)
            return b
        }

        private var dismissAction: (() -> Unit)? = null
        fun onDismiss(f: () -> Unit): AppDialog { dismissAction = f; return this }
        val isShowing get() = dialog.isShowing

        fun show(): AppDialog {
            if (activity.isFinishing) return this
            dialog.setOnDismissListener {
                if (showing === this) showing = null
                dismissAction?.invoke()
            }
            dialog.show()
            showing = this
            return this
        }
        fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

        /**
         * A short message inside this dialog, above its buttons (for a few seconds). Messages go here while a
         * dialog is open, since one at the bottom of the screen would be hidden behind it.
         */
        private var noteView: TextView? = null
        private var noteBaseReserve: Int? = null                 // the scrolling part's room before a ribbon showed
        fun note(text: String) {
            val parent = buttons.parent as? LinearLayout ?: return
            noteView?.let { parent.removeView(it) }
            // The scrolling part gives up the ribbon's height while it shows (it was sized to leave room for the
            // buttons only, so the ribbon pushed them off the screen).
            val scroll = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<MaxHeightScrollView>().firstOrNull()
            val base = noteBaseReserve ?: scroll?.reservedDp?.also { noteBaseReserve = it }
            val v = Ui.text(ctx, text, 14f, Ui.AMBER_INK, "bold").apply {
                background = Ui.rounded(Ui.AMBER_BG, Ui.dp(ctx, 12).toFloat())
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
            }
            parent.addView(v, parent.indexOfChild(buttons), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Ui.dp(ctx, 16); setMargins(m, Ui.dp(ctx, 6), m, 0)
            })
            v.announceForAccessibility(text)
            noteView = v
            if (scroll != null && base != null) {
                val m = Ui.dp(ctx, 16)
                v.measure(View.MeasureSpec.makeMeasureSpec(maxOf(parent.width - 2 * m, 1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                scroll.reservedDp = base + (v.measuredHeight / ctx.resources.displayMetrics.density).toInt() + 8
                scroll.requestLayout()
            }
            v.postDelayed({
                if (noteView === v) {
                    parent.removeView(v); noteView = null
                    if (scroll != null && base != null) { scroll.reservedDp = base; scroll.requestLayout() }
                }
            }, 4500)
        }

        companion object {
            /** The app's dialog that's open now, if any. */
            var showing: AppDialog? = null
                private set
        }
    }

    /** A switch's outline when on: the accent, deeper (light mode) or lighter (dark mode). */
    fun outlineOf(c: Int): Int {
        val f = if (dark) 0.6f else 0.55f                       // clearly deeper (or lighter): a visible ring
        val to = if (dark) 255 else 0
        fun mix(x: Int) = (x + (to - x) * f).toInt()
        return Color.rgb(mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }

    /** The time wheels in the app's look: numbers in its colours and fonts, no grey lines. */
    fun styleWheel(np: android.widget.NumberPicker) {
        np.background = null
        if (Build.VERSION.SDK_INT >= 29) {
            np.textColor = MUTED
            np.selectionDividerHeight = 0
        }
        for (i in 0 until np.childCount) {
            (np.getChildAt(i) as? android.widget.EditText)?.apply {
                setTextColor(ACCENT_TEXT)
                typeface = Ui.bold
                background = null
            }
        }
    }
}
