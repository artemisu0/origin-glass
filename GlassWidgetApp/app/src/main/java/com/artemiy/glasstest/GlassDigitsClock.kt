package com.artemiy.glasstest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import java.util.Calendar

/**
 * v0.20: часы из стеклянных цифр — КАЖДАЯ цифра отдельным view со своим стеклом.
 *
 * Зачем: HWUI рисует стекло по содержимому, только пока ЛЕВЫЙ край view внутри экрана (x ≥ 0; за правый край
 * можно сколько угодно). Если левый край уехал за экран хоть на пиксель — стекло выключается целиком и рисуется
 * просто текст: при листании влево все часы разом белели. Замерено удержанием пальца (v0.19: x 13 → стекло,
 * x −7 → белые цифры). С отдельными цифрами отваливается только та, что реально уезжает за левый край,
 * а её мы в этот момент просто не рисуем (будто уехала), вместо белой.
 *
 * Время считаем сами (тик в начале каждой минуты), цифры — обычные TextView в процессе хоста (их создаёт код,
 * а не RemoteViews, поэтому аннотация нужна только этому контейнеру).
 * android:tag="2" — две строки (часы над минутами, для 2×2), иначе одна строка "HH:mm".
 */
@RemoteViews.RemoteView
class GlassDigitsClock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    /** tag: "1"/"2" — по ячейке на символ (1 или 2 строки); "L1"/"L2" — одно стеклянное view на строку. */
    private var twoRows = tag?.toString()?.endsWith("2") == true
    private val perLine = tag?.toString()?.startsWith("L") == true
    /** "P1"/"P2": по куску на группу (часы / двоеточие / минуты) — средний размер стеклянных view. */
    private val perPiece = tag?.toString()?.startsWith("P") == true
    /** v0.30: "PA"/"LA" — одна или две строки выбираются сами: что даёт цифры крупнее при текущем размере виджета
     *  (виджет можно растягивать, есть 4×3). */
    private val autoRows = tag?.toString()?.endsWith("A") == true
    /** v0.29: шрифт меняется из приложения (ключ "font=…" в contentDescription, см. GlassFonts). */
    private var typeface: Typeface? = try { resources.getFont(R.font.dela_gothic_one) } catch (_: Throwable) { null }
    private var fontKey: String = GlassFonts.DEFAULT
    private val rows = ArrayList<LinearLayout>()
    private val cells = ArrayList<GlassDigit>()
    private var text = ""
    private var textPx = 0f
    /** Фиксированные ширины ячеек: цифра (самая широкая из 0–9) и двоеточие. Если ширина view цифры меняется
     *  при смене минуты («8» шире «1»), стекло пересобирается в неудачный момент и рассыпается на полосы. */
    private var digitW = 0
    private var colonW = 0

    private fun applyCellWidth(cell: GlassDigit, ch: Char) {
        if (perLine || perPiece) {
            // ширина постоянная (по самой широкой цифре): смена минуты не меняет размер стеклянного view
            val line = cell.text?.toString() ?: ""
            val w = if (digitW > 0) line.sumOf { if (it == ':') colonW else digitW } + padL + padR else LayoutParams.WRAP_CONTENT
            val lp = cell.layoutParams as? LinearLayout.LayoutParams ?: return
            if (lp.width != w) { lp.width = w; cell.layoutParams = lp }
            return
        }
        val w = (if (ch == ':') colonW else digitW).let { if (it > 0) it + padL + padR else it }
        if (w <= 0) return
        val lp = cell.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.width != w) { lp.width = w; cell.layoutParams = lp }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        // две строки создаём всегда: при одной вторая скрыта (v0.30 — число строк может меняться на лету)
        repeat(2) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            rows.add(row)
        }
        rows[1].visibility = if (twoRows) VISIBLE else GONE
        updateTime()
    }

    /** v0.30: сменить число строк — ячейки перестроит updateTime (переносит/убирает лишние). */
    private fun setTwoRows(two: Boolean) {
        if (two == twoRows) return
        twoRows = two
        rows[1].visibility = if (two) VISIBLE else GONE
        if (!two) (rows[1].layoutParams as? LayoutParams)?.topMargin = 0
        textPx = 0f
        text = ""
        Diag.report(context, "[rows] ${if (two) 2 else 1} строки при ${width}x$height")
        updateTime()
    }

    // --- время ---
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            val now = System.currentTimeMillis()
            postDelayed(this, 60_000L - now % 60_000L + 30L)
        }
    }

    private fun timeLines(two: Boolean): List<String> {
        val c = Calendar.getInstance()
        val h24 = try { DateFormat.is24HourFormat(context) } catch (_: Throwable) { true }
        val h = if (h24) "%02d".format(c.get(Calendar.HOUR_OF_DAY))
                else (c.get(Calendar.HOUR).let { if (it == 0) 12 else it }).toString()
        val m = "%02d".format(c.get(Calendar.MINUTE))
        return if (two) listOf(h, m) else listOf("$h:$m")
    }

    private fun timeText(): String = timeLines(twoRows).joinToString("\n")

    private fun updateTime() {
        try {
            val t = timeText()
            if (t == text) return
            text = t
            val parts = if (twoRows) t.split('\n') else listOf(t)
            var idx = 0
            parts.forEachIndexed { r, line ->
                val row = rows[r]
                val pieces = when {
                    perLine -> listOf(line)
                    perPiece -> Regex("[^:]+|:").findAll(line).map { it.value }.toList()
                    else -> line.map { it.toString() }
                }
                pieces.forEachIndexed { i, piece ->
                    val ch = piece.first()
                    val cell: GlassDigit
                    if (idx < cells.size) {
                        cell = cells[idx]
                        if (cell.parent !== row || row.indexOfChild(cell) != i) {
                            (cell.parent as? LinearLayout)?.removeView(cell)
                            row.addView(cell, i)
                        }
                    } else {
                        cell = GlassDigit(context, typeface)
                        if (textPx > 0f) cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                        cell.setPadding(padL, padT, padR, padB)
                        row.addView(cell, i)
                        cells.add(cell)
                    }
                    if (cell.text?.toString() != piece) cell.text = piece
                    applyCellWidth(cell, ch)
                    idx++
                }
            }
            // лишние ячейки (например, 12-часовой формат "9:05") убираем
            while (cells.size > idx) {
                val c = cells.removeAt(cells.size - 1)
                (c.parent as? LinearLayout)?.removeView(c)
            }
            fitTextSize(width, height)
        } catch (_: Throwable) {
        }
    }

    // --- размер шрифта под виджет ---
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * v0.30: насколько РЕАЛЬНЫЙ контур цифр/двоеточия вылезает за метрики шрифта (при размере 100): сверху за ascent,
     * снизу за descent, слева/справа за ширину знака. У части шрифтов (Cinematografica) цифры выше заявленной высоты
     * строки — с includeFontPadding=false верх срезала граница view («обрезаны сверху»).
     */
    private val ink = android.graphics.Rect()
    private val over = FloatArray(4)   // top, bottom, left, right — в px при textSize 100

    private fun measureOverflow() {
        measurePaint.typeface = typeface
        measurePaint.textSize = 100f
        val fm = measurePaint.fontMetrics
        var t = 0f; var b = 0f; var l = 0f; var r = 0f
        for (ch in "0123456789:") {
            val s = ch.toString()
            measurePaint.getTextBounds(s, 0, 1, ink)
            t = maxOf(t, fm.ascent - ink.top)              // ink.top выше ascent → > 0
            b = maxOf(b, ink.bottom - fm.descent)
            l = maxOf(l, -ink.left.toFloat())
            r = maxOf(r, ink.right - measurePaint.measureText(s))
        }
        over[0] = t; over[1] = b; over[2] = l; over[3] = r
    }

    /** Размер шрифта (px), при котором время в 1 или 2 строки влезает в w×h. */
    private fun pxFor(two: Boolean, w: Int, h: Int): Float {
        measureOverflow()
        val lines = timeLines(two)
        val dW = "0123456789".maxOf { measurePaint.measureText(it.toString()) }
        val cW = measurePaint.measureText(":")
        val pieces = if (perPiece) 3 else if (perLine) 1 else 5
        val widest = lines.maxOf { l -> l.sumOf { (if (it == ':') cW else dW).toDouble() }.toFloat() } +
            (over[2] + over[3]) * pieces
        val fm = measurePaint.fontMetrics
        val lineH = (fm.descent - fm.ascent + over[0] + over[1]) * (if (two) 0.86f else 1f)
        val byW = (w * 0.94f) / widest * 100f
        val byH = (h * 0.96f) / (lineH * lines.size) * 100f
        return minOf(byW, byH)
    }

    /** Отступы ячеек (px) под вылезающий контур при текущем размере. */
    private var padT = 0; private var padB = 0; private var padL = 0; private var padR = 0

    private fun fitTextSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // v0.30: другой вариант строк крупнее хотя бы на 5% — переключаемся (запас, чтобы не дёргалось на границе)
        if (autoRows && pxFor(!twoRows, w, h) > pxFor(twoRows, w, h) * 1.05f) { setTwoRows(!twoRows); return }
        val px = pxFor(twoRows, w, h)          // заодно measureOverflow() при 100
        measurePaint.textSize = 100f
        val fm = measurePaint.fontMetrics
        if (kotlin.math.abs(px - textPx) < 0.5f) return
        textPx = px
        val k = px / 100f
        padT = kotlin.math.ceil(over[0] * k).toInt().let { if (it > 0) it + 1 else 0 }
        padB = kotlin.math.ceil(over[1] * k).toInt().let { if (it > 0) it + 1 else 0 }
        padL = kotlin.math.ceil(over[2] * k).toInt().let { if (it > 0) it + 1 else 0 }
        padR = kotlin.math.ceil(over[3] * k).toInt().let { if (it > 0) it + 1 else 0 }
        measurePaint.textSize = px
        digitW = kotlin.math.ceil("0123456789".maxOf { measurePaint.measureText(it.toString()) }.toDouble()).toInt() + 2
        colonW = kotlin.math.ceil(measurePaint.measureText(":").toDouble()).toInt() + 2
        val chars = text.replace("\n", "")
        cells.forEachIndexed { i, c ->
            c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            c.setPadding(padL, padT, padR, padB)
            if (perLine || perPiece) applyCellWidth(c, '0') else chars.getOrNull(i)?.let { applyCellWidth(c, it) }
        }
        if (twoRows) {
            // строки ближе друг к другу, как у lineSpacingMultiplier 0.82
            val overlap = ((fm.descent - fm.ascent + over[0] + over[1]) * k * 0.14f).toInt()
            rows.getOrNull(1)?.let { (it.layoutParams as? LayoutParams)?.topMargin = -overlap }
        }
        post { requestLayout() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitTextSize(w, h)
    }

    // --- шрифт (v0.29) ---
    /** contentDescription ставит RemoteViews; переопределять setContentDescription нельзя (README v0.11) — опрашиваем. */
    private val fontCheck = object : Runnable {
        override fun run() {
            try { checkFont() } catch (_: Throwable) {}
            if (isAttachedToWindow) postDelayed(this, 500)
        }
    }

    private fun checkFont() {
        val key = GlassLook.parse(contentDescription)["font"] ?: GlassFonts.DEFAULT
        if (key == fontKey) return
        fontKey = key
        GlassFonts.cached(key)?.let { setClockTypeface(it); return }
        // свой файл грузится через провайдер приложения — не в главном потоке лаунчера
        val ctx = context
        GlassFonts.io.execute {
            val tf = GlassFonts.typeface(ctx, key)
            post {
                if (fontKey != key) return@post
                if (tf != null) setClockTypeface(tf)
                else Diag.report(ctx, "[font] '$key' не загрузился — остаётся прежний")
            }
        }
    }

    private fun setClockTypeface(tf: Typeface) {
        if (tf === typeface) return
        typeface = tf
        cells.forEach { it.changeTypeface(tf) }
        textPx = 0f                         // метрики другие — пересчитать размер и ширины ячеек
        fitTextSize(width, height)
        Diag.report(context, "[font] '$fontKey' применён")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
        removeCallbacks(fontCheck)
        post(fontCheck)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(fontCheck)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { removeCallbacks(tick); post(tick) }
    }
}

/**
 * Одна стеклянная цифра: непрозрачный белый текст (HWUI берёт форму стекла из альфы ≥ 0.5) + стекло вне пакета
 * лаунчера (GlassHelper: BATCH_NONE + setLiquidGlassEffect + рецепт clear). Если левый край цифры за экраном —
 * не рисуем её вовсе (стекло там всё равно не работает, иначе была бы белая цифра).
 */
@SuppressLint("ViewConstructor")
internal class GlassDigit(context: Context, tf: Typeface?) : TextView(context) {
    private companion object { const val CLAMP_MARGIN_DP = 3f }

    /** Часы-родитель: их contentDescription (ставит RemoteViews) — стиль "look=…" или конфиг опытов "recipe=…". */
    private fun clock(): View? {
        var p: Any? = parent
        while (p is View && p !is GlassDigitsClock) p = p.parent
        return p as? View
    }

    private val glass = GlassHelper(this, cornerRadiusDp = null, defaultCfg = { isLauncher ->
        // v0.20: clamp — view держится внутри экрана с отступом (иначе HWUI выключает стекло у края, проверено)
        val m = (CLAMP_MARGIN_DP * resources.displayMetrics.density).toInt()
        // v0.22: рецепт и номер стиля — из приложения (GlassLook, "look=…;st=…"), по умолчанию clear / 0
        GlassLook.cfg(clock()?.contentDescription, isLauncher, m)
    }, cfgSource = { clock() })

    init {
        typeface = tf
        setTextColor(0xFFFFFFFF.toInt())
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
    }

    /** v0.29: новый шрифт — форма стекла по содержимому меняется, переприменяем стекло. */
    fun changeTypeface(tf: Typeface) {
        typeface = tf
        glass.reapply("font")
    }

    private val loc = IntArray(2)
    private var hidden = false
    /** clamp: насколько сдвинут рисунок цифры внутри view (обратно сдвигу view). */
    private var contentShift = 0f

    private val posCheck = ViewTreeObserver.OnPreDrawListener {
        try { checkPos() } catch (_: Throwable) {}
        true
    }

    /** rightBySize: настоящая ширина из разметки и флаг «сейчас укорочено». */
    private var layoutW = 0
    private var cut = false

    private fun setCut(newW: Int?) {
        if (newW == null) {
            if (cut) { cut = false; right = left + layoutW; invalidate() }
            return
        }
        if (!cut) { cut = true; layoutW = width }
        val r = left + newW.coerceIn(1, layoutW)
        if (r != right) { right = r; invalidate() }
    }

    /** v0.21: [левый, правый край на экране без нашего сдвига, масштаб k] — см. ScreenSpan. */
    private val span = FloatArray(3)

    /** Поставить сдвиг view (локальные px), рисунок — обратно. Мелкий шум float не трогаем (иначе перерисовка каждый кадр). */
    private fun setShift(dx: Float): Boolean {
        if (dx == translationX || (dx != 0f && translationX != 0f && kotlin.math.abs(dx - translationX) < 0.5f)) return false
        translationX = dx; contentShift = -dx; invalidate()
        return true
    }

    private fun checkPos() {
        getLocationInWindow(loc)
        val cfg = glass.applied
        // v0.21: всё в ЭКРАННЫХ px. Виджет в лаунчере уменьшен (k ≈ 0.857), а width/translationX — локальные:
        // экранная ширина = k·width, сдвиг на экране = k·translationX.
        if (!ScreenSpan.of(this, span)) return
        val baseX = span[0]                         // левый край без нашего сдвига
        val baseR = span[1]                         // правый край без нашего сдвига
        val k = span[2]
        val screenW = rootView.width.toFloat()
        if (cfg?.clamp == true && cfg.rightBySize && screenW > 0f) {
            // левый край — сдвигом (работает), правый — укорачиванием view до края экрана с отступом
            val fullW = if (cut) layoutW else width
            val fullR = baseX + k * fullW
            val m = cfg.clampM.toFloat()
            val mr = (cfg.clampR ?: cfg.clampM).toFloat()
            val out = fullR <= m || baseX >= screenW - mr
            if (out != hidden) { hidden = out; invalidate() }
            if (out) return
            val over = fullR - (screenW - mr)
            if (over > 0f && baseX >= m) {
                if (translationX != 0f) { translationX = 0f; contentShift = 0f }
                setCut((fullW - over / k).toInt())
                return
            }
            setCut(null)
            setShift(if (baseX < m) (m - baseX) / k else 0f)
            return
        }
        if (cut) setCut(null)
        if (cfg != null && cfg.tx != 0) {   // опыт: постоянный сдвиг view
            val t = cfg.tx.toFloat()
            if (translationX != t) { translationX = t; invalidate() }
            return
        }
        if (cfg?.clamp == true && screenW > 0f) {
            // HWUI ломает стекло по содержимому, если view выходит за край экрана. Держим view внутри экрана,
            // а рисунок сдвигаем обратно — цифра на экране остаётся на своём месте (часть за краем всё равно не видна).
            val m = cfg.clampM.toFloat()
            val mr = (cfg.clampR ?: cfg.clampM).toFloat()
            val out = baseR <= m || baseX >= screenW - mr
            var dx = 0f                              // экранные px
            if (!out) {
                if (baseX < m) dx = m - baseX
                else if (cfg.clampRight && baseR > screenW - mr && baseR - baseX <= screenW - m - mr)
                    dx = (screenW - mr) - baseR
            }
            val old = translationX
            if (setShift(dx / k)) {
                if ((dx == 0f) != (old == 0f) || kotlin.math.abs(dx / k - old) > 40f)
                    Diag.report(context, "[clamp] '${text}' x=$baseX..$baseR k=$k screenW=$screenW dx=$dx")
            }
            if (out != hidden) { hidden = out; invalidate() }
            return
        }
        if (translationX != 0f) { translationX = 0f; contentShift = 0f; invalidate() }
        val off = cfg?.hide != false && (baseX < 0f || loc[1] < 0)
        if (off != hidden) { hidden = off; invalidate() }
    }

    private val edgePaint = Paint().apply { color = 0x01FFFFFF; style = Paint.Style.FILL }

    /** Почти прозрачные полоски по краям: в форму стекла (альфа ≥ 0.5) не попадают, но растягивают границы содержимого. */
    private fun drawEdges(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, 2f, h, edgePaint)
        canvas.drawRect(w - 2f, 0f, w, h, edgePaint)
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            if (hidden) return
            if (glass.applied?.edge == true) drawEdges(canvas)
            val shift = contentShift + (glass.applied?.xs ?: 0)
            if (shift != 0f) {
                // рисуем строку сами: штатный TextView.onDraw обрезает текст по своим границам (clipRect),
                // и при сдвиге вправо цифры срезались на ~200 px раньше края экрана
                val l = layout
                if (l != null) {
                    val p = paint
                    p.color = currentTextColor
                    p.drawableState = drawableState
                    canvas.save()
                    canvas.translate(totalPaddingLeft + shift, totalPaddingTop.toFloat())
                    l.draw(canvas)
                    canvas.restore()
                    return
                }
            }
            super.draw(canvas)
            return
        }
        // программный canvas = СНИМОК виджета: превью при долгом нажатии и (v0.34) картинка, которую лаунчер показывает
        // при разблокировке, пока не подменит её живым виджетом. Живого стекла там нет — рисуем его имитацию:
        // лёгкая заливка + светлый контур (раньше — белые цифры 80%, на разблокировке они ярко «вспыхивали»).
        try {
            val l = layout ?: return
            val p = paint
            val oldColor = p.color; val oldStyle = p.style; val oldW = p.strokeWidth
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            p.style = Paint.Style.FILL
            p.color = 0x2EFFFFFF
            l.draw(canvas)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * resources.displayMetrics.density
            p.color = 0x8CFFFFFF.toInt()
            l.draw(canvas)
            canvas.restore()
            p.style = oldStyle; p.strokeWidth = oldW; p.color = oldColor
        } catch (_: Throwable) {
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); glass.onAttached()
        try { viewTreeObserver.addOnPreDrawListener(posCheck) } catch (_: Throwable) {}
    }
    override fun onDetachedFromWindow() {
        try { viewTreeObserver.removeOnPreDrawListener(posCheck) } catch (_: Throwable) {}
        glass.onDetached(); super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) glass.onVisible()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!cut) glass.onSized(w, h)   // укорачивание у края — не повод переприменять стекло
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (cut) { cut = false; layoutW = r - l }   // настоящая раскладка отменяет укорачивание
    }
}
