package com.artemiy.glasstest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.RemoteViews

/**
 * v0.16 «Стеклянные часы»: время и дата (TextClock — тикает сам, без сервиса) на живом жидком стекле
 * (GlassBgView в режиме clear). Тап — открыть будильники системных часов.
 */
open class GlassClockWidget(
    private val layout: Int = R.layout.clock_2x2,
    private val rootId: Int = R.id.clock_root,
    /** view, которому отладочная команда задаёт конфиг стекла (contentDescription) и цвет текста. */
    private val debugId: Int = 0,
    /** v0.22: часы из стеклянных цифр — им уходит стиль, выбранный в приложении (contentDescription "look=…"). */
    private val lookId: Int = 0
) : AppWidgetProvider() {
    companion object {
        /** Отладка без касаний экрана: adb shell am broadcast -n <pkg>/<provider> -a ...GLASS_DEBUG
         *  --ei appWidgetId N --es desc "recipe=…" [--es color FFFFFFFF] */
        const val ACTION_DEBUG = "com.artemiy.glasstest.GLASS_DEBUG"
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_DEBUG && debugId != 0) {
            val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id != -1) {
                val v = RemoteViews(c.packageName, layout)
                v.setContentDescription(debugId, i.getStringExtra("desc"))
                i.getStringExtra("color")?.let { v.setTextColor(debugId, java.lang.Long.parseLong(it, 16).toInt()) }
                v.setOnClickPendingIntent(rootId, alarms(c))
                AppWidgetManager.getInstance(c).updateAppWidget(id, v)
                Diag.report(c, "[app] debug $id desc=${i.getStringExtra("desc")} color=${i.getStringExtra("color")}")
            }
            return
        }
        super.onReceive(c, i)
    }

    private fun alarms(c: Context) = PendingIntent.getActivity(
        c, 0,
        Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val look = if (lookId != 0) GlassLook.desc(c) else null
        ids.forEach { id ->
            val v = RemoteViews(c.packageName, layout)
            v.setOnClickPendingIntent(rootId, alarms(c))
            look?.let { v.setContentDescription(lookId, it) }
            m.updateAppWidget(id, v)
        }
    }
}

/** v0.16 широкие часы 4×2. */
class GlassClockWideWidget : GlassClockWidget(R.layout.clock_4x2, R.id.clockw_root)

/**
 * v0.19 «Стеклянное время»: цифры Dela Gothic One сами — жидкое стекло, фона нет (GlassTextClock).
 * Тап — будильники, как у «Стеклянных часов». Конфиг стекла не задаём — GlassTextClock берёт рабочий по умолчанию
 * v0.20: цифры — GlassDigitsClock (каждая цифра своим view). Отладочная команда (только desc, без color!) задаёт
 * конфиг всем цифрам через contentDescription часов.
 */
class GlassTimeWideWidget : GlassClockWidget(R.layout.glasstime_4x2, R.id.glasstime_root, R.id.glasstime_text, R.id.glasstime_text)

/** v0.19 «Стеклянное время» 2×2: часы над минутами. v0.22: + отладка и стиль из приложения. */
class GlassTimeWidget : GlassClockWidget(R.layout.glasstime_2x2, R.id.glasstime2_root, R.id.glasstime2_text, R.id.glasstime2_text)

/** v0.30 «Стеклянное время» 4×3: макет 4×2, часы сами выбирают 1 или 2 строки по размеру; можно растягивать. */
class GlassTimeBigWidget : GlassClockWidget(R.layout.glasstime_4x2, R.id.glasstime_root, R.id.glasstime_text, R.id.glasstime_text)
