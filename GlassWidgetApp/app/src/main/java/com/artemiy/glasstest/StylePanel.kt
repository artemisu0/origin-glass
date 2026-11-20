package com.artemiy.glasstest

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v0.23: панель стиля стекла часов: режим (Прозрачное / Жидкое / Папки / Блюр) + номер стиля BlurMaterial
 * (◀/▶ или сетка всех номеров — как в Glass lab). Каждое нажатие сразу уходит в часы (GlassLook.applyAll).
 */
class StylePanel(private val c: Context, private val onClose: () -> Unit) {
    private val dens = c.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()

    private var mode = GlassLook.mode(c)
    private var style = GlassLook.style(c)

    private val modeChips = ArrayList<TextView>()
    private val fontChips = ArrayList<TextView>()
    private val styleCells = HashMap<Int, TextView>()
    private lateinit var styleLabel: TextView
    private lateinit var status: TextView
    private lateinit var gridScroll: ScrollView

    private companion object {
        const val ACCENT = 0xFF4C8DFF.toInt()
        const val CHIP = 0x24FFFFFF
        const val PANEL = 0xF01C1C1E.toInt()
    }

    val view: View = build()

    private fun build(): View {
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(PANEL)
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        // заголовок + сколько часов
        panel.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(c).apply {
                text = "Стекло часов"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            status = TextView(c).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()) }
            addView(status)
        })

        // режимы
        val modes = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        GlassLook.MODES.forEachIndexed { i, m ->
            val chip = chip(m.title, 14f).apply {
                tag = m.id
                setOnClickListener { mode = m.id; changed() }
                // с широким системным шрифтом «Прозрачное» не влезало — уменьшаем текст до ширины кнопки
                setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            modeChips.add(chip)
            modes.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        panel.addView(modes, lp(top = 12))

        // v0.29: шрифт — встроенные, свои (долгое нажатие — удалить), «＋ Свой шрифт» (TTF/OTF из файлов)
        val fonts = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        GlassFonts.all(c).forEach { f ->
            val chip = chip(f.title, 14f).apply {
                tag = f.key
                setPadding(dp(14), 0, dp(14), 0)
                GlassFonts.typeface(c, f.key)?.let { typeface = it }   // название — тем же шрифтом
                setOnClickListener { GlassFonts.select(c, f.key); changed() }
                if (f.user) setOnLongClickListener {
                    GlassFonts.delete(c, f.key)
                    android.widget.Toast.makeText(c, "Шрифт «${f.title}» удалён", android.widget.Toast.LENGTH_SHORT).show()
                    (parent as? ViewGroup)?.removeView(this); changed(); true
                }
            }
            fontChips.add(chip)
            fonts.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(6) })
        }
        fonts.addView(chip("＋ Свой шрифт", 14f).apply {
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                // выбор файла — через активити (из плавающего окна результат не получить); панель спрячет она сама
                c.startActivity(android.content.Intent(c, FontPickActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        val fontScroll = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(fonts)
        }
        panel.addView(fontScroll, lp(top = 8))
        // выбранный шрифт (свои — в конце строки) сразу на виду
        fontScroll.post {
            val sel = GlassFonts.selected(c)
            fontChips.firstOrNull { it.tag == sel }?.let { fontScroll.scrollTo((it.left - dp(16)).coerceAtLeast(0), 0) }
        }

        // ◀ стиль ▶
        val stepper = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepper.addView(chip("◀", 20f).apply { setOnClickListener { step(-1) } }, LinearLayout.LayoutParams(dp(72), dp(48)))
        styleLabel = TextView(c).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        stepper.addView(styleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepper.addView(chip("▶", 20f).apply { setOnClickListener { step(1) } }, LinearLayout.LayoutParams(dp(72), dp(48)))
        panel.addView(stepper, lp(top = 10))

        // сетка всех номеров
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val cols = 7
        GlassLook.STYLES.toList().chunked(cols).forEach { rowStyles ->
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until cols) {
                val s = rowStyles.getOrNull(col)
                val cell: View = if (s == null) View(c) else chip(s.toString(), 13f).apply {
                    setOnClickListener { style = s; changed() }
                    styleCells[s] = this
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (col > 0) marginStart = dp(5) })
            }
            grid.addView(row, lp(top = 5))
        }
        gridScroll = ScrollView(c).apply { addView(grid) }
        panel.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })

        // сброс / готово
        val bottom = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(chip("Сброс", 14f).apply {
            setOnClickListener { mode = GlassLook.DEFAULT_MODE; style = GlassLook.DEFAULT_STYLE; changed(scroll = true) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottom.addView(chip("Готово", 14f).apply {
            setOnClickListener { onClose() }
            (background as GradientDrawable).setColor(ACCENT)
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        panel.addView(bottom, lp(top = 10))

        changed(scroll = true)
        return panel
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun chip(text: String, size: Float) = TextView(c).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 1
        background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(12).toFloat() }
    }

    private fun step(d: Int) {
        val all = GlassLook.STYLES
        val i = all.indexOf(style).coerceAtLeast(0)
        style = all[(i + d + all.size) % all.size]
        changed(scroll = true)
    }

    /** Сохранить, разослать в часы, обновить подсветку. */
    private fun changed(scroll: Boolean = false) {
        GlassLook.save(c, mode, style)
        val n = GlassLook.applyAll(c)
        modeChips.forEach { (it.background as GradientDrawable).setColor(if (it.tag == mode) ACCENT else CHIP) }
        val font = GlassFonts.selected(c)
        fontChips.forEach { (it.background as GradientDrawable).setColor(if (it.tag == font) ACCENT else CHIP) }
        styleCells.forEach { (s, v) -> (v.background as GradientDrawable).setColor(if (s == style) ACCENT else CHIP) }
        val m = GlassLook.MODES.first { it.id == mode }
        styleLabel.text = "style $style\n${m.hint}"
        status.text = if (n > 0) "часов: $n" else "часы не добавлены"
        if (scroll) styleCells[style]?.let { cell ->
            gridScroll.post { gridScroll.smoothScrollTo(0, ((cell.parent as View).top - dp(40)).coerceAtLeast(0)) }
        }
    }
}

/**
 * v0.23: панель — плавающее окно поверх РАБОЧЕГО СТОЛА (TYPE_APPLICATION_OVERLAY). Лаунчер vivo прячет рабочий стол,
 * пока поверх него открыта любая активити (даже прозрачная — проверено: чёрный экран над панелью), а под плавающим
 * окном лаунчер остаётся активным: часы видны, стиль меняется вживую, выше панели можно листать.
 * Нужно разрешение «Поверх других приложений» (Settings.canDrawOverlays).
 */
object PanelOverlay {
    private var shown: View? = null

    fun isShown() = shown != null

    fun show(ctx: Context) {
        if (shown != null) return
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val panel = StylePanel(app) { hide(app) }.view
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (app.resources.displayMetrics.heightPixels * 0.46f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // без фокуса: касания мимо панели уходят лаунчеру (можно листать страницы)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            title = "GlassStylePanel"
        }
        wm.addView(panel, lp)
        shown = panel
    }

    fun hide(ctx: Context) {
        val v = shown ?: return
        shown = null
        try { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }
}
