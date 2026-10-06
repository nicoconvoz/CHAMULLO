// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny view helpers: the app builds its screens in code to stay light (no AndroidX, no layout inflation). */
object Ui {
    // ICEBREAK's base skin, "Tinta" (icebreak_ui ib_skins.dart).
    const val INK = 0xFF121212.toInt()
    const val MUTED = 0xFF6B6B67.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val SOFT = 0xFFF1F1EE.toInt()
    const val LINE = 0xFFD8D8D3.toInt()
    const val BG = 0xFFE8E8E5.toInt()
    const val BAR = 0xFF8D8D89.toInt()
    const val BAR_ACTIVE = 0xFF111111.toInt()
    const val RED = 0xFFE5383B.toInt()
    const val BLUE = 0xFF2F6FDB.toInt()
    const val GREEN = 0xFF1F9D43.toInt()

    fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Activity.screen(build: LinearLayout.() -> Unit): LinearLayout {
        val col = column { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        col.build()
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(BG); addView(col) })
        return col
    }

    fun Activity.column(init: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; init() }

    fun Activity.row(init: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; init() }

    fun Activity.text(s: String, size: Float = 15f, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(2))
    }

    fun Activity.title(s: String) = text(s.uppercase(), 12f, MUTED, bold = true).apply { letterSpacing = 0.1f; setPadding(0, dp(18), 0, dp(6)) }

    fun Activity.button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else INK)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (primary) INK else SURFACE); if (!primary) setStroke(dp(1), 0xFFD9D9D3.toInt()) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    fun Activity.card(init: LinearLayout.() -> Unit) = column {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(SURFACE) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        init()
    }

    fun Activity.input(hint: String, multiLine: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 16f
        inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(SURFACE) }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    fun Activity.logo() = row {
        addView(TextView(this@logo).apply {
            text = "Ch"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF0E0E0E.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(42))
        })
        addView(column {
            setPadding(dp(12), 0, 0, 0)
            addView(text("CHAMULLO", 22f, bold = true))
            addView(text("Celulares que se pasan cartas a los gritos", 13f, MUTED))
        })
    }

    fun View.gone(hidden: Boolean) { visibility = if (hidden) View.GONE else View.VISIBLE }

    /** A square button with one of ICEBREAK's icons (IbSquare / IbIconButton). */
    fun Activity.iconButton(icon: String, size: Int = 44, color: Int = INK, filled: Boolean = false, onClick: () -> Unit) =
        android.widget.ImageView(this).apply {
            setImageDrawable(Icons.of(icon, color, filled))
            val pad = dp(size) / 5
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setOnClickListener { onClick() }
        }

    /** A round avatar with initials, like ICEBREAK's user card fallback. */
    fun Activity.avatar(name: String, size: Int = 46) = TextView(this).apply {
        text = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }
        textSize = size / 3f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2B2B2B.toInt()) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
}
