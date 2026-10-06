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
import ar.chamullo.core.MessageState
import ar.chamullo.core.NodeEvent
import ar.chamullo.core.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : Activity() {
    private lateinit var peer: ByteArray
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val listener: (NodeEvent?) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getByteArrayExtra(EXTRA_PEER) ?: run { finish(); return }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        scroll = ScrollView(this).apply { addView(list); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val box = input("Escribí tu carta")
        val priority = android.widget.CheckBox(this).apply { text = "Con prioridad: $PRIORITY_LUCAS Lucas para los carteros" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            val title = text("", 20f, bold = true).apply { setPadding(dp(16), dp(16), dp(16), dp(4)) }
            addView(title)
            addView(text("Cada carta salta de celular en celular. ✓✓ = llegó.", 13f, Ui.MUTED).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
            addView(scroll)
            addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16))
                addView(box)
                addView(priority)
                addView(button("Gritar carta") {
                    val msg = box.text.toString().trim()
                    if (msg.isNotEmpty()) {
                        box.setText("")
                        val paid = if (priority.isChecked) PRIORITY_LUCAS else 0L
                        Hub.post { node -> node.store.contact(peer)?.let { node.send(it, msg, priority = paid) }; Hub.emit(null) }
                    }
                })
            })
            Hub.ask({ it.store.contact(peer)?.name ?: "Contacto" }) { title.text = it }
        }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); Hub.listeners += listener; refresh() }

    override fun onPause() { Hub.listeners -= listener; super.onPause() }

    private fun refresh() = Hub.ask({ it.store.messages(peer) }) { messages ->
        list.removeAllViews()
        if (messages.isEmpty()) list.addView(text("Todavía no se escribieron. La primera carta puede tardar si están lejos.", 14f, Ui.MUTED))
        for (m in messages) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.mine) Gravity.END else Gravity.START
            addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (m.mine) Ui.INK else Ui.SURFACE) }
                addView(text(m.text, 16f, if (m.mine) Ui.SURFACE else Ui.INK))
                val state = when (m.state) { MessageState.SENT -> " · en camino"; MessageState.DELIVERED -> " · ✓✓"; MessageState.RECEIVED -> "" }
                addView(text(clock.format(Date(m.ts)) + state, 11f, if (m.mine) 0xFFBBBBBB.toInt() else Ui.MUTED))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        const val EXTRA_PEER = "peer"
        // What a letter offers its carriers to go first (Economy & Governance §6.1).
        const val PRIORITY_LUCAS = 2L
    }

    @Suppress("unused") private fun shortId() = peer.toHex().take(8)
}
