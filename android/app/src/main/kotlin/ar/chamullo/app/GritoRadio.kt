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
import ar.chamullo.core.Crypto
import ar.chamullo.core.Micro
import ar.chamullo.core.MicroAssembler
import ar.chamullo.core.Packet
import ar.chamullo.core.Beacon
import ar.chamullo.core.CardOffer
import ar.chamullo.core.Envelope
import ar.chamullo.core.Fragments
import ar.chamullo.core.Hello
import ar.chamullo.core.Heard
import ar.chamullo.core.Plaza
import ar.chamullo.core.toHex
import java.util.UUID

/**
 * El grito (Air Interface §3–4): the phone's 2.4 GHz LE radio used raw, without pairing or connecting.
 *
 * - Universal shout: every frame goes out as micros in classic 31-byte advertisements, which every phone can send
 *   and hear (Manufacturer Specific Data, company 0xFFFF, reserved for tests and internal use).
 * - Long-range bonus: phones with Coded PHY also shout the whole frame as one extended advertisement.
 * - Phones without extended advertising use the classic advertiser API, which every chip implements natively.
 * - Android turns scans older than 30 minutes into opportunistic ones (no results of their own), so the scan restarts
 *   every [RESCAN_MS].
 */
@SuppressLint("MissingPermission")
class GritoRadio(context: Context, private val onFrame: (ByteArray) -> Unit, private val onHello: (ByteArray) -> Unit = {}) : Radio {
    private enum class Mode { CLASSIC, LONG }
    private class Emission(val mode: Mode, val bytes: ByteArray)

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    // Whole frames wait in line; when the line is full the oldest whole frame goes, never half of one.
    private val queue = ArrayDeque<ArrayDeque<Emission>>()
    private val recent = LinkedHashMap<String, Long>()
    private val micros = MicroAssembler()
    private val sets = HashMap<Mode, AdvertisingSet>()
    private var starting: Mode? = null
    private var busy = false
    private var scanning = false

    override val label = "Bluetooth"
    override val supported = adapter != null
    override val active get() = enabled
    override val peers = 0
    override var shouts = 0L; private set
    override var heard = 0L; private set
    override var lastError: String? = null; private set
    var lastHeardAt = 0L; private set
    var assembled = 0L; private set
    val listening get() = scanning
    val waiting get() = queue.size

    val enabled get() = adapter?.isEnabled == true
    val extended get() = adapter?.isLeExtendedAdvertisingSupported == true
    val coded get() = extended && adapter?.isLeCodedPhySupported == true
    val maxAdvLen get() = if (extended) adapter!!.leMaximumAdvertisingDataLength else 31
    val maxFrame get() = MAX_FRAME

    override fun start() { handler.post { startScan(); handler.postDelayed(rescan, RESCAN_MS) } }

    private val rescan: Runnable = object : Runnable {
        override fun run() {
            runCatching { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            scanning = false
            FieldLog.add("BT", "reinicio de la escucha (Android corta las de más de 30 min)")
            startScan()
            handler.postDelayed(this, RESCAN_MS)
        }
    }

    override fun stop() { handler.post {
        handler.removeCallbacks(rescan)
        runCatching { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(classicCallback) }
        for (cb in callbacks.values) runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
        sets.clear(); starting = null; busy = false; queue.clear()
    } }

    override fun shout(frame: ByteArray) {
        if (frame.size > CAMINO_MAX) { FieldLog.add("BT", "${describe(frame)} de ${frame.size} bytes: va solo por la carretera"); return }
        for (part in Fragments.split(frame, MAX_FRAME)) shoutOne(part)
    }

    private fun shoutOne(frame: ByteArray) { handler.post {
        if (!enabled) return@post
        val group = ArrayDeque<Emission>()
        if (coded && frame.size <= maxAdvLen - 20) group.addLast(Emission(Mode.LONG, frame))
        for (m in Micro.split(frame)) group.addLast(Emission(Mode.CLASSIC, m))
        queue.addLast(group)
        while (queue.size > MAX_FRAMES) FieldLog.add("BT", "cola llena: se descarta una carta entera (${describe(frame)} en espera)").also { queue.removeAt(if (queue.size > 1) 1 else 0) }
        FieldLog.add("BT", "a la cola: ${describe(frame)} · ${group.size} gritos · ${queue.size} cartas esperando")
        pump()
    } }

    /** "¡Hola!" in one classic shout, ahead of everything else in line. */
    fun sayHello(hello: ByteArray) = handler.post {
        if (!enabled) return@post
        queue.addFirst(ArrayDeque(listOf(Emission(Mode.CLASSIC, hello))))
        pump()
    }

    private fun data(e: Emission): AdvertiseData = when (e.mode) {
        Mode.CLASSIC -> AdvertiseData.Builder().addManufacturerData(COMPANY, e.bytes).build()
        Mode.LONG -> AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE), e.bytes).build()
    }

    // Each emission stays on the air for a few advertising events, then the next one takes its place.
    private fun pump() {
        if (busy || starting != null || queue.isEmpty()) return
        val group = queue.first()
        val e = group.removeFirst()
        if (group.isEmpty()) queue.removeFirst()
        if (e.mode == Mode.CLASSIC && !extended) { classic(e); return }
        val set = sets[e.mode]
        if (set == null) { startSet(e); return }
        busy = true
        set.setAdvertisingData(data(e))
        set.enableAdvertising(true, 0, 0)
        shouts++
        handler.postDelayed({ set.enableAdvertising(false, 0, 0); busy = false; pump() }, HOLD_MS)
    }

    // Classic advertiser: start, stay on the air for HOLD_MS, stop, next.
    private fun classic(e: Emission) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { lastError = "Bluetooth apagado"; return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false).setTimeout(0).build()
        busy = true
        runCatching { advertiser.startAdvertising(settings, data(e), classicCallback) }.onFailure { lastError = it.message; FieldLog.add("BT", "excepción al gritar: ${it.message}") }
        handler.postDelayed({
            runCatching { advertiser.stopAdvertising(classicCallback) }
            busy = false
            pump()
        }, HOLD_MS)
    }

    private val classicCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { shouts++ }
        override fun onStartFailure(errorCode: Int) { lastError = "el grito corto falló (código $errorCode)"; FieldLog.add("BT", "grito clásico falló, código $errorCode") }
    }

    private val callbacks = Mode.values().associateWith { mode ->
        object : AdvertisingSetCallback() {
            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                if (status != ADVERTISE_SUCCESS) { lastError = "no se pudo cargar el grito (código $status)"; FieldLog.add("BT", "cargar grito falló, código $status") }
            }
            override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
                if (status != ADVERTISE_SUCCESS) { lastError = "no se pudo ${if (enable) "encender" else "apagar"} el grito (código $status)"; FieldLog.add("BT", "${if (enable) "encender" else "apagar"} grito falló, código $status") }
            }
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                starting = null
                if (status != ADVERTISE_SUCCESS || advertisingSet == null) {
                    lastError = "no pudo gritar en modo ${if (mode == Mode.LONG) "largo" else "corto"} (código $status)"
                    FieldLog.add("BT", "no arrancó el grito ${if (mode == Mode.LONG) "largo" else "corto"}, código $status")
                    pump(); return
                }
                sets[mode] = advertisingSet
                FieldLog.add("BT", "grito ${if (mode == Mode.LONG) "largo" else "corto"} listo")
                shouts++
                busy = true
                handler.postDelayed({ advertisingSet.enableAdvertising(false, 0, 0); busy = false; pump() }, HOLD_MS)
            }
        }
    }

    private fun startSet(first: Emission) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { lastError = "Bluetooth apagado"; return }
        val params = AdvertisingSetParameters.Builder()
            .setConnectable(false).setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .apply {
                if (first.mode == Mode.CLASSIC) setLegacyMode(true)
                else setLegacyMode(false).setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            }
            .build()
        starting = first.mode
        runCatching { advertiser.startAdvertisingSet(params, data(first), null, null, null, callbacks.getValue(first.mode)) }
            .onFailure { starting = null; lastError = it.message }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { lastError = "Bluetooth apagado"; return }
        val filters = buildList {
            add(ScanFilter.Builder().setManufacturerData(COMPANY, byteArrayOf(Micro.MARK), byteArrayOf(-1)).build())
            add(ScanFilter.Builder().setManufacturerData(COMPANY, byteArrayOf(Hello.MARK), byteArrayOf(-1)).build())
            if (extended) add(ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0)).build())
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply { if (extended) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
            .build()
        runCatching { scanner.startScan(filters, settings, scanCallback); scanning = true; FieldLog.add("BT", "escuchando (extendido: $extended, largo alcance: $coded)") }
            .onFailure { lastError = it.message; FieldLog.add("BT", "no pudo empezar a escuchar: ${it.message}") }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = receive(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::receive)
        override fun onScanFailed(errorCode: Int) { scanning = false; lastError = "no pudo escuchar (código $errorCode)"; FieldLog.add("BT", "la escucha falló, código $errorCode") }
    }

    // The same emission is heard several times while it is on the air; only the first copy goes up.
    private fun receive(result: ScanResult) {
        val record = result.scanRecord ?: return
        val micro = record.getManufacturerSpecificData(COMPANY)
        val whole = record.getServiceData(ParcelUuid(SERVICE))
        val bytes = micro ?: whole ?: return
        val key = Crypto.hash(bytes).toHex()
        val now = System.currentTimeMillis()
        if ((recent[key] ?: 0L) > now - DEDUP_MS) return
        recent[key] = now
        while (recent.size > 1024) recent.remove(recent.keys.first())
        heard++
        lastHeardAt = now
        if (micro != null && Hello.isHello(micro)) { onHello(micro); return }
        val frame = if (micro != null) micros.accept(micro) else bytes
        if (frame != null) { assembled++; FieldLog.add("BT", "llegó: ${describe(frame)}${if (micro == null) " (grito largo)" else ""}"); onFrame(frame) }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("c4a3e1b0-6d5f-4c1e-9a77-43484d4c4c4f")
        const val COMPANY = 0xFFFF
        const val MAX_FRAME = 240
        const val HOLD_MS = 250L
        const val DEDUP_MS = 5_000L
        const val MAX_FRAMES = 12
        const val CAMINO_MAX = 4_096

        fun describe(frame: ByteArray): String = when (val p = Packet.parseOrNull(frame)) {
            is Beacon -> "latido de ${p.name.ifBlank { "?" }}"
            is Plaza -> "plaza de ${p.name.ifBlank { "?" }}"
            is Heard -> "lo escuchó ${p.name.ifBlank { "?" }}"
            is CardOffer -> "tarjeta"
            is Envelope -> "carta cifrada"
            is ar.chamullo.core.RoadInvite -> "llave de carretera"
            null -> if (frame.size > 3 && frame[3].toInt() == Packet.KIND_FRAGMENT) "trozo de carta" else "desconocido"
        }
        const val RESCAN_MS = 10 * 60_000L
    }
}
