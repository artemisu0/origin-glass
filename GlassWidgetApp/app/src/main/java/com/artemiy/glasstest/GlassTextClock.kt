package com.artemiy.glasstest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.RemoteViews
import android.widget.TextClock

/**
 * v0.19: часы, у которых СТЕКЛОМ являются сами цифры (фона нет).
 * Это TextClock (тикает сам, в процессе хоста), на который вешается живое жидкое стекло (GlassHelper).
 *
 * Выяснено на телефоне (07.11.2026), подсказка — цифры часов экрана блокировки (SystemUIPlugin GlassApplier):
 *  - РАБОТАЕТ: view НЕ в пакете лаунчера (BATCH_NONE) + setMaterial(0, ct 2) + рецепт clear + НЕПРОЗРАЧНЫЙ текст.
 *    HWUI строит форму стекла из альфы содержимого (SDF методом JFA, «внутри» = альфа ≥ 0.5) → стекло с преломлением
 *    и ободком ровно по контуру цифр, сам белый цвет текста не виден (стекло его заменяет), фона нет.
 *  - рецепт liquid (как на экране блокировки: только setLiquidGlassEffect(1)) → матовые серые стеклянные цифры.
 *  - НЕ работает: пакет лаунчера CHILD (там стекло — только скруглённый прямоугольник по границам view, форма из
 *    содержимого/outline/clip не берётся); текст с альфой < 0.5 (#01 — маска пустая); ct 1 в лаунчере.
 * Режимы для опытов остались (contentDescription "recipe=…;batch=0;outline=1;…", разбор — GlassHelper; отладочная
 * команда — GlassClockWidget.ACTION_DEBUG).
 *
 * Превью при долгом нажатии — снимок через программный canvas, стекла в нём нет: там рисуем время
 * обычными полупрозрачными белыми цифрами, чтобы оно не пропадало.
 */
@RemoteViews.RemoteView
class GlassTextClock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : TextClock(context, attrs, defStyle) {

    private val glyphPath = Path()
    private val linePath = Path()
    @Volatile private var cfg: GlassCfg? = null

    private val glyphOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val p = try { buildGlyphPath() } catch (_: Throwable) { null }
            if (p != null && !p.isEmpty && Build.VERSION.SDK_INT >= 30) {
                outline.setPath(p)
            } else {
                outline.setRect(0, 0, view.width, view.height)
            }
            outline.alpha = 1f
        }
    }

    private val glass = GlassHelper(this, cornerRadiusDp = null, defaultCfg = { isLauncher ->
        if (isLauncher) GlassCfg("clear", 0, 2, batch = false)
        else GlassCfg("clear", 0, 1, batch = false)
    }, onApplied = { c ->
        cfg = c
        if (c?.outline == true) {
            outlineProvider = glyphOutline
            clipToOutline = true
        } else {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = false
        }
        invalidateOutline()
    })

    /** Контур цифр в координатах view. */
    private fun buildGlyphPath(): Path? {
        val l = layout ?: return null
        val t = l.text ?: return null
        val s = t.toString()
        glyphPath.reset()
        for (i in 0 until l.lineCount) {
            val st = l.getLineStart(i)
            var en = l.getLineEnd(i)
            while (en > st && (s[en - 1] == '\n' || s[en - 1] == ' ')) en--
            if (en <= st) continue
            linePath.reset()
            paint.getTextPath(s, st, en, l.getLineLeft(i), l.getLineBaseline(i).toFloat(), linePath)
            glyphPath.addPath(linePath)
        }
        glyphPath.offset(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        return glyphPath
    }

    /** Последний контур, по которому применено стекло: цифры сменились → переприменяем (форма берётся при setMaterial). */
    private var shapeKey: String? = null

    private fun shapeChanged(reason: String) {
        try {
            if (cfg?.outline != true || !isAttachedToWindow) return
            val key = "${text}|${width}x${height}|${textSize}"
            if (key == shapeKey) return
            shapeKey = key
            invalidateOutline()
            glass.reapply(reason)
        } catch (_: Throwable) {}
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        post { shapeChanged("text") }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post { shapeChanged("layout") }
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            if (cfg?.cpath == true) {
                val path = try { buildGlyphPath() } catch (_: Throwable) { null }
                if (path != null) {
                    canvas.save()
                    canvas.clipPath(path)
                    super.draw(canvas)
                    canvas.restore()
                    return
                }
            }
            super.draw(canvas)
            return
        }
        // программный canvas = снимок для превью при долгом нажатии: обычные белые цифры
        try {
            val l = layout ?: return
            val p = paint
            val old = p.color
            p.color = 0xCCFFFFFF.toInt()
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            l.draw(canvas)
            canvas.restore()
            p.color = old
        } catch (_: Throwable) {
        }
    }

    /*
     * v0.19: когда страницу тянут и view заметно уходит за край экрана (> ~30 px), HWUI сам выключает стекло по
     * содержимому и рисует просто текст (белые цифры) — до возврата страницы. Пробовал смягчать: менять цвет текста
     * (форма стекла портилась «полосами») или View.alpha (стекло оставалось бледным) — оба варианта хуже, убраны.
     */

    override fun onAttachedToWindow() { super.onAttachedToWindow(); glass.onAttached() }
    override fun onDetachedFromWindow() { glass.onDetached(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) glass.onVisible()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glass.onSized(w, h)
    }
}
