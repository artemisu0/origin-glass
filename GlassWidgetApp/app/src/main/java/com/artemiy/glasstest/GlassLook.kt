package com.artemiy.glasstest

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * v0.22–0.23: стиль стекла цифр «Стеклянного времени», выбирается в приложении (MainActivity — панель поверх
 * рабочего стола, видно вживую).
 *
 * Стиль = РЕЖИМ (как собирать стекло) + НОМЕР стиля BlurMaterial для setMaterial(style, ct) — как ◀/▶ в Glass lab.
 * Номера — из vivo-framework BlurMaterial.findStyle (README): 409–434 — стили жидкого стекла OS7, часть номеров
 * может ничего не рисовать.
 *
 * Путь: приложение сохраняет стиль → onUpdate виджетов кладёт его в contentDescription часов ("look=…;st=…") →
 * цифры (GlassDigit, в процессе лаунчера) собирают по нему GlassCfg. Строка с "recipe=…" (GLASS_DEBUG) важнее.
 */
object GlassLook {
    class Mode(val id: String, val title: String, val hint: String)

    val MODES = listOf(
        Mode("clear", "Прозрачное", "преломление 140/60 + ободок (как было)"),
        Mode("liquid", "Жидкое", "только жидкое стекло стиля, без своих параметров"),
        Mode("folder", "Папки", "рецепт папок: дымка + тон под обои"),
        Mode("plain", "Блюр", "материал стиля без жидкого стекла")
    )

    /** Все известные номера стилей BlurMaterial (vivo-framework findStyle). */
    val STYLES: IntArray = (
        listOf(0) + (2..11) + (51..59) + (101..113) + (151..163) + (201..215) + (251..265) +
        (301..303) + (311..313) + (321..323) + listOf(331) + (351..353) + (361..363) + (371..373) +
        listOf(381) + (401..434)
    ).toIntArray()

    const val DEFAULT_MODE = "clear"
    const val DEFAULT_STYLE = 0
    private const val PREFS = "glass_look"

    fun mode(c: Context): String {
        val id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", DEFAULT_MODE)
        return if (MODES.any { it.id == id }) id!! else DEFAULT_MODE
    }

    fun style(c: Context): Int {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("style", DEFAULT_STYLE)
        return if (s in STYLES) s else DEFAULT_STYLE
    }

    fun save(c: Context, mode: String, style: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode).putInt("style", style).remove("look").apply()
    }

    /** Строка для contentDescription часов. v0.29: + шрифт (GlassFonts). */
    fun desc(c: Context) = "look=${mode(c)};st=${style(c)};font=${GlassFonts.selected(c)}"

    internal fun parse(desc: CharSequence?): Map<String, String> {
        val s = desc?.toString() ?: return emptyMap()
        return s.split(';').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
        }.toMap()
    }

    /**
     * Конфиг стекла цифры (вызывается в процессе хоста). Всё — стекло по содержимому вне пакета лаунчера
     * (batch=false) с clamp у краёв экрана; ct: лаунчер 2, Jovi 1 (см. README).
     */
    internal fun cfg(desc: CharSequence?, isLauncher: Boolean, clampM: Int): GlassCfg {
        val map = parse(desc)
        val ct = if (isLauncher) 2 else 1
        val st = map["st"]?.toIntOrNull() ?: DEFAULT_STYLE
        val recipe = map["look"]?.takeIf { m -> MODES.any { it.id == m } } ?: DEFAULT_MODE
        return GlassCfg(recipe, st, ct, batch = false, clamp = true, clampM = clampM)
    }

    /** Разослать текущий стиль во все «Стеклянное время». Возвращает, сколько виджетов обновлено. */
    fun applyAll(c: Context): Int {
        val m = AppWidgetManager.getInstance(c)
        var n = 0
        for (p in listOf(GlassTimeWideWidget(), GlassTimeWidget(), GlassTimeBigWidget())) {
            val ids = m.getAppWidgetIds(ComponentName(c, p.javaClass))
            if (ids.isNotEmpty()) {
                p.onUpdate(c, m, ids)
                n += ids.size
            }
        }
        return n
    }
}
