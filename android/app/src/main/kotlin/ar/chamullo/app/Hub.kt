// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import ar.chamullo.core.Card
import ar.chamullo.core.Node
import ar.chamullo.core.NodeEvent
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Where the screens meet the running node. The node lives on one worker thread; screens ask it things with [ask]
 * and hear about changes through [listeners] on the main thread.
 */
object Hub {
    private val thread = HandlerThread("chamullo-node").apply { start() }
    val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var node: Node? = null
    @Volatile var radios: List<Radio> = emptyList()
    @Volatile var relay: ar.chamullo.core.NostrBridge? = null
    /** The data tunnel (Discovery & Routing §11.3) and whether I browse with my bought Internet right now. */
    @Volatile var tunnel: DataTunnel? = null
    @Volatile var browsing = false
    val pendingCards = mutableListOf<Card>()
    val listeners = CopyOnWriteArraySet<(NodeEvent?) -> Unit>()

    fun post(block: (Node) -> Unit) = worker.post { node?.let(block) }

    fun <T> ask(question: (Node) -> T, answer: (T) -> Unit) = worker.post {
        val n = node ?: return@post
        val result = question(n)
        main.post { answer(result) }
    }

    fun emit(event: NodeEvent?) = main.post { listeners.forEach { it(event) } }
}
