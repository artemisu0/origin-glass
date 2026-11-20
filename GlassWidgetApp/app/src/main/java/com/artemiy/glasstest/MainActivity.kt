package com.artemiy.glasstest

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v0.23: запуск приложения = панель стиля стекла часов поверх рабочего стола (PanelOverlay), сама активити сразу
 * закрывается — лаунчер возвращается на передний план и показывает часы вживую.
 * Если нет разрешения «Поверх других приложений» — карточка с кнопкой, ведущей в настройки.
 */
class MainActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            PanelOverlay.show(this)
            finish()
            return
        }
        showPermissionCard()
    }

    private fun showPermissionCard() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(0xF01C1C1E.toInt())
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        card.addView(TextView(this).apply {
            text = "Стекло часов"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Панель стиля открывается поверх рабочего стола, чтобы часы было видно вживую. " +
                    "Для этого включи для «Glass Origin» разрешение «Поверх других приложений»."
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, dp(8), 0, dp(16))
        })
        fun button(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
            setOnClickListener { onClick() }
        }
        card.addView(button("Разрешить", 0xFF4C8DFF.toInt()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        card.addView(button("Закрыть", 0x24FFFFFF) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        setContentView(root)
    }
}
