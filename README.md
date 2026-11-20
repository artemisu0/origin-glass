# Test Glass Vivo

## Цель
Сделать свой виджет для рабочего стола vivo X200 Ultra (OriginOS 7, Android 17, китайская прошивка)
с НАСТОЯЩИМ системным эффектом жидкого стекла — как у виджетов из «Комплектов Origin» (часы, календарь).
Условия: без рута (на этом vivo он невозможен), без фейкового стекла по обоям.

## Статус (06.11.2026)
- Разобран лаунчер vivo (com.bbk.launcher2) через adb — найден механизм Origin-виджетов.
- v0.1 (только метки Origin, как у XTC) — на телефоне СТЕКЛА НЕТ. Виджет попал в хост 1026.
- Разобрал launcher.apk через dexdump (Android SDK) — нашёл, что на самом деле включает стекло (см. «Разбор лаунчера» ниже).
- v0.2: три виджета — «Glass test» (контроль), «Glass material», «Glass blur». На обычных страницах рабочего стола стекла НЕТ ни у одного.
- ГЛАВНОЕ: календари vivo с этого телефона привязаны НЕ к лаунчеру, а к HiBoard (com.vivo.hiboard, hostId 2048) — это левая страница «=».
  У лаунчера (хосты 1024/1026) нет ни одного календарного виджета — ни сейчас, ни в дампе первого чата.
  Сами виджеты календаря — обычные AppWidgetProvider + RemoteViews, только с доп. meta-data vivo.
- HiBoard (HiBoardAppWidgetHostView, ca0.y.s) даёт виджету блюр/стекло, если:
  `vivo.support.motionblur.widget = true` ИЛИ (`vivo_widget = true` И `vivo.support.material.widget = true`). Проверки пакета не нашёл.
  Настройка телефона: hiboard_card_blur_style=14.
- Следующий тест: добавить «Glass blur» (v0.2, motionblur=true) на страницу HiBoard «=».
  Собственное меню Jovi «добавить» показывает только «Комплекты Origin». Но есть второй путь — перетаскивание из списка виджетов лаунчера:
  - лаунчер объявляет vivo.launcher.support.widget.drag_to_hiboard=1 и vivo.launcher.suport.moveto.hiboard=true;
  - HiBoard принимает drag с меткой "launcher_widget_store_drag" (ui.drag.q.B, «drag event from widget store»);
  - его WidgetsQueryProvider.isSupportAddWidget отвечает true для ЛЮБОГО виджета, если secure hiboard_support_widget ≥ 1
    (на телефоне = 1), виджета нет в чёрном списке (t80.b.o()) и всего виджетов < 20. Проверки на Origin-комплект там нет.
  - Сам провайдер из adb не спросить: permission query_added_widgets = signature|privileged.
- Проверено на телефоне: перетаскивание «Glass blur» (пакет com.artemiy.glasstest) из списка виджетов →
  «Виджеты приложений не поддерживаются на Умном рабочем столе» (string/toast_smart_desktop_not_support_app_widget).
  Код: dragndrop.g.n0() → тип 2, если rb.d.t(): p5.d.d(cn) == 3. Классификатор p5.d.d:
  1 = u1.E0 (origin_widget + пакет com.vivo*/com.bbk* или системная подпись); 2 = ui_version ≥ 2.0 И пакет в белых списках
  f6.a.f1/L0/e1 (u1.v0, p5.d.i); 3 = остальное → отказ. Дальше ещё: размер 2×2 или ширина 4 (rb.d.q), не в чёрном
  списке Jovi / не часы-погода (rb.d.x).
- v0.3 (06.11.2026): тот же APK, но applicationId = com.vivo.artemiy.glasstest (приложение «Glass vivo»). Установился (adb), vivo префикс не блокирует.
  РЕЗУЛЬТАТ: «Glass blur» перетащился в Jovi; при долгом нажатии HiBoard рисует его превью стеклянным, в обычном виде — без стекла.
  «Glass test» стал появляться в «Комплектах Origin».

## Как на самом деле устроены виджеты календаря (06.11.2026)
- Это НЕ обычные RemoteViews. Разметка — собственные классы календаря (Event4x2RootViewLayout extends WidgetBlurBgRootView,
  ConstraintLayout, ViewPager2…). Их код исполняется ВНУТРИ хоста (HiBoard/лаунчер) и сам ставит стекло через
  OriginUI VBlurUtils → скрытый View.setMaterial(style, contentType).
- Разрешает это прошивка: vivo-framework com.vivo.common.widget.AppWidgetContextImpl.newWidgetContext() даёт
  createApplicationContext(..., INCLUDE_CODE|IGNORE_SECURITY) только пакетам из списков:
  mWidgetsList (зашит, ~45 пакетов vivo) + mCustomerWidgetsList (хост передаёт через AppWidgetHost.onUpdateVivoWidgetCustomerWhiteList).
  HiBoard передаёт свой список ca0.z.a — в нём есть ДЕМО-пакеты SDK: com.vivo.originwidgetdemo,
  com.vivo.widget.widgetsdk_inner_demo, com.bbk.widget.common.sdk_widget_demo, com.example.tjktest.widgetdemoapp (на телефоне свободны).
- HiBoard для виджетов с motionblur вызывает у корня setMaterialDrawOnHwUnderlay(true) — но сам материал не задаёт.

## Главное: стекло можно включить из ОБЫЧНОГО RemoteViews
- В framework.jar (classes4.dex) vivo пометила методы View как @RemotableViewMethod (все public):
  setMaterial(Bundle/II/III), clearMaterial(Bundle/I/()), setMaterialDrawOnHwUnderlay(Z), setMaterialCornerRadius(F/FFFF/…/Bundle),
  setLiquidGlassEffect(F), setGlassEffectCustomized, setMaterialAlpha, setMaterialBlurRadius и ещё ~90.
- Ключи Bundle для setMaterial: "style", "contentType" (IVivoBlurRender.BlurGeometry). setLiquidGlassEffect работает только после setMaterial.
- Партнёрский SDK Umetrip внутри HiBoard так и делает (VibrancyEffectUtil.setVibrancyEffect):
  setBundle(id,"clearMaterial",Bundle()); setBundle(id,"setMaterial",{style:0, contentType}); если contentType==1 → setBoolean(id,"setMaterialDrawOnHwUnderlay",true).
- v0.4 (06.11.2026, установлен): «Glass material» = рецепт Umetrip (style 0, contentType 1); «Glass blur» = он же + setMaterialCornerRadius(24dp) +
  setLiquidGlassEffect(1.0); «Glass test» = контроль.
  РЕЗУЛЬТАТ v0.4: стекло ЗАРАБОТАЛО, но легло на ТЕКСТ (буквы стали стеклянными), а не подложкой; при перетаскивании на рабочем столе
  видна стеклянная плашка с преломлением; виджет иногда пропадал целиком. Причина: у корня не было фона — материал берёт форму
  из того, что рисует view.
- Как у календаря: материал вешается на КОРЕНЬ (WidgetBlurBgRootView), у корня есть фон blur_lightmode_black_background
  (layer-list #6E000000 + #2C000000, без углов — углы обрезает хост). Параметры календарь берёт у хоста через рефлексию
  AppWidgetHostView.getViewAttribute() → "widgetBlurValues" (String[]: [0] style, [1] contentType, [2] bool, [3] int,
  [4..14] setMaterialCustomized, [15],[16] style, [17..27] параметры жидкого стекла). HiBoard даёт style 0, contentType 1 (если HW underlay) или 2.
- v0.5 (06.11.2026, установлен): у корня фон glass_bg (как у календаря + углы 24dp); «Material v0.5» = рецепт + setMaterialCornerRadius(24dp);
  «Blur v0.5» = + setLiquidGlassEffect(1.0); «Control v0.5» = только фон.
  РЕЗУЛЬТАТ v0.5 (светлые обои): Material — тёмная плашка, слабый блюр, не как папки; Blur — на месте почти исчезает
  (видны обои), при перетаскивании — плашка с преломлением.
- style в setMaterial — номер стиля BlurMaterial (vivo-framework, findStyle): 2–11, 51–59, 101–113, 151–163, 201–215, 251–265,
  301–303, 311–313, 321–323, 331, 351–353, 361–363, 371–373, 381, 401–434. 409–434 — стили ЖИДКОГО СТЕКЛА
  (getGlassEffectParamsByStyle: need_glass_effect, refraction_range/intensity, light_color…). Лаунчерный SDK
  com.spacesystem.materialsdk.Material.n выбирает вариант из 409–434 по теме (LIGHT/DARK) и силе (STRONG) — четвёрки
  (429,430,431,432), (425–428), (421–424), (414,418,420…).
- v0.6 (06.11.2026, установлен): виджет «Glass lab» — ◀/▶ перебирают стили, M — contentType 1/2 × жидкое стекло, BG — подложка
  clear/light/dark; на виджете написан текущий стиль. Цель — найти стиль, похожий на стекло папок.
  РЕЗУЛЬТАТ v0.6: style 420 · ct 1 · plain · light — в Jovi стекло НА МЕСТЕ (как карточки календаря); на рабочем столе
  настоящее жидкое стекло с преломлением только при ПЕРЕТАСКИВАНИИ, на месте — просто светлая подложка.
- Причина на рабочем столе: лаунчер рисует жидкое стекло ПАКЕТОМ. ShortcutAndWidgetContainer помечен
  View.setLiquidGlassBatchType(GROUP=1), стеклянные элементы (папки, OriginFolderIconBlurView) — CHILD=2 (c9.c.S, dm.l.C).
  Наш view = NONE(0) → внутри группы стекло не рисуется. setLiquidGlassBatchType НЕ remotable — из RemoteViews не вызвать.
- Решение «как у календаря» (v0.7): свой код view внутри хоста. vivo RemoteViews.initCustomWidgetState → isCustomContextWidget(pkg)
  → грузит классы из APK виджета, если пакет в белом списке. Белые списки: лаунчер f6.a.a1 (= getLauncherCustomViewWidgetPkgList),
  Jovi ca0.z.a. Общие демо-имена SDK: com.vivo.widget.widgetsdk_inner_demo, com.bbk.widget.common.sdk_widget_demo,
  com.example.tjktest.widgetdemoapp (+ лаунчер: com.bbk.widget.common.test; Jovi: com.vivo.originwidgetdemo).
- v0.7 (06.11.2026, установлен): applicationId = com.vivo.widget.widgetsdk_inner_demo, приложение «Glass Origin».
  Корень разметки — com.artemiy.glasstest.GlassRootView: в onAttachedToWindow ищет предка с batch GROUP и ставит себе CHILD
  (рефлексия, всё в try/catch — код в процессе лаунчера), пишет в logcat тег GlassRootView. Стекло — по-прежнему вызовами RemoteViews.
  Виджеты: «Glass» (style 420, светлая подложка) и «Glass lab» (старт с 420). Старое приложение com.vivo.artemiy.glasstest не трогал.
  РЕЗУЛЬТАТ v0.7: оба виджета — белая карточка-заглушка «загрузка» и на рабочем столе, и в Jovi (view не построился).
- Причина: белый список vivo лишь даёт контекст с нашим кодом (createApplicationContext INCLUDE_CODE), а фильтр LayoutInflater
  в RemoteViews стандартный — пропускает только классы с аннотацией @android.widget.RemoteViews.RemoteView.
  У календаря (Event4x2RootViewLayout, WidgetBlurBgRootView) она стоит. У нашего GlassRootView не было → InflateException.
- Логи лаунчера и HiBoard vivo глушит полностью (0 строк от их pid), поэтому с v0.8 GlassRootView шлёт отчёты broadcast'ом
  в наш DiagReceiver → logcat тег GlassDiag (видно из нашего процесса).
- v0.8 (06.11.2026): + @RemoteViews.RemoteView на GlassRootView, + DiagReceiver. APK собран, аннотация в dex есть.
  Установлен (со второй попытки). Отчёт из лаунчера (logcat GlassDiag):
    [com.bbk.launcher2] ctor in host process
    [com.bbk.launcher2] GROUP at depth 2 -> CHILD result=true; chain: LauncherAppWidgetView=0 LauncherAppWidgetHostView=0 ShortcutAndWidgetContainer=1
  → наш код исполняется в лаунчере и встаёт в пакет жидкого стекла как папки.
  РЕЗУЛЬТАТ v0.8 (рабочий стол): ct 1 — на месте стекла нет (HW underlay в лаунчере не рисуется); ct 2 — стекло на месте
  ПОЯВИЛОСЬ (ободок, жидкое стекло), но формой стал весь корень: текст и кнопки стали «стеклянными» и почти пропали.
- Как у папок: стекло висит на ОТДЕЛЬНОМ фоновом view без содержимого (OriginFolderIconBlurView), иконки — соседи сверху.
- v0.9 (06.11.2026): слой com.artemiy.glasstest.GlassBgView (@RemoteView) под содержимым. tag="auto" (виджет «Glass»):
  сам в хосте ставит стекло — в лаунчере batch CHILD + setMaterial(420, ct 2), в Jovi setMaterial(420, ct 1) + HW underlay,
  + setMaterialCornerRadius(24dp). В «Glass lab» слой lab_glass без tag — материал от кнопок через RemoteViews, view только CHILD.
  Текст/кнопки — соседи над слоем. Установлен (21:19). Отчёты из лаунчера:
    [com.bbk.launcher2] bg auto=false inGroup=true(depth 3) batch=true material by RemoteViews   (Glass lab)
    [com.bbk.launcher2] bg auto=true inGroup=true(depth 3) batch=true style=420 ct=2 material=true size=673x673   (Glass)
  РЕЗУЛЬТАТ v0.9 (рабочий стол): «Glass» — НАСТОЯЩЕЕ матовое стекло на месте (фон размыт, текст обычный).
  Glass lab на ct 2 (style 421) — тоже стекло на месте; на ct 1 — нет. Но вид — матовый блюр, а у папок жидкое стекло
  с преломлением по краям и светлым ободком.

## Рецепт стекла папок лаунчера (06.11.2026)
- Пресеты в c9.c: C = папки (FolderIcon.Ub), x = перетаскиваемый объект (dragndrop.j), Q = SuggestWidgetView,
  M = стопки виджетов, F = индикатор/поиск, K = попапы. Режим c9.c.o(): 0 → d9.e, 1 → d9.c (жидкое стекло), 2 → d9.a.
- Папка, режим 1: d9.c.g → d9.c.v(a, 2, radius, 3, rect) → dm.f → dm.l.A → materialsdk.b:
    setMaterial(0, 2)                       (L)
    enableMaterialG2Style(false)            (w)
    setMaterialFPS(120)                     (R)
    chooseMaterialBlurIteration(1)          (u)
    setGlassEffectCustomized(140f, 60f)     (H)  ← преломление
    setGlassEffectInnerStrokeLight(0xFFFFFFFF, 0.35f, 0f, 1f, 1f, 4, 0.25f)  (J) ← ободок
    setMaterialCornerRadius(r)              (N)
    setMaterialGroupID(3)                   (T)
    setMaterialAlpha(1f)                    (M)
    setMaterialForceUpdateBg(false)         (S)
    setMaterialDrawOnHwUnderlay(false)      (Q)
    setMaterialRegion(rect)                 (U, если есть)
    setBlurDynamicColorParams(2)            (G)
- v0.10 (06.11.2026): GlassBgView (tag auto) в лаунчере применяет этот рецепт напрямую рефлексией (наш код в хосте —
  можно и многоаргументные методы); в Jovi — как в v0.9 (420, ct 1). Отчёт: «recipe=folder … errors: …».
  APK собран, рецепт в dex есть. Установлен 21:29:05, но отчётов GlassDiag нет ~1.5 мин — видимо, лаунчер не пересоздал view
  (переприменил RemoteViews к старому). Чтобы включился новый код — удалить виджет и добавить заново.
  РЕЗУЛЬТАТ v0.10: «Glass» — НАСТОЯЩЕЕ ЖИДКОЕ СТЕКЛО как у папок (преломление + ободок). Баги: при долгом нажатии эффект пропадает;
  Glass lab ломается от касаний рабочего стола.

## v0.11–0.13 (06.11.2026) — стабильность
- Хост определяется по имени процесса (com.bbk.launcher2 → рецепт папок, com.vivo.hiboard → 420/ct 1), а не по наличию GROUP.
- GlassBgView сам поддерживает стекло: OnPreDrawListener — смена конфига проверяется на каждом кадре, обход предков (GROUP)
  раз в 250 мс + догоняющая проверка; при изменении — сброс в два кадра (clearMaterial, HW underlay off, batch NONE →
  следующий кадр: CHILD + рецепт). В лаунчере ct 1 не применяется никогда (иначе после него рецепт давал молочную плашку).
- Glass lab: параметры уходят в слой через RemoteViews.setContentDescription(lab_glass, "recipe=…;style=…;ct=…").
  НЕЛЬЗЯ переопределять setContentDescription в своём классе: у переопределения нет @RemotableViewMethod → RemoteViews
  не может его вызвать → весь виджет белая заглушка (так было в v0.11).
- Проверено на телефоне (adb: скриншоты + тапы): «Glass» и «Glass lab» держат стекло после тапов, листания страниц, меню
  приложений, режима редактирования; лаборатория — по всем режимам M и стилям. В v0.13 вид как у папок: фон размыт + преломление по краю.
- Долгое нажатие: лаунчер ПРЯЧЕТ живой виджет (LauncherAppWidgetHostView = INVISIBLE) и показывает снимок-превью —
  стекла на нём нет. Папка при долгом нажатии остаётся стеклянной (лаунчер поднимает её саму). Не исправлено.
- Стили: в режиме folder номер стиля почти не влияет (вид задают setGlassEffectCustomized/InnerStrokeLight);
  plain-стили 2–11 и т.п. — обычный блюр, часть почти непрозрачные (style 6 — по сути заливка цветом).
## v0.14–0.15 (06.11.2026) — прозрачность и долгое нажатие
- Параметры стекла видны в `adb shell dumpsys activity top` (строка BlurGeometry под каждым view с материалом).
  У папок/дока: mBlurRadius=0.02, saturation=40, маска1 чёрная 35%, маска2 #bfbfbf 8%, blurLevel=4, colorOpacity1=0.1,
  refraction 140/60, light 0.45 — тон и муть даёт динамический цвет (materialsdk.a / setBlurDynamicColorParams).
- Наш рецепт folder: chooseMaterialBlurIteration(1) → blurLevel 1 (муть), setBlurDynamicColorParams(2) → colorOpacity1 0.1
  (тон под обои, «то темновато, то беловато»), плюс наша подложка #33FFFFFF (белёсость).
- Режим clear (по умолчанию у «Glass»): рецепт папок БЕЗ chooseMaterialBlurIteration и БЕЗ setBlurDynamicColorParams,
  подложка glass_bg_none (#01FFFFFF — форма есть, цвета нет) → прозрачное бесцветное жидкое стекло (преломление + ободок).
  Если «Glass» кажется тёмным — это просто тёмные обои за ним (параметры идентичны лаборатории).
- Долгое нажатие: лаунчер запускает системный drag-and-drop (для переноса в Jovi); превью — bitmap-снимок через программный
  canvas, живое стекло туда не попадает. v0.15: GlassBgView.draw() в программном canvas рисует имитацию плашки
  (заливка 10% белого + ободок 1.5dp 45% белого, радиус 24dp) — в меню зажатия и при перетаскивании виджет выглядит
  стеклянной плашкой, после отпускания — снова живое стекло. Проверено скриншотами.
- Glass lab (v0.14+): M = clear / folder / plain ct2 / plain ct1; BG = none / light / dark.
## v0.16–0.18 (06.11.2026) — первый настоящий виджет: «Стеклянные часы»
- «Стеклянные часы» 2×2 (clock_2x2) и 4×2 (clock_4x2): GlassBgView (tag auto → clear) + TextClock (время HH:mm,
  дата «EEEE, d MMMM» по локали; тикает сам, без сервиса). Тап — AlarmClock.ACTION_SHOW_ALARMS.
- Долгое нажатие по пустому месту открыло карточку «Комплекты Origin» для Glass Origin с меткой 推荐 и кнопкой
  «Добавить комплект» — лаунчер считает наши виджеты Origin-виджетами (пакет com.vivo* + origin_widget).
- Тёмный тон стекла (~30% темнее фона) появился в v0.13 из-за двухкадрового сброса (clear/NONE → кадр → CHILD/рецепт).
  Замер по скриншотам: v0.12 внутри светлее фона (177 vs 146), v0.14 темнее (98 vs 146). Группа материала 3 не виновата
  (v0.17 проверял). v0.18: снова порядок v0.12 в один кадр (batch → clearMaterial → setMaterial → рецепт) → стекло
  прозрачное (219 vs 199). Стабильность сохранилась: в лаунчере ct всегда 2; прогнал лабораторию по всем режимам.
- plain + style 0 ничего не рисует (нет ни блюра, ни эффекта стекла) — это нормально.
- Тест-кухня: vivo глушит логи лаунчера → отчёты GlassDiag идут broadcast'ом в наше приложение, его процесс должен быть жив
  (am start …/com.artemiy.glasstest.MainActivity, потом HOME). adb install иногда требует подтверждения на телефоне.
- Почему лаунчер не дал стекло на обычных страницах — не выяснено до конца. У него в m4.j.p есть глобальные условия
  (isGlassMaterialEffect = c9.c.s(), isSupportDynamicBlur). Подозрительные настройки: secure pref_time_shadow_switch=0
  («Time shadow», переключатель в настройках значков лаунчера), secure vivo.widget.material.launcher.alpha=0.0.

## v0.19 (07.11.2026) — «Стеклянное время»: цифры САМИ из жидкого стекла
- Виджеты «Стеклянное время» 4×2 (glasstime_4x2) и 2×2 (glasstime_2x2, часы над минутами): GlassTextClock
  (@RemoteView, TextClock в процессе хоста) со шрифтом Dela Gothic One (res/font, OFL 1.1, взят из Download/Fonts на
  телефоне), автоподбор размера, фона нет. Тап — будильники. Логика стекла вынесена в GlassCore.kt (GlassHelper).
- РЕЦЕПТ (работает, проверено скриншотами, обновляется каждую минуту): view НЕ в пакете лаунчера (setLiquidGlassBatchType
  NONE) + clearMaterial + setMaterial(0, ct 2) + setLiquidGlassEffect(1.0) + рецепт clear (setGlassEffectCustomized 140/60,
  InnerStrokeLight, FPS 120, группа 3, alpha 1) + НЕПРОЗРАЧНЫЙ текст (#FFFFFFFF). Белый цвет не виден — стекло его заменяет.
- Почему так: libhwui строит форму жидкого стекла из АЛЬФЫ содержимого view — SDF методом JFA (шейдер с
  boundaryTexture/closestPointTexture, «внутри» = альфа ≥ 0.5). Поэтому текст #01 давал пустую маску.
  В пакете лаунчера (CHILD, как у папок) стекло — только скруглённый прямоугольник по границам view (SDF sdRoundedRect),
  форму оттуда не задать ни outline-path, ни clipPath, ни enableMaterialClipWhenBlend.
- Подсказка пришла от часов экрана блокировки: SystemUIPlugin keyguard/time/material/applier/GlassApplier вешает стекло
  прямо на TextView цифр: setMaterial(0, ct), chooseMaterialBlurIteration(4), setMaterialCustomized(blur, 10, цвет…),
  setLiquidGlassEffect(1), setLiquidGlassRimLight, setVibrancyEffectUseNearest; цвет текста непрозрачный.
- Рецепт liquid (только setLiquidGlassEffect, без clear-параметров) → матовые серые стеклянные цифры, как на локскрине.
- Не сработало: vibrancy (View.setVibrancyEffect — цифры просто исчезают), setImageAlphaInBlurAlgorithm,
  contentType 3/4, ct 1 в лаунчере, outline/clip в пакете.
- ЛОВУШКА опытов: лаунчер применял новый contentDescription только при перерисовке view (preDraw). Если цвет текста не
  менялся, конфиг «опаздывал» до смены минуты → скриншоты показывали старое состояние. v0.19: GlassHelper ещё и опрашивает
  contentDescription раз в 300 мс. clearMaterial НЕ сбрасывает setLiquidGlassEffect — порядок опытов влиял на результат.
- Отладка без касаний экрана (провайдеры часов): adb shell "am broadcast -n com.vivo.widget.widgetsdk_inner_demo/
  com.artemiy.glasstest.GlassTimeWideWidget -a com.artemiy.glasstest.GLASS_DEBUG --ei appWidgetId N --es desc
  'recipe=clear;style=0;ct=2;batch=0' --es color FFFFFFFF"; применение проверять через uiautomator dump (content-desc).
- Листание страниц (проверено удержанием пальца: adb shell input motionevent DOWN/MOVE/UP):
  - пока страницу тянут, лаунчер ставит ShortcutAndWidgetContainer АППАРАТНЫЙ слой (layerType 2) — внутри слоя стекло
    по содержимому ломается (видна верхняя половина цифр). GlassHelper снимает такой слой с ближайших предков
    (setLayerType NONE) — стеклу папок это не мешает. Теперь при удержании со сдвигом до ~30 px цифры целые;
  - после остановки (200 мс без движения) — invalidate + переприменение стекла;
  - если view уходит за край экрана сильнее (~90 px+), HWUI сам выключает стекло и рисует просто текст (белые цифры)
    до возврата страницы. Смягчить не удалось: смена цвета текста изнутри view портила форму («полосы»; через
    RemoteViews — нормально), View.alpha оставлял стекло бледным, текст #90FFFFFF делает стекло бледнее. Оставлено так.
- Превью при долгом нажатии — программный canvas: GlassTextClock рисует там белые цифры 80%.
- Не проверено: Jovi (там ct 1), 2×2 на телефоне.

## v0.20 (07.11.2026) — почему цифры белеют при сдвиге страницы
- Замер удержанием пальца (шаг 20 px): стекло по содержимому работает, пока ЛЕВЫЙ край view на экране (x ≥ 0).
  x = 13 → стекло, x = −7 → HWUI целиком выключает стекло и рисует просто текст (белые цифры). За правый край — можно.
  Это решение внутри libhwui (не настраивается из view): у текста шириной во весь виджет левый край уезжает первым.
- v0.20: GlassDigitsClock — каждая цифра отдельным view (GlassDigit, свой GlassHelper), время считается само.
  При сдвиге белеть может только цифра, реально ушедшая за левый край; её не рисуем. Ячейки фиксированной ширины
  (по самой широкой цифре), чтобы смена минуты не меняла размер view.
- Проверено: при удержании со сдвигом до 450 px цифры остаются стеклянными, белых нет.
- Отдельные view на каждую цифру оказались хуже: у узких высоких view HWUI строит форму стекла с ошибками
  (вертикальные края «1», наклон «7» рассыпаются на горизонтальные полосы — «не то преломляют»). Группа материала
  и поворот ободка на это не влияют.

## v0.20 (08.11.2026) — РЕШЕНИЕ: стекло цифр при листании в обе стороны
- Замеры удержанием пальца в обе стороны: стекло по содержимому выключается (белые/синие цифры), как только view
  подходит к краю экрана ближе ~запаса (и слева, и справа). Просто сдвинуть view внутрь экрана до края — мало
  (проверено: dx=63 → всё равно сломано); с отступом 30 px — стекло держится.
- setMaterialRegion / setMaterialForceCheckRegion (область стекла = видимая часть) — не помогают.
- Итог (GlassDigitsClock, tag "L1"/"L2"): ОДНО стеклянное view на строку (как в v0.19 — форма строится чисто) +
  clamp: пока строка у края, view сдвигается (translationX) так, чтобы оставаться в экране с отступом 8dp,
  а рисунок — на столько же обратно (canvas.translate). На экране цифры стоят на месте, срезается только полоска
  8dp у самого края. Ширина view постоянная (по самой широкой цифре), минута не меняет размер.
- Проверено на телефоне: сдвиги 60/150/300/450 px влево и вправо — стекло везде, после отпускания — чистое,
  смена минуты 11:04→11:05 — чистая. Не проверены: 2×2 (L2) на телефоне, Jovi.
- Синие «11:» справа при листании — это НЕ наш виджет: на соседней странице другие часы (лаунчер держит их как
  картинку, ImageView). Наш «Стеклянное время» на телефоне один — id 109.
- Установка: vivo иногда показывает «Приложение из внешнего источника» — галочка (129,2796) + «Продолжить установку»
  (720,2979); подтверждаю сам (разрешено пользователем). Опыты: GLASS_DEBUG → contentDescription часов, цифры
  читают его у родителя (ключи batch/hide/clamp/cm/region/fcr/gid/lrot).

## v0.21 (08.11.2026) — часы обрезались у правого края при листании: МАСШТАБ виджета
- Состояние перед правкой: «Стеклянное время» 4×2 — tag "P1" (три стеклянных view: часы / двоеточие / минуты),
  отступ clamp 3dp (= 12 px), а не L1 и 8dp, как записано в v0.20.
- Симптом (удержание пальца, 5/20/50/75%): страница едет вправо → цифры обрываются на x≈1320–1337, т.е. ~100 px
  до края экрана; влево — срез ровно ~12 px (это отступ clamp, так и задумано).
- Причина: лаунчер vivo строит виджет в полной ширине (LauncherAppWidgetView 1440 px, `dumpsys activity top`)
  и УМЕНЬШАЕТ его масштабом k ≈ 0.857 под ячейки (у «14» width 638, на экране 547). clamp брал положение из
  getLocationInWindow (экранные px), а width и translationX — локальные. Слева ошибка сокращалась, справа край view
  всегда вставал на 1428 − (638 − 547) = 1337. Замеры правого края до v0.21 (README v0.20: «dx=63 мало, 30 px держится»)
  искажены на эти ~91 px.
- Исправление: ScreenSpan (GlassCore.kt) — края view на экране без своего сдвига и k через transformMatrixToGlobal;
  GlassDigit.checkPos считает clamp в экранных px, сдвиг = dx / k; GlassHelper.trackMove вычитает k·translationX.
- Проверено на телефоне (логи `k=0.8573551`): справа на 20/50/75% цифры доходят до края (срез ~12 px, как слева),
  на 75% «1» видна целиком (раньше — полоска). Влево — без изменений.
- ПРАВИЛО для всех будущих виджетов: в лаунчере виджет масштабирован — экранные и локальные px не смешивать.

## v0.22 (08.11.2026) — переключатель стиля стекла часов в приложении
- Приложение «Glass Origin» (MainActivity) — экран «Стиль стекла часов»: Прозрачное (clear) / Как папки (folder) /
  Матовое (liquid) / Иней (liquid + chooseMaterialBlurIteration 4). Выбор — в SharedPreferences (GlassLook).
- Путь: GlassLook.applyAll → onUpdate «Стеклянного времени» 4×2 и 2×2 → setContentDescription(часы, "look=…") →
  GlassDigit в лаунчере собирает GlassCfg по стилю (GlassLook.cfg). "recipe=…" из GLASS_DEBUG по-прежнему важнее.
  У 2×2 теперь тоже работает GLASS_DEBUG (раньше не был передан debugId).
- Проверено по логу GlassDiag: каждое переключение доходит до цифр за < 1 с (liquid → frost → folder → clear → frost).
  «Иней» на снимке — матовые серые цифры с размытым фоном. Остальные стили глазами по снимкам не сверял
  (пользователь переключал их сам на телефоне).
- Риск: clearMaterial не сбрасывает setLiquidGlassEffect (README v0.19) — возможно, и blur iteration тоже. Если
  «Прозрачное» после «Инея» осталось матовым — сбрасывать iteration явно.
- РЕЗУЛЬТАТ v0.22: пользователю не понравилось — «Иней» дал плоские серые цифры без преломления и ободка
  (в dumpsys у цифр mBlurLevel=4, у подложки GlassBgView в clear — 0). Хотел другое: номера стилей, как в Glass lab.

## v0.23–0.24 (08.11.2026) — номера стилей BlurMaterial для часов, панель поверх рабочего стола
- Стиль часов = режим + номер: "look=clear|liquid|folder|plain;st=N" в contentDescription часов (GlassLook).
  Номера — все из findStyle (README выше): 0, 2–11, 51–59, 101–113, 151–163, 201–215, 251–265, 301–303, 311–313,
  321–323, 331, 351–353, 361–363, 371–373, 381, 401–434 (130 шт., не все рабочие).
  Режимы: clear (setMaterial(N) + жидкое стекло + 140/60 + ободок), liquid (setMaterial(N) + только жидкое стекло —
  видны параметры самого стиля), folder (рецепт папок), plain (setMaterial(N) без жидкого стекла).
- GlassHelper: в plain вне пакета явно setLiquidGlassEffect(0) (clearMaterial его не сбрасывает); если прошлый конфиг
  ставил blur iteration, а новый нет — chooseMaterialBlurIteration(0). «Иней» убран.
- v0.23: панель была прозрачной активити снизу — НЕ ГОДИТСЯ: лаунчер vivo прячет рабочий стол, пока поверх него
  любая активити (даже windowIsTranslucent; система считает его видимым — Task visible=true, isOnScreen=true —
  но над панелью чёрный экран).
- v0.24: панель — плавающее окно TYPE_APPLICATION_OVERLAY (StylePanel.kt, PanelOverlay), FLAG_NOT_FOCUSABLE: лаунчер
  остаётся на переднем плане, часы видны вживую, касания мимо панели — лаунчеру. Нужно разрешение «Поверх других
  приложений»; MainActivity без него показывает карточку с кнопкой в настройки, с ним — открывает панель и закрывается.
- Установлено. Не проверено: разрешение ещё не выдано (appops SYSTEM_ALERT_WINDOW: default).
  (17.11: пользователь выдал — appops allow; стиль сейчас clear / 410.)

## v0.25 (17.11.2026) — стекло цифр ломалось, когда выезжает Jovi (HiBoard)
- Симптом (скрин пользователя, часы на стр. 1, Jovi тянут слева): «10» плоские серые (стекло выключено),
  «:33» стеклянные, но в горизонтальных полосах. Воспроизвёл удержанием пальца на 5/10/20/35%.
- Причина (отчёт предков цифр): как только тянут Jovi, лаунчер ставит DragLayer (9-й предок цифр) АППАРАТНЫЙ слой
  (L=2) и плавно меняет ему alpha 1 → 0.89 и scale → 0.99. Внутри HW-слоя стекло по содержимому даёт полосы
  (как при листании, v0.19), а dropAncestorLayers смотрел только 8 предков. После снятия слоя оставалась alpha < 1:
  HWUI рисует такое поддерево через промежуточный буфер — там стекло цифр выключается («10» плоские).
- Исправление (GlassHelper.dropAncestorLayers): HW-слой снимаем у ВСЕЙ цепочки предков; предку с alpha < 1 ставим
  forceHasOverlappingRendering(false) — альфа применяется к каждой операции рисования, без буфера.
- ЛОВУШКА (пользователь: «чет они плоские, 10 и 4»): пока у DragLayer стоит этот флаг, у стекла цифр пропадает светлый
  ободок и преломление даже при alpha 1 — цифры плоские. Доказано перезапуском лаунчера (флаг сбросился → ободки
  вернулись). Поэтому флаг ставим ТОЛЬКО пока alpha < 1 и снимаем (force(true)), как только alpha = 1; после снятия все
  цифры переприменяют стекло (overlapEpoch). Флаг, оставшийся от прошлой версии кода, снимается при первой встрече.
- Проверено на телефоне: в покое ободки есть (после установки, после удержания Jovi, после открытия/закрытия Jovi,
  после листания); Jovi 10/20% — цифры цельные, без полос, ободок притушен (экран и так гаснет); на 35%+ их размывает
  сам Jovi; листание 20% — чисто, с ободком.
- Диагностика: Android 14+ замораживает фоновое приложение → broadcast'ы GlassDiag от лаунчера не доходят. Diag теперь
  ещё и пишет в файл в cache процесса, где исполняется код: лаунчер — /data/user_de/0/com.bbk.launcher2/cache/
  glassdiag.log (читать через root). Отчёт о предках (альфа/слой/масштаб) — флаг Diag.ANCESTORS (выкл.).
- Телефон теперь с root (KernelSU). Ставлю без окна vivo: adb push → su -c 'pm install -r'. Сами часы root НЕ требуют.

## v0.26–0.28 (17.11.2026) — «на доли секунды багается»: разбор видео по кадрам
- Метод: screenrecord (40 Мбит/с) во время жестов (Jovi тянуть/отпустить, открыть рывком, BACK, листание туда-обратно),
  ffmpeg → кадры области часов с метками времени, листы по 24 кадра от начала каждого жеста. Скриншоты с удержанием
  такие сбои НЕ ловят (за 0.8 с удержания стекло успевает переприменится). Скрипт: rec.sh в scratchpad.
- v0.25: полосы на «:53» ВЕСЬ жест Jovi, пока палец движется. Трассировка: лаунчер анимирует не только alpha, но и
  МАСШТАБ DragLayer (1 → 0.98). Форма стекла по содержимому считается при применении: сдвиг она переживает (листание
  чистое), смену масштаба — нет. В покое у DragLayer scale = 0.9999206, не ровно 1.
- v0.26: при смене масштаба предков (ScreenSpan k) — переприменение стекла в том же кадре (ensure quiet) → чисто,
  кроме 2 кадров в начале жеста. v0.27: сравнение с k на момент применения (appliedK), а не с прошлым кадром; при
  изменении предков — переприменение в этом же кадре (не post) → всё ещё 2 кадра.
- Трассировка preDraw (Diag.TRACE): наш код успевает на КАЖДЫЙ кадр (8 мс). Битые кадры = ровно кадры, где меняются
  свойства DragLayer нашими руками: 1-й — лаунчер ставит HW-слой (снимаем), 5-й — первая alpha < 1 (включаем
  «без перекрытия»). Само переключение свойства предка даёт 1 кадр с полосами.
- v0.28: DragLayer со слоем = начало перехода → «без перекрытия» включаем СРАЗУ вместе со снятием слоя и держим, пока
  alpha < 1 / scale < 0.999 / 250 мс после этого (busyUntil); по истечении — kick-invalidate, флаг снимается, ободок
  возвращается. Только для DragLayer (у ShortcutAndWidgetContainer при листании флаг не трогаем — там ободок нужен).
- Проверено видео: Jovi тянуть/отпустить, открыть рывком, BACK, листание — НИ ОДНОГО кадра с полосами. Пока идёт
  переход Jovi, цифры без ободка (плоские), через ~0.25 с после окончания ободок возвращается.

## v0.29–0.31 (17.11.2026) — любой шрифт TTF/OTF, растягивание, 4×3
- Шрифт (GlassFonts.kt): в панели «Стекло часов» строка шрифтов — Dela Gothic (встроенный), системные Sans Black / Sans /
  Serif / Mono, свои; «＋ Свой шрифт» → FontPickActivity (прячет плавающую панель, ACTION_OPEN_DOCUMENT */*, тип файла
  проверяется по сигнатуре OTTO/ttcf/0x00010000/true + Typeface.Builder(file).build() != null) → копия в files/fonts
  (<имя>_<метка>.ttf|otf|ttc). Долгое нажатие на свой шрифт — удалить. Названия в строке нарисованы своим шрифтом.
- Ключ шрифта — в contentDescription часов: "look=…;st=…;font=dela|sys:<семейство>|file:<имя>". GlassDigitsClock
  опрашивает его раз в 500 мс (переопределять setContentDescription нельзя), грузит шрифт в фоне и меняет у всех цифр
  (GlassDigit.changeTypeface → переприменение стекла).
- Лаунчеру файлы приложения недоступны (другой uid) → FontProvider (authority …widgetsdk_inner_demo.fonts, exported,
  только чтение, только files/fonts, только лаунчер/Jovi/само приложение по uid) отдаёт дескриптор → Typeface.Builder(fd).
  Root не нужен. Проверено: Cinematografica-Heavy.otf из Download/Fonts — «[font] 'file:…' применён», часы этим шрифтом.
- «Обрезаны сверху» (Cinematografica): цифры выше заявленной высоты строки (ascent), а ячейки с includeFontPadding=false
  мерились по метрикам → верх срезала граница view. v0.31: реальный контур "0123456789:" (getTextBounds) → отступы ячеек
  сверху/снизу/по бокам на вылезающую часть, размер подгоняется по контуру. Проверено: флажки «1» и верх «8» целые.
- Размер: «Стеклянное время» 4×2 и 2×2 — resizeMode horizontal|vertical (minResize 110×50dp); новый провайдер
  «Стеклянное время 4×3» (тот же макет). tag "PA": часы сами выбирают 1 или 2 строки — что даёт цифры крупнее при текущем
  размере (с запасом 5%, чтобы не дёргалось). Лаунчер vivo растягивание поддерживает (долгое нажатие → рамка с маркерами);
  при растягивании до 4×3 лог: «[rows] 2 строки при 1365x1002».
- Панель: «Прозрачное» не влезало при широком системном шрифте → autosize у кнопок режимов; строка шрифтов сразу
  прокручена к выбранному.

## v0.32–0.34 (17.11.2026) — «при включении серым падает, потом стеклянным становится»
- Видео + трассировка DragLayer: возврат на рабочий стол (HOME) и закрытие/запуск приложения — alpha ВСЁ ВРЕМЯ 1.0, меняется
  только масштаб (0.9 → 1) + слой в первом кадре. v0.28 включала «без перекрытия» уже по слою и держала всю анимацию →
  цифры ~0.6 с без ободка («серые»).
- При разблокировке: лаунчер сначала показывает СНИМОК виджета (программный canvas → наша заглушка: белые цифры 80% —
  яркая вспышка, к тому же снимок старый, прежним шрифтом), потом ~1 с ПРОЯВЛЯЕТ наш виджет: alpha контейнера
  LauncherAppWidgetHostView 0 → 1 (+ слой на нём). Всё это время флаг → без ободка.
- v0.32 (флаг по слою только 80 мс): ободок пропал НАСОВСЕМ — даже в покое и после Jovi, до перезапуска лаунчера.
  Снимать флаг посреди анимации масштаба нельзя. Откатил.
- v0.33: слой флаг не включает; флаг только при alpha в (0,1), снятие — в покое (BUSY_MS; держим, пока DragLayer
  отмасштабирован). Видео HOME: ободок с первого видимого кадра. Цена: в начале выезда Jovi вернулись 2 битых кадра.
- v0.34: (1) проявление НАШЕГО контейнера пропускаем — пока лаунчер поднимает его alpha, держим 1 (запоминаем значение
  лаунчера, а не нашу 1; функцию зовут все 3 цифры за кадр — иначе флаг мигал каждый кадр). Угасание не трогаем.
  (2) проявление из полной невидимости (alpha была 0) флаг не включает. (3) заглушка снимка — имитация стекла:
  заливка 18% белого + контур 1.5dp 55%.
- Разблокировку проверил пользователь на телефоне: «все норм» (видео разблокировки снять не удалось: блокировка + AOD,
  выключение — состояние Dozing, screenrecord при выключенном экране не стартует).

## Разбор лаунчера (06.11.2026, dexdump, classes2/classes3.dex)
- Стеклянную подложку ставит m4.j.p «setTimeShadowToWidget» (Launcher.BlurController) на child(0) виджета = корень нашего layout.
  Условие для виджета: isSupportMaterial ИЛИ isSupportBlurSpecialWidget (LauncherAppWidgetHostView.E5 / C5).
  - isSupportMaterial = meta-data boolean `vivo.support.material.widget` (sd.c0.j1) — у виджетов задач vivo.
  - isSupportBlurSpecialWidget = meta-data boolean `vivo.support.motionblur.widget` (sd.c0.G0) — у всех виджетов календаря vivo.
  - Плюс глобальный флаг системы (z5.a.m(), «isSupportDynamicBlur») — раз календарь стеклянный, он включён.
  - ПРОВЕРКИ ПАКЕТА / ПОДПИСИ / БЕЛОГО СПИСКА НА ЭТОМ ПУТИ НЕТ.
- У XTC ни одной из этих меток нет — сравнивали не с тем образцом.
- isSupportOriginWidget (u1.E0) = origin_widget И пакет «доверенный»: имя начинается с `com.vivo` / `com.bbk` ИЛИ подпись системная.
  Это «тип 1» (sd.i.p/q: 1 = origin, 2 = ui_version ≥ 2.0, 3 = обычный); используется для размеров, к стеклу не относится.
- Хост 1024/1026/1025/1027 выбирается по режиму рабочего стола (LauncherEnvironmentManagerMain.b4), а не по типу виджета.
- Строки "custom_widget.json" в коде лаунчера нет — белый список, похоже, на стекло не влияет.
- Если v0.2 не даст стекла: запасной вариант — пакет с именем com.vivo.* (проходит проверку «доверенного»).

## Главное, что нашли
1. Стеклянные виджеты vivo — это ОБЫЧНЫЕ Android AppWidget (RemoteViews), но с особыми meta-data в манифесте.
   Стекло рисует сам лаунчер на подложке под виджетом (системный материал, а не код виджета).
2. Одинаковые метки у календаря vivo (com.vivo.widget.calendar) и у СТОРОННЕГО пакета XTC (com.xtc.originwidget):
   - com.vivo.widget_type = origin_widget
   - com.vivo.widget_ui_version = 2.0 (float)
   - com.vivo.launcher.widget.adapter_feature = 1
   - com.vivo.widget.running.version = 1
   - com.vivo.widget.show.title = 1
3. В лаунчере проверки isSupportOriginWidget / isOriginTypeOr2_0_UIVersion смотрят именно на эти метки.
4. Разрешение com.bbk.launcher2.permission.WIDGET_SERVER имеет уровень protection = normal → его может взять любое приложение.
5. Классы стекла в лаунчере: applyGlassEffect / setGlassEffect («By SDK»), setTimeShadowToWidget(isGlassMaterialEffect, materialBGView),
   Launcher.BlurController, android.view.blur.BlurMaterial, com.spacesystem.materialsdk.Material.
6. У лаунчера два хоста виджетов: hostId 1024 (системные/Origin) и 1026 (сторонние, туда попал KWGT).

## Открытый вопрос — белый список
В лаунчере зашит assets/custom_widget.json — список из 122 виджетов (XTC там есть).
Неясно, требуется ли попасть в этот список для стекла. Проверка — тестовый виджет.
- Стекло появилось → путь открыт, делаем свой дизайн.
- Стекла нет → искать проверку белого списка: декомпилировать launcher.apk через jadx на ноуте.

## Файлы
- GlassWidgetTest/ — код тестового виджета (вставить в новый проект Android Studio «Empty Views Activity»)
- GlassWidgetApp/ — готовый Gradle-проект с этим виджетом; пересборка — build_apk.bat
- Распакованные dex/json лаунчера: Desktop\origin_dump\launcher_unz (дизассемблер: %LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\dexdump.exe -d)
- scripts/ — bat-скрипты, которыми снимали дампы с телефона
- Дампы и APK лежат в Desktop\origin_dump (launcher.apk, SuperCard.apk, WidgetCalendar.apk, xtc.apk, widgets.txt, packages.txt, pkg_*.txt)
- adb: %USERPROFILE%\platform-tools\adb.exe
