package ar.chamullo.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.logo
import ar.chamullo.app.Ui.screen
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title
import ar.chamullo.core.hex

/**
 * La Tienda de Lucas (Economy & Governance §4.3, §13): what the Lucas earned carrying letters or lending Internet buy
 * inside the ecosystem. Each purchase is a signed spend in the pueblo's ledger, paid to the store's account.
 */
class StoreActivity : Activity() {
    private lateinit var wallet: TextView
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen {
            addView(logo())
            addView(title("Tienda de Lucas"))
            wallet = text("", 16f, Ui.GREEN, bold = true); addView(wallet)
            addView(text("Las Lucas se ganan llevando cartas y prestando Internet. Acá se gastan; cada compra queda en la libreta del pueblo.", 13f, Ui.MUTED))
            for (item in CATALOG) addView(card {
                addView(text("${item.name} · ${item.price} Lucas", 15f, bold = true))
                addView(text(item.what, 13f, Ui.MUTED))
                addView(button("Comprar", primary = false) { buy(item) })
            })
            result = text("", 14f); addView(result)
        }
        refresh()
    }

    private fun buy(item: Item) {
        val seller = Settings.founder(this).takeIf { it.length == 64 }?.let { hex(it) }
            ?: run { result.text = "Todavía no hay libreta en tu pueblo: falta la clave del fundador (Diagnóstico)."; return }
        Hub.ask({ node -> node.spend(seller, item.price, item.name) }) { ok ->
            result.text = if (ok) "✓ Comprado: ${item.name}. Queda anotado en la próxima página de la libreta." else "No te alcanzan las Lucas para ${item.name}."
            refresh()
        }
    }

    private fun refresh() = Hub.ask({ it.available() }) { n -> wallet.text = "🪙 $n Lucas para gastar" }

    class Item(val name: String, val price: Long, val what: String)

    companion object {
        val CATALOG = listOf(
            Item("Sticker CHAMULLO 🦅", 5, "Para tu perfil y tus cartas."),
            Item("Aporte al relé de la comunidad", 20, "Ayuda a sostener la nube del puente por Internet."),
            Item("Un mate para el Capitán 🧉", 10, "Agradecimiento a quien fundó la red.")
        )
    }
}
