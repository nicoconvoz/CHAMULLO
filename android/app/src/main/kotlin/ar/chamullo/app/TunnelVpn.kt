// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * "Navegar con mis datos" (Discovery & Routing §11.3): a VPN that only carries a recommendation. It tells every app to
 * use [LocalProxy] (Android 10+), which sends the web through the data tunnel. Nothing else leaves the phone this way:
 * what reaches the VPN itself is dropped. CHAMULLO's own pipes stay outside it.
 */
class TunnelVpn : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    private val proxy = LocalProxy()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { shutdown(); stopSelf(); return START_NOT_STICKY }
        if (tun != null) return START_STICKY
        if (Build.VERSION.SDK_INT < 29) { stopSelf(); return START_NOT_STICKY }
        runCatching {
            proxy.start()
            tun = Builder().setSession("CHAMULLO: Internet prestado")
                .addAddress("10.77.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("10.77.0.1")
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", LocalProxy.PORT))
                .addDisallowedApplication(packageName)
                .establish()
        }.onFailure { FieldLog.add("TÚNEL", "no pude armar la VPN: ${it.message}") }
        val fd = tun ?: run { proxy.stop(); stopSelf(); return START_NOT_STICKY }
        Hub.browsing = true
        FieldLog.add("TÚNEL", "navegando con mis datos comprados")
        // Whatever an app sends without the proxy lands here: read and dropped, so it never piles up.
        Thread {
            runCatching {
                val s = FileInputStream(fd.fileDescriptor); val buf = ByteArray(32 * 1024)
                while (tun === fd) if (s.read(buf) <= 0) Thread.sleep(100)
            }
        }.apply { isDaemon = true; name = "vpn-drop" }.start()
        return START_STICKY
    }

    private fun shutdown() {
        Hub.browsing = false
        proxy.stop()
        runCatching { tun?.close() }; tun = null
        FieldLog.add("TÚNEL", "dejo de navegar con mis datos")
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    companion object {
        const val STOP = "ar.chamullo.app.TUNNEL_STOP"
    }
}
