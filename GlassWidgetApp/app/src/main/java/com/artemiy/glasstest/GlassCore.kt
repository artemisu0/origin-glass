package com.artemiy.glasstest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Method

/**
 * Общая логика живого стекла для наших view в процессе хоста (лаунчер / Jovi).
 * Вынесено из GlassBgView в v0.19, чтобы ею пользовались и подложка (GlassBgView), и стеклянный текст (GlassTextClock).
 *
 * Как работает (история — в README):
 *  - хост по имени процесса: com.bbk.launcher2 → рецепт стекла папок с ct 2; com.vivo.hiboard → свой;
 *  - в лаунчере жидкое стекло рисуется пакетом: контейнер ячеек = batch GROUP(1) → себе ставим CHILD(2);
 *  - порядок применения — как в v0.12, в ОДИН кадр: batch → clearMaterial → setMaterial → рецепт
 *    (двухкадровый сброс v0.13 делал стекло ~30% темнее);
 *  - самовосстановление: смена конфига (contentDescription) — на каждом кадре, обход предков — раз в 250 мс
 *    с догоняющей проверкой; плюс при появлении/смене размера;
 *  - форма стекла = то, что рисует view (фон/глифы); цвет «чернил» с альфой 1/255 даёт форму без цвета.
 *
 * ВАЖНО: работает в процессе лаунчера — любое исключение уронит лаунчер, поэтому всё в try/catch.
 */
internal data class GlassCfg(
    val recipe: String, val style: Int, val ct: Int,
    /** v0.19: false → не вставать в пакет жидкого стекла лаунчера (BATCH_NONE). */
    val batch: Boolean = true,
    /** v0.19: enableMaterialClipWhenBlend — стекло обрезается по клипу из списка отрисовки view. */
    val clip: Boolean = false,
    /** v0.19: радиус скругления области стекла (dp); null — как задано у view. */
    val radiusDp: Float? = null,
    /** v0.19: форма view = контур глифов (outline-path + clipToOutline) — для GlassTextClock. */
    val outline: Boolean = false,
    /** v0.19: canvas.clipPath по глифам в draw() — для GlassTextClock. */
    val cpath: Boolean = false,
    /** v0.19: setImageAlphaInBlurAlgorithm — альфа содержимого view участвует в алгоритме стекла (форма по глифам?). */
    val ialpha: Boolean = false,
    /** v0.19: vibrancy (View.setVibrancyEffect(style,type)): содержимое view смешивается со стеклом; null — выкл. */
    val vibStyle: Int? = null,
    val vibType: Int = 0,
    /** v0.19 рецепт liquid (как цифры часов экрана блокировки): интенсивность setLiquidGlassEffect. */
    val lg: Float = 1f,
    /** v0.19 liquid: состояние setLiquidGlassRimLight (null — не трогать). */
    val rim: Int? = null,
    /** v0.19 liquid: chooseMaterialBlurIteration (null — не трогать; на экране блокировки 4). */
    val iter: Int? = null,
    /** v0.20 опыт: область стекла setMaterialRegion. 0 — не задавать; 1 — каждый кадр видимая на экране часть view;
     *  2 — один раз весь view. */
    val region: Int = 0,
    /** v0.20 опыт: setMaterialForceCheckRegion(true). */
    val fcr: Boolean = false,
    /** v0.20: не рисовать цифру, чей левый край за экраном (GlassDigit). */
    val hide: Boolean = true,
    /** v0.20: держать view цифры внутри экрана (сдвиг view + обратный сдвиг рисунка), GlassDigit. */
    val clamp: Boolean = false,
    /** v0.20: отступ от края экрана для clamp (px). */
    val clampM: Int = 0,
    /** v0.20 опыт: отступ справа (px; null — как clampM) и включён ли сдвиг у правого края. */
    val clampR: Int? = null,
    val clampRight: Boolean = true,
    /** v0.20 опыт: у правого края не сдвигать view, а укорачивать его (setRight). */
    val rightBySize: Boolean = false,
    /** v0.20 опыт: постоянный сдвиг рисунка цифр вправо (px), чтобы проверить стекло у правого края в покое. */
    val xs: Int = 0,
    /** v0.20 опыт: рисовать почти прозрачные точки по краям view (границы содержимого = весь view). */
    val edge: Boolean = false,
    /** v0.20 опыт: постоянный translationX view в покое (px). */
    val tx: Int = 0,
    /** v0.20 опыт: группа материала. null — 3 (как у папок); -1 — своя на каждый view; иначе это число. */
    val gid: Int? = null,
    /** v0.20 опыт: поворот светлого ободка (setGlassEffectInnerStrokeLightRotation), null — не трогать. */
    val lrot: Float? = null
)

/**
 * v0.21: где view на экране БЕЗ своего translationX — в экранных px.
 * Лаунчер vivo строит виджет в «полной» ширине (LauncherAppWidgetView 1440 px) и уменьшает его масштабом
 * (~0.857 у 4×2) под ячейки. Поэтому локальные px (width, translationX) ≠ экранным (getLocationInWindow).
 * До v0.21 clamp их смешивал: у левого края сходилось, а у правого view вставало на x≈1337 и цифры
 * срезались на ~100 px раньше края экрана.
 * out: [0] левый край, [1] правый край (экран, без нашего сдвига), [2] k — масштаб: экранных px на 1 локальный.
 */
internal object ScreenSpan {
    private val mx = android.graphics.Matrix()
    private val pts = FloatArray(4)
    private val loc = IntArray(2)

    fun of(v: View, out: FloatArray): Boolean {
        val w = v.width
        if (w <= 0) return false
        var l: Float
        var r: Float
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            mx.reset()
            v.transformMatrixToGlobal(mx)   // view → экран, с масштабом всех предков и своим translationX
            pts[0] = 0f; pts[1] = 0f; pts[2] = w.toFloat(); pts[3] = 0f
            mx.mapPoints(pts)
            l = minOf(pts[0], pts[2]); r = maxOf(pts[0], pts[2])
        } else {
            v.getLocationOnScreen(loc)
            var k = v.scaleX
            var p = v.parent
            while (p is View) { k *= p.scaleX; p = p.parent }
            l = loc[0].toFloat(); r = l + w * k
        }
        val own = if (v.scaleX != 0f) v.scaleX else 1f
        val k = (r - l) / w / own           // масштаб предков: в нём живёт translationX
        val shift = k * v.translationX
        out[0] = l - shift; out[1] = r - shift; out[2] = k
        return k > 0f
    }
}

internal class GlassHelper(
    private val v: View,
    /** Ставить скругление области стекла (для подложки — да, для текста — нет). */
    private val cornerRadiusDp: Float?,
    /** Конфиг по умолчанию, если в contentDescription нет "recipe=…": (isLauncher) → cfg. */
    private val defaultCfg: (Boolean) -> GlassCfg?,
    /** Вызывается ПЕРЕД применением материала (view задаёт форму: outline и т.п. — лаунчер берёт её при setMaterial). */
    private val onApplied: ((GlassCfg?) -> Unit)? = null,
    /** v0.19: view остановилось после движения — пусть чуть изменит содержимое, чтобы HWUI пересчитал форму стекла. */
    private val onSettled: (() -> Unit)? = null,
    /** v0.20: чей contentDescription читать как конфиг (у цифр GlassDigit — у часов-родителя). */
    private val cfgSource: () -> View? = { v }
) {
    private fun cfgDesc(): String? = try { cfgSource()?.contentDescription?.toString() } catch (_: Throwable) { null }
    private companion object {
        const val BATCH_NONE = 0
        const val BATCH_GROUP = 1
        const val BATCH_CHILD = 2
        const val CHECK_MS = 250L
        val I = Int::class.javaPrimitiveType!!
        val F = Float::class.javaPrimitiveType!!
        val Z = Boolean::class.javaPrimitiveType!!
        val methods = HashMap<String, Method?>()
        /** v0.25: предки, которым МЫ временно выключили «перекрытие» (общие для всех цифр всех часов). */
        val noOverlap = java.util.WeakHashMap<View, Boolean>()
        /** Предки, которых уже видели в этой загрузке кода. */
        val seenAnc = java.util.WeakHashMap<View, Boolean>()
        /** Растёт при каждом снятии флага — по нему все цифры переприменяют стекло. */
        var overlapEpoch = 0
        /** v0.28: до какого времени корень (DragLayer) считается «в переходе» — «перекрытие» держим выключенным. */
        val busyUntil = java.util.WeakHashMap<View, Long>()
        const val BUSY_MS = 250L
        /** v0.34: прошлая alpha контейнера виджета — чтобы отличить проявление (растёт) от угасания. */
        val hostAlpha = java.util.WeakHashMap<View, Float>()
        val hostOverriddenAt = java.util.WeakHashMap<View, Long>()
        /** v0.34: alpha предков в прошлом кадре и «проявляется из невидимости» (была alpha 0). */
        val lastAlpha = java.util.WeakHashMap<View, Float>()
        val fromHidden = java.util.WeakHashMap<View, Boolean>()
    }

    val host: String = try { android.app.Application.getProcessName() } catch (t: Throwable) { "?" }
    val isLauncher get() = host.startsWith("com.bbk.launcher2")
    private var cfgText: String? = null
    private var lastGroup: Boolean? = null
    private var lastApplied: String? = null
    private var lastCheck = 0L
    private var trailingCheck = false
    /** Текущий конфиг (после apply) — view может подстроить отрисовку (например, клип по глифам). */
    @Volatile var applied: GlassCfg? = null
        private set
    private var hadVibrancy = false
    /** v0.23: прошлый конфиг ставил chooseMaterialBlurIteration — новый без него должен сбросить. */
    private var hadIter = false

    /** v0.19: стекло по содержимому (вне пакета) при листании страниц застревало обрезанным, пока view не
     *  перерисуется (смена минуты). Следим за положением в окне: двигается → перерисовка, остановился → переприменить. */
    private val loc = IntArray(2)
    private val span = FloatArray(3)
    /** v0.27: масштаб предков (ScreenSpan k), при котором стекло применено последний раз. */
    private var appliedK = 0f
    private val spanA = FloatArray(3)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var moveFrom = ""
    private var settlePending = false
    private val settle = Runnable {
        settlePending = false
        v.invalidate()
        ensure("moved $moveFrom->$lastX,$lastY", force = true)
        // форма стекла по содержимому пересчитывается только при смене содержимого; если она посчиталась во время
        // движения/в слое — остаётся испорченной («полосы», обрезка). Меняем содержимое незаметно для глаза.
        v.post { try { onSettled?.invoke() } catch (_: Throwable) {} }
    }

    /**
     * v0.19: пока палец тянет страницу, лаунчер ставит контейнеру ячеек (ShortcutAndWidgetContainer) АППАРАТНЫЙ слой
     * (layerType 2) — внутри слоя стекло по содержимому ломается (видна верхняя часть цифр, проверено удержанием на 7%).
     * Стеклу папок (пакет) слой не мешает. Поэтому снимаем такой слой с ближайших предков обратно в NONE.
     */
    private fun dropAncestorLayers() {
        var p: Any? = v.parent
        var d = 0
        var changed = false
        // v0.25: при выезде Jovi слой получает DragLayer — 9-й предок; раньше смотрели только 8 → берём всю цепочку
        val now = SystemClock.uptimeMillis()
        while (p is View && d < 30) {
            // v0.28: DragLayer — корень рабочего стола; слой на нём = начало перехода (Jovi, запуск приложения), через
            // несколько кадров пойдут alpha и масштаб. Каждое изменение свойств предка даёт один кадр с полосами, поэтому
            // «перекрытие» выключаем сразу вместе со снятием слоя — два битых кадра сливаются в один.
            val root = p.javaClass.simpleName == "DragLayer"
            // v0.34: после включения экрана лаунчер ~1 с проявляет НАШ виджет (alpha его контейнера 0 → 1). С alpha < 1
            // стекло цифр либо выключается (буфер), либо без ободка (наш флаг) — «серым падает, потом стеклянным».
            // Проявление нашего виджета пропускаем: пока alpha контейнера растёт, держим 1 (весь рабочий стол
            // проявляется сам за ~30 мс). Угасание (alpha падает) не трогаем — лаунчер прячет виджет по делу.
            if (p.javaClass.simpleName == "LauncherAppWidgetHostView") {
                val a = p.alpha
                // запоминаем значение ЛАУНЧЕРА (не нашу подмену на 1) — иначе следующий шаг проявления (0.03 после
                // нашей 1) выглядел как угасание, и флаг «без перекрытия» включался/выключался каждый кадр
                // функцию зовёт каждая цифра — вторая и третья видят уже нашу 1: её в этом кадре не запоминаем
                val ours = a >= 1f && now - (hostOverriddenAt[p] ?: 0L) < 12
                val prev = if (ours) a else hostAlpha.put(p, a) ?: 1f
                if (a > 0f && a < 1f && a > prev) {
                    p.alpha = 1f
                    hostOverriddenAt[p] = now
                    if (!skippedFade) { skippedFade = true; Diag.report(v.context, "[$host] skip widget fade-in a=$a") }
                } else if (a >= 1f) skippedFade = false
            }
            if (p.layerType == View.LAYER_TYPE_HARDWARE) {
                p.setLayerType(View.LAYER_TYPE_NONE, null)
                Diag.report(v.context, "[$host] dropped HW layer of ${p.javaClass.simpleName}@$d")
                changed = true
                // v0.33: слой САМ ПО СЕБЕ флаг не включает. Возврат на рабочий стол / включение экрана / закрытие
                // приложения меняют только масштаб (alpha = 1, трассировка) — с флагом цифры всю анимацию без ободка
                // («серые»). А если снять флаг посреди анимации масштаба (v0.32: окно 80 мс), ободок пропадал НАСОВСЕМ —
                // до перезапуска лаунчера. Поэтому флаг — только когда alpha реально < 1 (Jovi), снятие — только в покое.
            }
            // v0.34: проявление из полной невидимости (включение экрана: alpha 0 → 1 за ~30 мс) — флаг не включаем:
            // с ним ободка нет ещё BUSY_MS после того, как всё уже видно. Пока alpha = 0, view не рисуется вовсе.
            val a = p.alpha
            val prevA = lastAlpha.put(p, a) ?: 1f
            if (a <= 0f) fromHidden[p] = true else if (a >= 1f) fromHidden.remove(p)
            val wantsFlag = a > 0f && a < 1f && !(fromHidden[p] == true && a >= prevA)
            if (root && wantsFlag) busyUntil[p] = now + BUSY_MS
            // флаг уже стоит — держим, пока идёт масштаб (снимать его можно только в покое, см. выше)
            if (root && p in noOverlap && (p.scaleX < 0.999f || p.scaleX > 1.001f)) busyUntil[p] = now + BUSY_MS
            if (root && (busyUntil[p] ?: 0L) > now) {
                // после перехода экран может замереть — нужен кадр, чтобы снять флаг и вернуть ободок
                v.removeCallbacks(kick); v.postDelayed(kick, (busyUntil[p] ?: now) - now + 30)
            }
            // v0.25: при выезде Jovi DragLayer ещё и полупрозрачен (a 0.95→0.89). С alpha < 1 HWUI рисует поддерево
            // через промежуточный буфер — стекло по содержимому там выключается («10» плоские). Без «перекрытия»
            // альфа применяется к каждой операции рисования напрямую, без буфера.
            // НО: пока флаг стоит, у стекла пропадает светлый ободок даже при alpha 1 (проверено перезапуском лаунчера) —
            // поэтому ставим его только на время полупрозрачности и снимаем, как только alpha вернулась к 1.
            // первая встреча с предком в этой загрузке кода: флаг мог остаться выключенным от прошлой версии (v0.25 до
            // правки ставила его навсегда) — тогда тоже снимаем
            val first = seenAnc.put(p, true) == null
            val busy = (busyUntil[p] ?: 0L) > now
            if (wantsFlag || (busy && p in noOverlap)) {
                if (p.getHasOverlappingRendering() && p !in noOverlap) {
                    p.forceHasOverlappingRendering(false)
                    noOverlap[p] = true
                    Diag.report(v.context, "[$host] no-overlap ON ${p.javaClass.simpleName}@$d a=${p.alpha}")
                    changed = true
                }
            } else if (noOverlap.remove(p) != null ||
                (first && !p.getHasOverlappingRendering() && p.hasOverlappingRendering())) {
                p.forceHasOverlappingRendering(true)   // как по умолчанию у View
                Diag.report(v.context, "[$host] no-overlap OFF ${p.javaClass.simpleName}@$d")
                changed = true
            }
            p = p.parent; d++
        }
        // предки поменялись (слой снят, «перекрытие» вкл/выкл) — форма стекла и ободок пересчитываются только при
        // переприменении, и у ВСЕХ цифр. v0.27: сразу, в этом же кадре (раньше через post — кадр с полосами успевал попасть
        // на экран). Цифры, чей preDraw в этом кадре уже прошёл, догоняют в следующем.
        if (changed) overlapEpoch++
        if (seenEpoch != overlapEpoch) {
            seenEpoch = overlapEpoch
            ensure("ancestors", force = true, quiet = true)
        }
    }
    private var seenEpoch = overlapEpoch
    private var skippedFade = false
    private val kick = Runnable { v.invalidate() }

    /** v0.20: область стекла = видимая часть view (обрезанная краями экрана/родителей), в координатах view. */
    private val visR = android.graphics.Rect()
    private val regionF = android.graphics.RectF()
    private var lastRegion = ""
    private val loc2 = IntArray(2)

    private fun updateRegion(cfg: GlassCfg?, force: Boolean = false) {
        if (cfg == null || cfg.region == 0 || v.width <= 0 || v.height <= 0) return
        if (cfg.region == 1) {
            if (!v.getGlobalVisibleRect(visR)) return
            v.getLocationInWindow(loc2)
            visR.offset(-loc2[0], -loc2[1])
            regionF.set(visR)
        } else {
            regionF.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
        }
        val key = regionF.toShortString()
        if (!force && key == lastRegion) return
        lastRegion = key
        val log = StringBuilder()
        call(log, "setMaterialRegion", arrayOf(android.graphics.RectF::class.java, F, F, F, F), regionF, 0f, 0f, 0f, 0f)
        if (log.isNotEmpty()) Diag.report(v.context, "[$host] region $key errors:$log")
    }

    /**
     * v0.25 диагностика (Jovi): пока выезжает HiBoard, «10» теряло стекло, а «:33» рассыпалось на полосы.
     * Сообщаем, что лаунчер делает с предками view: альфа, слой, масштаб, сдвиг (только при изменении, не чаще 60 мс).
     */
    private var lastAnc = ""
    private var lastAncT = 0L
    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.3f".format(f)

    private fun reportAncestors() {
        if (!Diag.ANCESTORS) return
        val sb = StringBuilder()
        var p: Any? = v.parent
        var d = 1
        while (p is View && d < 30) {
            if (p.alpha != 1f || p.layerType != View.LAYER_TYPE_NONE || p.scaleX != 1f || p.scaleY != 1f ||
                p.translationX != 0f || p.translationY != 0f || p.visibility != View.VISIBLE) {
                sb.append(p.javaClass.simpleName).append('@').append(d).append('{')
                if (p.alpha != 1f) sb.append("a=").append(fmt(p.alpha)).append(' ')
                if (p.layerType != View.LAYER_TYPE_NONE) sb.append("L=").append(p.layerType).append(' ')
                if (p.scaleX != 1f || p.scaleY != 1f) sb.append("s=").append(fmt(p.scaleX)).append('x').append(fmt(p.scaleY)).append(' ')
                if (p.translationX != 0f || p.translationY != 0f) sb.append("t=").append(fmt(p.translationX)).append(',').append(fmt(p.translationY)).append(' ')
                if (p.visibility != View.VISIBLE) sb.append("vis=").append(p.visibility).append(' ')
                sb.setLength(sb.length - 1); sb.append("} ")
            }
            p = p.parent; d++
        }
        sb.append("focus=").append(v.hasWindowFocus())
        val s = sb.toString()
        val now = SystemClock.uptimeMillis()
        if (s == lastAnc || now - lastAncT < 60) return
        lastAnc = s; lastAncT = now
        Diag.report(v.context, "[anc] '${(v as? android.widget.TextView)?.text}' $s")
    }

    /** Трассировка кадров (Diag.TRACE): время каждого preDraw первой цифры и состояние DragLayer. */
    private var traceUntil = 0L
    private fun trace() {
        if (!Diag.TRACE) return
        val txt = (v as? android.widget.TextView)?.text?.toString() ?: return
        if (txt.length < 2) return
        var p: Any? = v.parent; var dl: View? = null; var d = 0
        while (p is View && d < 30) { if (p.javaClass.simpleName == "DragLayer") dl = p; p = p.parent; d++ }
        val now = SystemClock.uptimeMillis()
        val busy = dl != null && (dl.alpha < 1f || dl.scaleX != 1f || dl.layerType != View.LAYER_TYPE_NONE)
        if (busy) traceUntil = now + 300
        if (now > traceUntil) return
        val ns = System.nanoTime() / 1000 % 100_000_000
        Diag.report(v.context, "[trace] '$txt' t=$now us=$ns L=${dl?.layerType} a=${dl?.alpha} s=${dl?.scaleX} k=${if (ScreenSpan.of(v, spanT)) spanT[2] else -1f}")
    }
    private val spanT = FloatArray(3)

    private fun trackMove() {
        if (applied?.batch != false) return   // только для стекла по содержимому (BATCH_NONE)
        try { trace() } catch (_: Throwable) {}
        try { reportAncestors() } catch (_: Throwable) {}
        try { dropAncestorLayers() } catch (_: Throwable) {}
        try { updateRegion(applied) } catch (_: Throwable) {}
        v.getLocationInWindow(loc)
        // своё смещение (clamp у цифр) не считаем движением, иначе стекло переприменяется каждый кадр.
        // v0.21: translationX — в локальных px, а виджет в лаунчере уменьшен → вычитаем в экранных (ScreenSpan)
        if (ScreenSpan.of(v, span)) {
            loc[0] = Math.round(span[0])
            // v0.26: при выезде Jovi лаунчер анимирует МАСШТАБ DragLayer (1 → 0.99). Форма стекла по содержимому
            // считается при применении стекла и при сдвиге остаётся верной, а при смене масштаба съезжает → полосы,
            // пока не переприменим (раньше — через 200 мс после остановки). Переприменяем сразу, в этом же кадре.
            // v0.27: сравниваем с масштабом, при котором стекло применено (appliedK), а не с прошлым кадром — иначе мелкие
            // шаги анимации (< порога) копились и кадр с полосами проскакивал.
            val k = span[2]
            if (appliedK > 0f && kotlin.math.abs(k - appliedK) > 2e-5f) ensure("scale", force = true, quiet = true)
        } else loc[0] -= v.translationX.toInt()
        loc[1] -= v.translationY.toInt()
        if (loc[0] != lastX || loc[1] != lastY) {
            val first = lastX == Int.MIN_VALUE
            if (!settlePending) moveFrom = "$lastX,$lastY"
            lastX = loc[0]; lastY = loc[1]
            if (!first) {
                v.post { v.invalidate() }
                v.removeCallbacks(settle)
                settlePending = true
                v.postDelayed(settle, 200)
            }
        }
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        try { trackMove() } catch (_: Throwable) {}
        try {
            if (cfgDesc() != cfgText) {
                v.post { ensure("config") }
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - lastCheck >= CHECK_MS) {
                    lastCheck = now
                    ensure("frame")
                } else if (!trailingCheck) {
                    trailingCheck = true
                    v.postDelayed({ trailingCheck = false; lastCheck = SystemClock.uptimeMillis(); ensure("trailing") }, CHECK_MS)
                }
            }
        } catch (_: Throwable) {
        }
        true
    }

    /** v0.19: смена contentDescription без перерисовки (тот же цвет текста и т.п.) не вызывает preDraw —
     *  конфиг «опаздывал» до следующего кадра (смена минуты, листание). Поэтому ещё и опрос раз в 300 мс. */
    private var attached = false
    private val poll = object : Runnable {
        override fun run() {
            try { if (cfgDesc() != cfgText) ensure("config") } catch (_: Throwable) {}
            if (attached) v.postDelayed(this, 300)
        }
    }

    // --- жизненный цикл (вызываются из view) ---
    fun onAttached() = safe("attach") {
        v.viewTreeObserver.addOnPreDrawListener(preDraw)
        v.post { ensure("attach", force = true) }
        attached = true
        v.removeCallbacks(poll)
        v.postDelayed(poll, 300)
    }

    fun onDetached() {
        attached = false
        lastX = Int.MIN_VALUE; lastY = Int.MIN_VALUE
        try { v.removeCallbacks(poll); v.removeCallbacks(settle) } catch (_: Throwable) {}
        try { v.viewTreeObserver.removeOnPreDrawListener(preDraw) } catch (_: Throwable) {}
        lastGroup = null
        lastApplied = null
        lastRegion = ""
    }

    fun onVisible() = safe("visible") { v.post { ensure("visible", force = true) } }
    /** Переприменить стекло (например, сменилась форма view — новые цифры). */
    fun reapply(reason: String) = safe(reason) { v.post { ensure(reason, force = true) } }
    private var lastW = 0
    private var lastH = 0
    fun onSized(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == lastW && h == lastH)) return
        lastW = w; lastH = h
        safe("size") { v.post { ensure("size", force = true) } }
    }

    // --- внутреннее ---
    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Diag.report(v.context, "[$host] $what failed: $t") }
    }

    private fun m(name: String, vararg types: Class<*>): Method? {
        val key = name + types.joinToString(",") { it.name }
        return methods.getOrPut(key) {
            try { View::class.java.getMethod(name, *types) } catch (t: Throwable) { null }
        }
    }

    private fun call(log: StringBuilder, name: String, types: Array<Class<*>>, vararg args: Any): Any? {
        val mm = m(name, *types) ?: run { log.append(" !").append(name).append(":missing"); return null }
        return try { mm.invoke(v, *args) } catch (t: Throwable) {
            log.append(" !").append(name).append(':').append(t.javaClass.simpleName); null
        }
    }

    private fun inGroup(): Boolean {
        val get = m("getLiquidGlassBatchType") ?: return false
        var p = v.parent
        var depth = 0
        while (p is View && depth < 20) {
            if ((try { get.invoke(p) } catch (t: Throwable) { 0 } as? Int ?: 0) == BATCH_GROUP) return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun currentCfg(): GlassCfg? {
        val txt = cfgText
        if (txt != null && txt.contains("recipe=")) {
            val map = txt.split(';').mapNotNull { kv ->
                val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
            }.toMap()
            val ct = map["ct"]?.toIntOrNull() ?: 2
            // в лаунчере contentType 1 (HW underlay) на месте не рисуется — вместо него ct 2 (3 и 4 пропускаем для опытов)
            return GlassCfg(
                map["recipe"] ?: "plain", map["style"]?.toIntOrNull() ?: 0, if (isLauncher && ct == 1 && map["recipe"] != "liquid") 2 else ct,
                batch = map["batch"] != "0", clip = map["clip"] == "1", radiusDp = map["radius"]?.toFloatOrNull(),
                outline = map["outline"] == "1", cpath = map["cpath"] == "1", ialpha = map["ialpha"] == "1",
                region = map["region"]?.toIntOrNull() ?: 0, fcr = map["fcr"] == "1", hide = map["hide"] != "0",
                clamp = map["clamp"] == "1", clampM = map["cm"]?.toIntOrNull() ?: 0,
                clampR = map["cr"]?.toIntOrNull(), clampRight = map["rc"] != "0", rightBySize = map["rs"] == "1", xs = map["xs"]?.toIntOrNull() ?: 0, edge = map["edge"] == "1", tx = map["tx"]?.toIntOrNull() ?: 0,
                gid = map["gid"]?.toIntOrNull(), lrot = map["lrot"]?.toFloatOrNull(),
                vibStyle = map["vib"]?.toIntOrNull(), vibType = map["vt"]?.toIntOrNull() ?: 0,
                lg = map["lg"]?.toFloatOrNull() ?: 1f, rim = map["rim"]?.toIntOrNull(), iter = map["iter"]?.toIntOrNull()
            )
        }
        return defaultCfg(isLauncher)
    }

    /** quiet — не слать отчёт (переприменение каждый кадр при смене масштаба). */
    private fun ensure(reason: String, force: Boolean = false, quiet: Boolean = false) {
        try {
            cfgText = cfgDesc()
            val group = inGroup()
            val cfg = currentCfg()
            val key = "$group|$cfg"
            if (!force && group == lastGroup && key == lastApplied) return
            lastGroup = group
            lastApplied = key
            apply(reason, group, cfg, quiet)
        } catch (t: Throwable) {
            Diag.report(v.context, "[$host] ensure failed: $t")
        }
    }

    private fun apply(reason: String, group: Boolean, cfg: GlassCfg?, quiet: Boolean = false) {
        val log = StringBuilder()
        try {
            applied = cfg
            try { if (ScreenSpan.of(v, spanA)) appliedK = spanA[2] } catch (_: Throwable) {}
            try { onApplied?.invoke(cfg) } catch (t: Throwable) { log.append(" !onApplied:").append(t.javaClass.simpleName) }
            call(log, "setLiquidGlassBatchType", arrayOf(I), if (group && cfg?.batch != false) BATCH_CHILD else BATCH_NONE)
            var ok: Any? = null
            if (cfg != null) {
                call(log, "clearMaterial", arrayOf())
                ok = call(log, "setMaterial", arrayOf(I, I), cfg.style, cfg.ct)
                if (!cfg.batch && cfg.lg > 0f && (cfg.recipe == "clear" || cfg.recipe == "folder")) {
                    // v0.19: вне пакета лаунчера форму стекла даёт содержимое view (альфа ≥ 0.5) — но только при
                    // включённом жидком стекле по содержимому, как у цифр экрана блокировки (clearMaterial его не сбрасывает)
                    call(log, "setLiquidGlassEffect", arrayOf(F), cfg.lg)
                }
                // v0.23: clearMaterial не сбрасывает жидкое стекло и, похоже, blur iteration (после «Инея» цифры
                // оставались с mBlurLevel=4). Сбрасываем то, что поставил прошлый конфиг, а этот — нет.
                if (!cfg.batch && cfg.recipe == "plain") call(log, "setLiquidGlassEffect", arrayOf(F), 0f)
                val iterNow = cfg.iter ?: if (cfg.recipe == "folder") 1 else null
                if (iterNow == null && hadIter) call(log, "chooseMaterialBlurIteration", arrayOf(I), 0)
                hadIter = iterNow != null
                if (cfg.recipe == "folder" || cfg.recipe == "clear") {
                    // рецепт стекла папок лаунчера (folder); clear — то же без мути и тона
                    call(log, "enableMaterialG2Style", arrayOf(Z), false)
                    call(log, "setMaterialFPS", arrayOf(I), 120)
                    if (cfg.recipe == "folder") call(log, "chooseMaterialBlurIteration", arrayOf(I), 1) // лёгкая муть
                    call(log, "setGlassEffectCustomized", arrayOf(F, F), 140f, 60f)              // преломление
                    call(log, "setGlassEffectInnerStrokeLight", arrayOf(I, F, F, F, F, I, F),     // ободок
                        -1, 0.35f, 0f, 1f, 1f, 4, 0.25f)
                    val g = when (val c = cfg.gid) { null -> 3; -1 -> 100 + (System.identityHashCode(v) % 10000); else -> c }
                    call(log, "setMaterialGroupID", arrayOf(I), g)
                    cfg.lrot?.let { call(log, "setGlassEffectInnerStrokeLightRotation", arrayOf(F), it) }
                    call(log, "setMaterialAlpha", arrayOf(F), 1f)
                    call(log, "setMaterialForceUpdateBg", arrayOf(Z), false)
                    if (cfg.recipe == "folder") call(log, "setBlurDynamicColorParams", arrayOf(I), 2) // тон 10% под обои
                }
                if (cfg.recipe == "liquid") {
                    // как GlassApplier часов экрана блокировки (SystemUIPlugin): жидкое стекло по содержимому view
                    cfg.iter?.let { call(log, "chooseMaterialBlurIteration", arrayOf(I), it) }
                    call(log, "setLiquidGlassEffect", arrayOf(F), cfg.lg)
                    cfg.rim?.let {
                        try {
                            v.javaClass.getMethod("setLiquidGlassRimLight", Integer::class.java, java.lang.Float::class.java)
                                .invoke(v, Integer.valueOf(it), null)
                        } catch (t: Throwable) { log.append(" !rim:").append(t.javaClass.simpleName) }
                    }
                }
                call(log, "setMaterialDrawOnHwUnderlay", arrayOf(Z), cfg.ct == 1)
                call(log, "enableMaterialClipWhenBlend", arrayOf(Z), cfg.clip)
                call(log, "setImageAlphaInBlurAlgorithm", arrayOf(Z), cfg.ialpha)
                if (cfg.fcr) call(log, "setMaterialForceCheckRegion", arrayOf(Z), true)
                if (cfg.vibStyle != null) {
                    val r = call(log, "setVibrancyEffect", arrayOf(I, I), cfg.vibStyle, cfg.vibType)
                    log.append(" vib=").append(r)
                } else if (hadVibrancy) {
                    call(log, "clearVibrancyEffect", arrayOf(I), 0)
                }
                hadVibrancy = cfg.vibStyle != null
                (cfg.radiusDp ?: cornerRadiusDp)?.let {
                    call(log, "setMaterialCornerRadius", arrayOf(F), it * v.resources.displayMetrics.density)
                }
            }
            try { updateRegion(cfg, force = true) } catch (_: Throwable) {}
            v.invalidate()
            if (!quiet || log.isNotEmpty()) Diag.report(v.context, "[$host] ${v.javaClass.simpleName} $reason group=$group cfg=$cfg material=$ok " +
                    "size=${v.width}x${v.height}" + (if (log.isEmpty()) "" else " errors:$log"))
        } catch (t: Throwable) {
            Diag.report(v.context, "[$host] apply failed: $t")
        }
    }
}

/**
 * Отчёты из процесса хоста в наш DiagReceiver (логи лаунчера/Jovi vivo глушит).
 * v0.25: Android 14+ замораживает фоновое приложение, и broadcast'ы от лаунчера до DiagReceiver не доходят.
 * Поэтому ещё и пишем в файл в cache того процесса, где исполняется код (лаунчер: /data/user/0/com.bbk.launcher2/
 * cache/glassdiag.log) — читается через root/run-as. Для работы часов не нужен.
 */
object Diag {
    /** Подробный отчёт о предках (альфа/слой/масштаб) каждый кадр — включать только для разбора. */
    const val ANCESTORS = false
    /** Покадровая трассировка preDraw в начале переходов лаунчера — только для разбора. */
    const val TRACE = false
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    private val file: java.io.File? by lazy {
        try {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            java.io.File(app.cacheDir, "glassdiag.log")
        } catch (_: Throwable) { null }
    }

    fun report(c: Context, msg: String) {
        try {
            c.sendBroadcast(
                Intent(DiagReceiver.ACTION)
                    .setComponent(ComponentName("com.vivo.widget.widgetsdk_inner_demo", DiagReceiver::class.java.name))
                    .putExtra("msg", msg)
            )
        } catch (_: Throwable) {
        }
        val line = time.format(java.util.Date()) + " " + msg + "\n"
        try {
            io.execute {
                try {
                    val f = file ?: return@execute
                    if (f.length() > 2_000_000) f.delete()
                    f.appendText(line)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
