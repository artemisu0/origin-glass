package com.artemiy.glasstest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * v0.29: выбор файла шрифта (TTF/OTF/TTC) системным диалогом. Открывается из панели стиля (StylePanel): прячет
 * плавающую панель (иначе она закрывает пол-диалога), импортирует файл (GlassFonts.import — копия + проверка),
 * выбирает его для часов и возвращает панель.
 */
class FontPickActivity : Activity() {
    private companion object { const val PICK = 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        PanelOverlay.hide(this)
        // тип */*: у шрифтов в файловых менеджерах очень разные MIME (font/ttf, application/x-font-ttf,
        // application/octet-stream…) — фильтр по типу прятал бы часть файлов. Что это шрифт — проверяем сами.
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(pick, PICK)
        } catch (_: Throwable) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show()
            done()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || uri == null) { done(); return }
        val app = applicationContext
        GlassFonts.io.execute {
            val key = GlassFonts.import(app, uri)
            runOnUiThread {
                if (key != null) {
                    GlassFonts.select(app, key)
                    GlassLook.applyAll(app)
                    Toast.makeText(app, "Шрифт «${GlassFonts.prettyName(key.removePrefix("file:"))}» на часах", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(app, "Это не шрифт TTF/OTF", Toast.LENGTH_LONG).show()
                }
                done()
            }
        }
    }

    private fun done() {
        if (android.provider.Settings.canDrawOverlays(this)) PanelOverlay.show(this)
        finish()
    }
}
