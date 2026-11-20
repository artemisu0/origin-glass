// Положить в пакет приложения (app/src/main/java/<твой.пакет>/GlassWidget.kt) и добавить строку package
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

class GlassWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, RemoteViews(c.packageName, R.layout.glass_widget)) }
    }
}
