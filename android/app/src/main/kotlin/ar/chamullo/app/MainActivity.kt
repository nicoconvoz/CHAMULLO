package ar.chamullo.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.logo
import ar.chamullo.app.Ui.row
import ar.chamullo.app.Ui.screen
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title
import ar.chamullo.core.Card
import ar.chamullo.core.NodeEvent
import ar.chamullo.core.toHex

class MainActivity : Activity() {
    private lateinit var status: android.widget.TextView
    private lateinit var nearby: LinearLayout
    private lateinit var contacts: LinearLayout
    private val listener: (NodeEvent?) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = Vault(this)
        if (!vault.hasIdentity()) { startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return }

        screen {
            addView(logo())
            addView(card {
                addView(text(vault.name(), 18f, bold = true))
                addView(text("id " + (vault.identity()?.nodeId?.toHex()?.take(8) ?: "—"), 13f, Ui.MUTED))
                status = text("Encendiendo el grito…", 14f, Ui.MUTED)
                addView(status)
            })
            addView(title("Cerca tuyo"))
            nearby = column(); addView(nearby)
            addView(title("Contactos"))
            contacts = column(); addView(contacts)
            addView(title("Más"))
            addView(button("Abrir ICEBREAK, la red social") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ICEBREAK_URL))) })
            addView(text("ICEBREAK se abre en el navegador y necesita internet; CHAMULLO no.", 12f, Ui.MUTED))
            addView(button("Diagnóstico del grito", primary = false) { startActivity(Intent(this@MainActivity, DiagActivity::class.java)) })
            addView(button("Ver mis 16 palabras", primary = false) { showPhrase(vault) })
        }
        askPermissions()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    override fun onResume() {
        super.onResume()
        Hub.listeners += listener
        refresh()
        offerPendingCards()
    }

    override fun onPause() {
        Hub.listeners -= listener
        super.onPause()
    }

    private fun refresh() {
        val radio = Hub.radio
        status.text = when {
            radio == null -> "El grito está apagado: falta dar permisos."
            !radio.enabled -> "Prendé el Bluetooth: CHAMULLO usa esa radio, sin conectarse a nada."
            !radio.extended -> "Tu celular solo puede escuchar: su radio no permite gritos largos."
            radio.coded -> "Gritando en largo alcance."
            else -> "Gritando en modo normal (tu radio no tiene largo alcance)."
        }
        Hub.ask({ node -> node.neighbors().map { Triple(it.name, it.coded, it) } to node.store.contacts() }) { (near, cards) ->
            nearby.removeAllViews()
            if (near.isEmpty()) nearby.addView(text("Nadie a la vista todavía. Acercate a otro celular con CHAMULLO.", 14f, Ui.MUTED))
            val known = cards.map { it.nodeId.toHex() }.toSet()
            for ((name, coded, n) in near) nearby.addView(card {
                addView(row {
                    addView(column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(text(name.ifBlank { "Sin nombre" }, 16f, bold = true))
                        addView(text(if (coded) "largo alcance" else "modo normal", 12f, Ui.MUTED))
                    })
                })
                if (n.nodeId.toHex() in known) addView(text("Ya es tu contacto", 13f, Ui.GREEN))
                else addView(button("Intercambiar tarjeta", primary = false) {
                    Hub.post { it.offerCard(n) }
                    status.text = "Tarjeta enviada a ${name}. Cuando acepte, aparece en Contactos."
                })
            })
            contacts.removeAllViews()
            if (cards.isEmpty()) contacts.addView(text("Todavía no tenés contactos.", 14f, Ui.MUTED))
            for (c in cards) contacts.addView(card {
                addView(text(c.name.ifBlank { "Sin nombre" }, 16f, bold = true))
                addView(text("id " + c.nodeId.toHex().take(8), 12f, Ui.MUTED))
                setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PEER, c.nodeId)) }
            })
        }
        offerPendingCards()
    }

    private fun offerPendingCards() {
        val card: Card = synchronized(Hub.pendingCards) { Hub.pendingCards.removeFirstOrNull() } ?: return
        AlertDialog.Builder(this)
            .setTitle("${card.name} quiere intercambiar tarjetas")
            .setMessage("Si aceptás, se pueden escribir aunque estén lejos: las cartas viajan de celular en celular.")
            .setPositiveButton("Aceptar") { _, _ -> Hub.post { it.acceptCard(card); Hub.emit(null) } }
            .setNegativeButton("Ahora no", null)
            .setOnDismissListener { offerPendingCards() }
            .show()
    }

    private fun showPhrase(vault: Vault) {
        AlertDialog.Builder(this).setTitle("Tus 16 palabras").setMessage(vault.phrase()).setPositiveButton("Listo", null).show()
    }

    /* ---------- permissions and start ---------- */
    private fun needed(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun askPermissions() {
        val missing = needed()
        if (missing.isEmpty()) startGrito() else requestPermissions(missing, REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val radioPerms = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (radioPerms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startGrito()
        else status.text = "Sin permiso de Bluetooth el grito no puede encenderse."
    }

    private fun startGrito() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        startForegroundService(Intent(this, GritoService::class.java))
        status.postDelayed({ refresh() }, 1500)
    }

    companion object {
        const val ICEBREAK_URL = "https://nicoconvoz.github.io/icebreak-web/"
        private const val REQ_PERMS = 7
    }
}
