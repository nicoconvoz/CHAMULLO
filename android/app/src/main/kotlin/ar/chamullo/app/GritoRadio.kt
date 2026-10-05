package ar.chamullo.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
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
import ar.chamullo.core.Crypto
import ar.chamullo.core.toHex
import java.util.UUID

/**
 * El grito (Air Interface §3–4): the phone's LE radio used raw. Frames go out as non-connectable, non-scannable
 * extended advertisements carrying one Service Data field; nobody pairs or connects. Long-range Coded PHY is used
 * when the phone supports it.
 */
@SuppressLint("MissingPermission")
class GritoRadio(context: Context, private val onFrame: (ByteArray) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<ByteArray>()
    private val recent = LinkedHashMap<String, Long>()
    private var set: AdvertisingSet? = null
    private var starting = false
    private var busy = false
    private var scanning = false

    var shouts = 0L; private set
    var heard = 0L; private set
    var lastError: String? = null; private set

    val enabled get() = adapter?.isEnabled == true
    val extended get() = adapter?.isLeExtendedAdvertisingSupported == true
    val coded get() = adapter?.isLeCodedPhySupported == true
    val maxAdvLen get() = if (extended) adapter!!.leMaximumAdvertisingDataLength else 31

    // One Service Data AD structure: length + type + 128-bit UUID = 18 bytes, plus a little margin.
    val maxFrame get() = (maxAdvLen - 20).coerceIn(0, MAX_FRAME)
    val canShout get() = enabled && extended

    fun start() = handler.post { startScan() }

    fun stop() = handler.post {
        runCatching { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(setCallback) }
        set = null; starting = false; busy = false; queue.clear()
    }

    fun shout(frame: ByteArray) = handler.post {
        if (!canShout) return@post
        queue.addLast(frame)
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        pump()
    }

    private fun data(frame: ByteArray) = AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE), frame).build()

    // Each shout stays on the air for HOLD_MS (a few advertising events), then the next one takes its place.
    private fun pump() {
        if (busy || starting || queue.isEmpty()) return
        val frame = queue.removeFirst()
        val current = set
        if (current == null) { startSet(frame); return }
        busy = true
        current.setAdvertisingData(data(frame))
        current.enableAdvertising(true, 0, 0)
        shouts++
        handler.postDelayed({
            busy = false
            if (queue.isEmpty()) current.enableAdvertising(false, 0, 0)
            pump()
        }, HOLD_MS)
    }

    private fun startSet(first: ByteArray) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { lastError = "sin anunciante LE"; return }
        val phy = if (coded) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false).setConnectable(false).setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(phy).setSecondaryPhy(phy)
            .build()
        starting = true
        runCatching { advertiser.startAdvertisingSet(params, data(first), null, null, null, setCallback) }
            .onFailure { starting = false; lastError = it.message }
    }

    private val setCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            starting = false
            if (status != ADVERTISE_SUCCESS || advertisingSet == null) { lastError = "no pudo gritar (código $status)"; return }
            set = advertisingSet
            shouts++
            busy = true
            handler.postDelayed({ busy = false; if (queue.isEmpty()) advertisingSet.enableAdvertising(false, 0, 0); pump() }, HOLD_MS)
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { lastError = "Bluetooth apagado"; return }
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(!extended)
            .apply { if (extended) setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback); scanning = true }
            .onFailure { lastError = it.message }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = receive(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::receive)
        override fun onScanFailed(errorCode: Int) { scanning = false; lastError = "no pudo escuchar (código $errorCode)" }
    }

    // The same shout is heard several times while it is on the air; only the first copy goes up.
    private fun receive(result: ScanResult) {
        val frame = result.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
        val key = Crypto.hash(frame).toHex()
        val now = System.currentTimeMillis()
        if ((recent[key] ?: 0L) > now - DEDUP_MS) return
        recent[key] = now
        while (recent.size > 512) recent.remove(recent.keys.first())
        heard++
        onFrame(frame)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("c4a3e1b0-6d5f-4c1e-9a77-43484d4c4c4f") // ends in "CHMLLO"
        const val MAX_FRAME = 240
        const val HOLD_MS = 300L
        const val DEDUP_MS = 5_000L
        const val MAX_QUEUE = 200
    }
}
