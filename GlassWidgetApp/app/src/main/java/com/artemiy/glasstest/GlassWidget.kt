package com.artemiy.glasstest

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Стекло через скрытые методы vivo у android.view.View, помеченные в OriginOS 7 как
 * @RemotableViewMethod (их можно вызывать из обычного RemoteViews; так же делает
 * партнёрский SDK Umetrip в HiBoard).
 *  - style: номер стиля BlurMaterial; 409–434 — жидкое стекло OS7.
 *  - contentType 1 = отрисовка на HW underlay (+ setMaterialDrawOnHwUnderlay).
 *  - форма стекла берётся из фона view (без фона стеклом становится текст).
 */
object Glass {
    fun apply(c: Context, v: RemoteViews, viewId: Int, style: Int, contentType: Int = 1, liquid: Boolean = false) {
        v.setBundle(viewId, "clearMaterial", Bundle())
        v.setBundle(viewId, "setMaterial", Bundle().apply {
            putInt("style", style)             // IVivoBlurRender.BlurGeometry.STYLE
            putInt("contentType", contentType) // IVivoBlurRender.BlurGeometry.CONTENT_TYPE
        })
        if (contentType == 1) v.setBoolean(viewId, "setMaterialDrawOnHwUnderlay", true)
        v.setFloat(viewId, "setMaterialCornerRadius", 24f * c.resources.displayMetrics.density)
        if (liquid) v.setFloat(viewId, "setLiquidGlassEffect", 1.0f) // только после setMaterial
    }
}

/**
 * v0.9 «Glass»: стекло ставит сам слой GlassBgView (tag="auto") внутри хоста — style 420,
 * в лаунчере batch CHILD + contentType 2, в Jovi contentType 1 + HW underlay.
 * Здесь только текст: RemoteViews-вызовы материала не нужны.
 */
class GlassMaterialWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach {
            val v = RemoteViews(c.packageName, R.layout.glass_widget)
            v.setTextViewText(R.id.label, "Glass v0.34")
            m.updateAppWidget(it, v)
        }
    }
}
