// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The black box: what the radios and the node did, so a field test can be read afterwards instead of guessed. */
object FieldLog {
    private const val MAX = 800
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized fun add(tag: String, message: String) {
        lines.addLast("${clock.format(Date())} [$tag] $message")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized fun text(): String = lines.joinToString("\n")

    @Synchronized fun last(n: Int): List<String> = lines.toList().takeLast(n)
}
