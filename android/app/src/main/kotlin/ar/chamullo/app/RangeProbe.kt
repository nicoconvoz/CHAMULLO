// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import ar.chamullo.core.Range
import java.util.UUID

/**
 * La prueba de largo alcance (Air Interface §12): one phone shouts a numbered shout every second over Bluetooth LE Coded
 * PHY (S=8, the long-range mode) when its chip has it, plain Bluetooth otherwise; the other listens on every PHY and
 * counts. Walking away shows how far each mode really reaches on these phones. Apart from el grito, so it never mixes
 * with letters.
 */
@SuppressLint("MissingPermission")
class RangeProbe(context: Context, private val nodeId: ByteArray) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    val stats = Range.Stats()

    val enabled get() = adapter?.isEnabled == true
    val coded get() = adapter?.isLeExtendedAdvertisingSupported == true && adapter.isLeCodedPhySupported
    @Volatile var shouting = false; private set
    @Volatile var listening = false; private set
    @Volatile var sent = 0L; private set
    @Volatile var error: String? = null; private set

    private var set: AdvertisingSet? = null
    private var seq = 0L

    /** What this phone can do, in words. */
    fun capability(): String = when {
        adapter == null -> "este celular no tiene Bluetooth"
        !enabled -> "el Bluetooth está apagado"
        coded -> "tiene largo alcance (Bluetooth LE Coded)"
        else -> "no tiene largo alcance: la prueba usa Bluetooth común"
    }

    fun startShouting() {
        if (shouting || !enabled) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { error = "no puede anunciar por Bluetooth"; return }
        shouting = true; seq = 0; sent = 0
        if (coded) {
            val params = AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(false).setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build()
            runCatching { advertiser.startAdvertisingSet(params, data(), null, null, null, setCallback) }
                .onFailure { error = it.message; shouting = false }
        } else startLegacy()
        handler.postDelayed(next, 1_000)
        FieldLog.add("ALCANCE", "grito de prueba: ${if (coded) "largo alcance" else "Bluetooth común"}")
    }

    // Long range carries it as service data; a plain advert has 31 bytes in all, so there it goes as manufacturer data.
    private fun data(): AdvertiseData = if (coded) AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE), Range.shout(nodeId, seq)).build()
        else AdvertiseData.Builder().addManufacturerData(COMPANY, Range.shout(nodeId, seq)).build()

    private val setCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status != ADVERTISE_SUCCESS || advertisingSet == null) { error = "no arrancó el largo alcance (código $status)"; shouting = false; return }
            set = advertisingSet; sent++
        }
    }

    private val legacy = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { sent++ }
        override fun onStartFailure(errorCode: Int) { error = "no pudo gritar (código $errorCode)" }
    }

    private fun startLegacy() {
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).setTimeout(0).build()
        runCatching { adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data(), legacy) }.onFailure { error = it.message }
    }

    // A new number every second: the coded set changes its data in place; the classic advert is restarted.
    private val next: Runnable = object : Runnable {
        override fun run() {
            if (!shouting) return
            seq++
            val s = set
            if (coded) { if (s != null) { s.setAdvertisingData(data()); sent++ } }
            else { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(legacy) }; startLegacy() }
            handler.postDelayed(this, 1_000)
        }
    }

    fun stopShouting() {
        shouting = false
        handler.removeCallbacks(next)
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(setCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(legacy) }
        set = null
    }

    fun startListening() {
        if (listening || !enabled) return
        val scanner = adapter?.bluetoothLeScanner ?: run { error = "no puede escuchar por Bluetooth"; return }
        stats.clear()
        val filters = listOf(ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0)).build(),
            ScanFilter.Builder().setManufacturerData(COMPANY, byteArrayOf(0x43, 0x48, 0x52), byteArrayOf(-1, -1, -1)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply { if (adapter.isLeExtendedAdvertisingSupported) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }.build()
        runCatching { scanner.startScan(filters, settings, scan); listening = true }.onFailure { error = it.message }
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val bytes = record.getServiceData(ParcelUuid(SERVICE)) ?: record.getManufacturerSpecificData(COMPANY) ?: return
            val (id, n) = Range.read(bytes) ?: return
            stats.heard(id, n, result.rssi, result.primaryPhy == BluetoothDevice.PHY_LE_CODED, System.currentTimeMillis())
        }
        override fun onScanFailed(errorCode: Int) { listening = false; error = "la escucha falló (código $errorCode)" }
    }

    fun stopListening() {
        listening = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("c4a3e1b0-6d5f-4c1e-9a77-43484d52414e")

        /** What this phone's Bluetooth can do, before any test starts. */
        fun describe(c: Context): String {
            val a = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return "Este celular no tiene Bluetooth."
            if (!a.isEnabled) return "El Bluetooth está apagado: la prueba lo pide al empezar."
            return if (a.isLeExtendedAdvertisingSupported && a.isLeCodedPhySupported) "Este celular tiene largo alcance (Bluetooth LE Coded)." else "Este celular no tiene largo alcance: la prueba usa Bluetooth común."
        }
        const val COMPANY = 0xFFFF
    }
}
