package ar.chamullo.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.input
import ar.chamullo.app.Ui.logo
import ar.chamullo.app.Ui.screen
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title
import ar.chamullo.core.Phrase

/** First run: create an identity (and write down the 16 words) or recover one with them. */
class OnboardingActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start()
    }

    private fun start() {
        root = screen {
            addView(logo())
            addView(title("Tu nombre en la red"))
            val name = input("Cómo te van a ver tus vecinos")
            addView(name)
            addView(text("Sin torres, sin internet, sin Wi-Fi: tu celular le pasa cartas a los celulares de alrededor, y ellos a los suyos.", 14f, Ui.MUTED))
            addView(button("Crear mi identidad") { if (named(name.text.toString())) showNewPhrase(name.text.toString().trim()) })
            addView(button("Ya tengo mis 16 palabras", primary = false) { if (named(name.text.toString())) recover(name.text.toString().trim()) })
        }
    }

    private fun named(n: String): Boolean {
        if (n.isNotBlank()) return true
        root.addView(text("Poné un nombre primero.", 14f, Ui.BLUE))
        return false
    }

    private fun showNewPhrase(name: String) {
        val phrase = Phrase.newPhrase()
        screen {
            addView(logo())
            addView(title("Tus 16 palabras"))
            addView(card { addView(text(phrase.split(" ").chunked(4).joinToString("\n") { it.joinToString("   ") }, 20f, bold = true)) })
            addView(text("Anotalas en papel, en orden. Con ellas recuperás tu identidad en cualquier celular. Nadie más las tiene: si las perdés, no hay forma de recuperarlas.", 14f, Ui.MUTED))
            addView(button("Ya las anoté") { finishWith(phrase, name) })
            addView(button("Volver", primary = false) { start() })
        }
    }

    private fun recover(name: String) {
        screen {
            addView(logo())
            addView(title("Recuperar"))
            val words = input("Tus 16 palabras, separadas por espacios", multiLine = true)
            addView(words)
            val error = text("", 14f, Ui.BLUE)
            addView(error)
            addView(button("Recuperar mi identidad") {
                error.text = when (val c = Phrase.check(words.text.toString())) {
                    Phrase.Check.Ok -> { finishWith(words.text.toString(), name); "" }
                    Phrase.Check.WrongLength -> "Tienen que ser 16 palabras."
                    is Phrase.Check.UnknownWord -> "No conozco la palabra \"${c.word}\". Revisala."
                    Phrase.Check.BadChecksum -> "Alguna palabra está mal o fuera de orden."
                }
            })
            addView(button("Volver", primary = false) { start() })
        }
    }

    private fun finishWith(phrase: String, name: String) {
        Vault(this).save(Phrase.normalize(phrase).joinToString(" "), name)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
