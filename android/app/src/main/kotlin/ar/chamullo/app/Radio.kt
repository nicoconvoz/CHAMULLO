// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

/**
 * A way for frames to leave the phone (Master document §4: the protocol is independent of the transport).
 * Every radio carries the same CHAMULLO frames; the node never knows which one a frame used.
 */
interface Radio {
    val label: String
    /** The phone has the hardware for it. */
    val supported: Boolean
    /** It is switched on and ready right now. */
    val active: Boolean
    val shouts: Long
    val heard: Long
    val peers: Int
    val lastError: String?
    fun start()
    fun stop()
    fun shout(frame: ByteArray)
}
