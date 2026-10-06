// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/**
 * La Tienda de Lucas (Economy & Governance §13). The catalog is published on CHAMULLO's page (tienda.json) so prices and
 * products change without a new app; [DEFAULT] is the one that comes inside. A purchase is a signed spend in the pueblo's
 * ledger ("tienda:<item>"), so "my purchases" and "my data" are read from the book, the same in every copy.
 */
object Shop {
    class Item(val id: String, val name: String, val desc: String, val price: Long, val mb: Int = 0)
    class Category(val id: String, val title: String, val items: List<Item>)
    class Purchase(val item: Item, val price: Long, val ts: Long)

    const val PREFIX = "tienda:"
    /** Data packs: their Lucas wait in the buyer's data escrow until lenders collect them with receipts (§13). */
    const val DATA_PREFIX = "tienda:datos"

    /** The catalog that comes inside the app. Data packs are Internet lent by bridges (Discovery & Routing §11). */
    const val DEFAULT = """{"categorias":[
      {"id":"datos","titulo":"🌐 Datos de Internet prestados","items":[
        {"id":"datos-50mb","nombre":"50 MB","detalle":"Para mensajes y mapas","precio":25,"mb":50},
        {"id":"datos-250mb","nombre":"250 MB","detalle":"Un día de redes sociales","precio":100,"mb":250},
        {"id":"datos-1gb","nombre":"1 GB","detalle":"Una semana tranquila","precio":350,"mb":1024},
        {"id":"datos-5gb","nombre":"5 GB","detalle":"Para videos y llamadas","precio":1500,"mb":5120},
        {"id":"datos-10gb","nombre":"10 GB","detalle":"El mes completo","precio":2800,"mb":10240}]},
      {"id":"prioridad","titulo":"⚡ Prioridad","items":[
        {"id":"prioridad-10","nombre":"10 cartas primero","detalle":"Tus cartas pasan adelante en la fila","precio":15},
        {"id":"prioridad-50","nombre":"50 cartas primero","detalle":"Para cuando apura","precio":60}]},
      {"id":"insignias","titulo":"🏅 Insignias","items":[
        {"id":"sticker-chamullo","nombre":"Sticker CHAMULLO 🦅","detalle":"Para tu perfil y tus cartas","precio":5},
        {"id":"insignia-cartero","nombre":"Insignia Cartero 📮","detalle":"Para los que llevan cartas","precio":40},
        {"id":"insignia-puente","nombre":"Insignia Puente 🌉","detalle":"Para los que prestan Internet","precio":40}]},
      {"id":"apoyos","titulo":"🤝 Apoyos a la red","items":[
        {"id":"apoyo-rele","nombre":"Aporte a los relés","detalle":"Sostiene el puente por Internet","precio":20},
        {"id":"apoyo-mate","nombre":"Un mate para el Capitán 🧉","detalle":"Agradecimiento a quien fundó la red","precio":10}]}
    ]}"""

    fun parse(json: String): List<Category> {
        val root = Json.parse(json) as Map<*, *>
        return (root["categorias"] as List<*>).map { c ->
            val m = c as Map<*, *>
            Category(m["id"] as String, m["titulo"] as String, (m["items"] as List<*>).map { i ->
                val it = i as Map<*, *>
                Item(it["id"] as String, it["nombre"] as String, (it["detalle"] as? String) ?: "", (it["precio"] as Number).toLong(), (it["mb"] as? Number)?.toInt() ?: 0)
            })
        }
    }

    fun parseOrDefault(json: String?): List<Category> = json?.let { runCatching { parse(it).takeIf { c -> c.isNotEmpty() } }.getOrNull() } ?: parse(DEFAULT)

    /** A purchase: [buyer] pays [item]'s price to [seller] (the store's account), signed, for the next page. */
    fun buy(buyer: Identity, item: Item, seller: ByteArray, now: Long): Entry.Spend = Entry.spend(buyer, payee(item, seller), item.price, PREFIX + item.id, now)

    /** Who a purchase pays: the store, or nobody yet for a data pack (it goes to escrow for the lenders). */
    fun payee(item: Item, seller: ByteArray): ByteArray? = if (isData(item)) null else seller

    fun isData(item: Item) = item.mb > 0 && (PREFIX + item.id).startsWith(DATA_PREFIX)

    /** The packs bought, oldest first, for [DataPlan]. */
    fun packs(purchases: List<Purchase>): List<DataPlan.Pack> = purchases.filter { isData(it.item) }.map { DataPlan.Pack(it.item.mb, it.price) }

    /** What [me] bought, as the ledger has it. */
    fun purchases(ledger: Ledger, me: ByteArray, catalog: List<Category>): List<Purchase> {
        val items = catalog.flatMap { it.items }.associateBy { it.id }
        return ledger.pages.flatMap { p -> p.entries.mapNotNull { Entry.parse(it) as? Entry.Spend }.map { it to p.ts } }
            .filter { (s, _) -> s.from.contentEquals(me) && s.what.startsWith(PREFIX) }
            .mapNotNull { (s, ts) -> items[s.what.removePrefix(PREFIX)]?.let { Purchase(it, s.amount, ts) } }
    }

    /** Megabytes of lent Internet bought. */
    fun dataMb(purchases: List<Purchase>): Int = purchases.sumOf { it.item.mb }
}
