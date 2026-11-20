package com.artemiy.glasstest

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RemoteViews

/**
 * Слой-подложка со стеклом (как OriginFolderIconBlurView у папок): стекло на view без содержимого,
 * текст/кнопки — соседи НАД ним. Код выполняется в процессе хоста (белый список «живых» виджетов
 * + @RemoteViews.RemoteView). Вся логика стекла — в GlassHelper (GlassCore.kt).
 *
 * Конфиг: contentDescription "recipe=clear|folder|plain;style=N;ct=N" (так управляет Glass lab);
 * без него при android:tag="auto" — по хосту: лаунчер → clear, Jovi → plain 420 ct 1.
 *
 * Превью при долгом нажатии / перетаскивании — СНИМОК виджета через программный canvas; живое стекло
 * туда не попадает, поэтому в снимке рисуем имитацию плашки (лёгкая заливка + светлый ободок).
 */
@RemoteViews.RemoteView
class GlassBgView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val glass = GlassHelper(this, cornerRadiusDp = 24f, defaultCfg = { isLauncher ->
        if (tag != "auto") null
        else if (isLauncher) GlassCfg("clear", 0, 2) else GlassCfg("plain", 420, 1)
    })

    private val previewFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL; color = 0x1AFFFFFF
    }
    private val previewStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; color = 0x73FFFFFF
    }
    private val previewRect = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        if (canvas.isHardwareAccelerated) {
            super.draw(canvas)
            return
        }
        try {
            val d = resources.displayMetrics.density
            val sw = 1.5f * d
            previewStroke.strokeWidth = sw
            previewRect.set(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
            val r = 24f * d
            canvas.drawRoundRect(previewRect, r, r, previewFill)
            canvas.drawRoundRect(previewRect, r, r, previewStroke)
        } catch (_: Throwable) {
        }
    }

    // v0.12: НЕ переопределяем setContentDescription — у переопределения нет @RemotableViewMethod,
    // и RemoteViews отказывался его вызывать (в v0.11 Glass lab стал белой заглушкой).

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
