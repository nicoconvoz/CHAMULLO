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
    const val INK = 0xFF121212.toInt()
    const val MUTED = 0xFF686864.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val SOFT = 0xFFF4F4F1.toInt()
    const val BLUE = 0xFF2F6FDB.toInt()
    const val GREEN = 0xFF1F9D43.toInt()

    fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Activity.screen(build: LinearLayout.() -> Unit): LinearLayout {
        val col = column { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        col.build()
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
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
}
