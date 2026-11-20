package com.artemiy.glasstest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * «Glass lab»: подбор стекла кнопками прямо на виджете.
 *  ◀ / ▶  — стиль BlurMaterial (0 = как у папок; 409–434 — стили жидкого стекла OS7).
 *  M      — режим: clear (жидкое стекло без мути и тона) / folder (точно как папки) / plain ct 2 / plain ct 1.
 *  BG     — подложка слоя: none (альфа 1/255) / светлая / тёмная как у календаря.
 *
 * v0.11: параметры уходят в слой GlassBgView через setContentDescription ("recipe=…;style=…;ct=…"),
 * а стекло ставит и поддерживает сам слой в процессе хоста. Раньше (v0.6–0.10) материал задавался
 * вызовами RemoteViews и слетал от любого касания рабочего стола — восстановить его было некому.
 */
class GlassLabWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION = "com.artemiy.glasstest.LAB"
        private const val PREFS = "glass_lab_v14"

        val STYLES = intArrayOf(0, 2, 6, 11, 51, 101, 151, 201, 206, 251, 301, 401) + (409..434).toList().toIntArray()

        /** Режимы M: (recipe, contentType). */
        private val MODES = arrayOf("clear" to 2, "folder" to 2, "plain" to 2, "plain" to 1)

        private val BGS = intArrayOf(R.drawable.glass_bg_none, R.drawable.lab_bg_light, R.drawable.glass_bg)
        private val BG_NAMES = arrayOf("none", "light", "dark")
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(c, m, it) }
    }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action != ACTION) return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var style = p.getInt("style", 0)
        var mode = p.getInt("mode", 0)
        var bg = p.getInt("bg", 0)
        when (i.getStringExtra("cmd")) {
            "prev" -> style = (style - 1 + STYLES.size) % STYLES.size
            "next" -> style = (style + 1) % STYLES.size
            "mode" -> mode = (mode + 1) % MODES.size
            "bg" -> bg = (bg + 1) % BGS.size
        }
        p.edit().putInt("style", style).putInt("mode", mode).putInt("bg", bg).apply()
        val m = AppWidgetManager.getInstance(c)
        m.getAppWidgetIds(ComponentName(c, GlassLabWidget::class.java)).forEach { render(c, m, it) }
    }

    private fun render(c: Context, m: AppWidgetManager, id: Int) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val style = STYLES[p.getInt("style", 0).coerceIn(STYLES.indices)]
        val (recipe, ct) = MODES[p.getInt("mode", 0).coerceIn(MODES.indices)]
        val bg = p.getInt("bg", 0).coerceIn(BGS.indices)

        val v = RemoteViews(c.packageName, R.layout.lab_widget)
        v.setTextViewText(R.id.lab_label, "style $style\n$recipe · ct $ct · ${BG_NAMES[bg]}")
        v.setInt(R.id.lab_glass, "setBackgroundResource", BGS[bg])
        // конфиг для GlassBgView (он перехватывает setContentDescription и сам ставит стекло в хосте)
        v.setContentDescription(R.id.lab_glass, "recipe=$recipe;style=$style;ct=$ct")

        v.setOnClickPendingIntent(R.id.lab_prev, pi(c, "prev", 1))
        v.setOnClickPendingIntent(R.id.lab_next, pi(c, "next", 2))
        v.setOnClickPendingIntent(R.id.lab_mode, pi(c, "mode", 3))
        v.setOnClickPendingIntent(R.id.lab_bg, pi(c, "bg", 4))
        m.updateAppWidget(id, v)
    }

    private fun pi(c: Context, cmd: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, code,
            Intent(c, GlassLabWidget::class.java).setAction(ACTION).putExtra("cmd", cmd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
