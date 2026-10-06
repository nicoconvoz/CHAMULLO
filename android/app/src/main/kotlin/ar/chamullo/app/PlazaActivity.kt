// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.dp
import ar.chamullo.app.Ui.input
import ar.chamullo.app.Ui.text
import ar.chamullo.core.NodeEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** La plaza: an open test chat. Everyone within earshot reads it and answers "lo escuché" on their own. */
class PlazaActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val listener: (NodeEvent?) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        scroll = ScrollView(this).apply { addView(list); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val box = input("Gritá algo a la plaza")
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            addView(text("La Plaza", 20f, bold = true).apply { setPadding(dp(16), dp(16), dp(16), dp(4)) })
            addView(text("Chat de prueba abierto: lo leen todos los celulares que están cerca, sin tarjetas. Debajo de cada mensaje tuyo ves quién lo escuchó.", 13f, Ui.MUTED).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
            addView(scroll)
            addView(LinearLayout(this@PlazaActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16))
                addView(box)
                addView(button("Gritar a la plaza") {
                    val msg = box.text.toString().trim()
                    if (msg.isNotEmpty()) { box.setText(""); Hub.post { it.sendPlaza(msg); Hub.emit(null) } }
                })
            })
        })
    }

    override fun onResume() { super.onResume(); Hub.listeners += listener; refresh() }

    override fun onPause() { Hub.listeners -= listener; super.onPause() }

    private fun refresh() = Hub.ask({ it.plaza() }) { lines ->
        list.removeAllViews()
        if (lines.isEmpty()) list.addView(text("Silencio en la plaza. Escribí algo y mirá quién te escucha.", 14f, Ui.MUTED))
        for (l in lines) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (l.mine) Gravity.END else Gravity.START
            addView(LinearLayout(this@PlazaActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (l.mine) Ui.INK else Ui.SURFACE) }
                if (!l.mine) addView(text(l.name.ifBlank { "Sin nombre" }, 13f, Ui.BLUE, bold = true))
                addView(text(l.text, 16f, if (l.mine) Ui.SURFACE else Ui.INK))
                val heard = when {
                    !l.mine -> ""
                    l.heardBy.isEmpty() -> " · nadie lo escuchó todavía"
                    else -> " · lo escuchó: " + l.heardBy.joinToString(", ")
                }
                addView(text(clock.format(Date(l.ts)) + heard, 11f, if (l.mine) 0xFFBBBBBB.toInt() else Ui.MUTED))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
