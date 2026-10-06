// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/** The version published on the download page (version.txt next to the APK), as ICEBREAK does since 0.10.13. */
object Version {
    private val plain = Regex("^\\d+\\.\\d+\\.\\d+$")

    fun parse(body: String): String? = body.trim().takeIf { plain.matches(it) }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toInt() }
        val y = b.split('.').map { it.toInt() }
        for (i in 0 until 3) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    fun needsUpdate(web: String?, installed: String): Boolean = web != null && newer(web, installed)
}
