package io.github.g20ir.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * GATT client for the IR service of the Google G10/G20 reference remote
 * (Telink firmware, refDesignRcu app_ir.c / app_att.c).
 *
 * The remote stays bonded to the Shield; we open a second GATT client on the existing link.
 * Programming must happen from the host the remote is bonded to: the firmware wipes the IR
 * table when it bonds with a different host.
 */
@SuppressLint("MissingPermission")
class G20Remote(
    private val context: Context,
    val device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    companion object {
        private fun uuid(b: String) = UUID.fromString("d343bf$b-5a21-4f05-bc7d-af01f617b664")
        val SERVICE: UUID = uuid("c0")
        val CONTROL: UUID = uuid("c1") // 01 = start (clears table), 00 = stop/save
        val KEY_ID: UUID = uuid("c2") // 2 bytes big-endian
        val CODE: UUID = uuid("c3") // payload, must arrive in ONE write
        val SUPPRESS: UUID = uuid("c4") // key ids -> BLE, zero length -> all IR
        val KEY_EVENT: UUID = uuid("c5") // notify: [0 down / 1 up, keyIdHi, keyIdLo]
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OP_TIMEOUT_MS = 6000L

        private fun sig16(x: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(x))
        private val DEVICE_INFO = sig16(0x180A)
        private val BATTERY = sig16(0x180F)
        private val BATTERY_LEVEL = sig16(0x2A19)
        private val INFO_FIELDS = listOf(
            "Manufacturer" to sig16(0x2A29),
            "Model" to sig16(0x2A24),
            "Firmware" to sig16(0x2A26),
            "Hardware" to sig16(0x2A27),
            "Software" to sig16(0x2A28),
        )
    }

    enum class State { DISCONNECTED, CONNECTING, READY, FAILED }

    data class KeyEvent(val down: Boolean, val keyId: Int)

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _keyEvents = MutableSharedFlow<KeyEvent>(extraBufferCapacity = 32)
    val keyEvents: SharedFlow<KeyEvent> = _keyEvents

    /** Negotiated ATT MTU; a write can carry mtu - 3 bytes. The firmware caps this at 180. */
    var mtu = 23
        private set

    val maxWrite: Int get() = mtu - 3

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private var readPending: CompletableDeferred<ByteArray?>? = null
    private var connected = CompletableDeferred<Boolean>()
    private var discovered = CompletableDeferred<Boolean>()
    private var mtuDone = CompletableDeferred<Int>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("connection state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.complete(false)
                discovered.complete(false)
                pending?.complete(-1)
                if (_state.value == State.READY) _state.value = State.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            log("MTU $newMtu (status $status)")
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuDone.complete(newMtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readPending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) readPending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c.uuid, value)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(c.uuid, c.value ?: return)
        }
    }

    private fun handleNotify(uuid: UUID, v: ByteArray) {
        if (uuid != KEY_EVENT || v.size < 3) return
        val ev = KeyEvent(down = v[0].toInt() == 0, keyId = ((v[1].toInt() and 0xFF) shl 8) or (v[2].toInt() and 0xFF))
        log("key event ${if (ev.down) "down" else "up"} 0x%04X".format(ev.keyId))
        _keyEvents.tryEmit(ev)
    }

    /** Connects, discovers services and negotiates the MTU. Returns false if the IR service is missing. */
    suspend fun connect(): Boolean {
        close()
        _state.value = State.CONNECTING
        connected = CompletableDeferred()
        discovered = CompletableDeferred()
        mtuDone = CompletableDeferred()
        log("connecting to ${device.name} ${device.address}")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (withTimeoutOrNull(15_000) { connected.await() } != true) return fail("could not connect")
        delay(300)
        if (!g.discoverServices()) return fail("service discovery did not start")
        if (withTimeoutOrNull(15_000) { discovered.await() } != true) return fail("service discovery failed")
        val svc = g.getService(SERVICE) ?: return fail("this device has no IR programming service")
        log("IR service found, ${svc.characteristics.size} characteristics")
        if (g.requestMtu(512)) {
            withTimeoutOrNull(5000) { mtuDone.await() } ?: log("MTU exchange timed out, using $mtu")
        }
        _state.value = State.READY
        return true
    }

    private fun fail(msg: String): Boolean {
        log("error: $msg")
        _state.value = State.FAILED
        gatt?.close()
        gatt = null
        return false
    }

    fun close() {
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            it.close()
        }
        gatt = null
        if (_state.value != State.FAILED) _state.value = State.DISCONNECTED
    }

    private fun char(uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(SERVICE)?.getCharacteristic(uuid) ?: throw IllegalStateException("not connected")

    private suspend fun write(uuid: UUID, value: ByteArray) = opLock.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val c = char(uuid)
        val d = CompletableDeferred<Int>()
        pending = d
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                g.writeCharacteristic(c)
            }
        }
        if (!started) throw IllegalStateException("write to ${uuid.toString().take(8)} was not accepted by Android")
        val status = withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() } ?: throw IllegalStateException("write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("write failed, status $status")
    }

    private suspend fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray) = opLock.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val p = CompletableDeferred<Int>()
        pending = p
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }
        if (!started) throw IllegalStateException("descriptor write was not accepted by Android")
        val status = withTimeoutOrNull(OP_TIMEOUT_MS) { p.await() } ?: throw IllegalStateException("descriptor write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("descriptor write failed, status $status")
    }

    private suspend fun read(service: UUID, uuid: UUID): ByteArray? = opLock.withLock {
        val g = gatt ?: return@withLock null
        val c = g.getService(service)?.getCharacteristic(uuid) ?: return@withLock null
        val d = CompletableDeferred<ByteArray?>()
        readPending = d
        @Suppress("DEPRECATION")
        if (!g.readCharacteristic(c)) return@withLock null
        withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() }
    }

    /**
     * Device information and battery level. The IR table itself cannot be read: the firmware
     * backs all IR characteristics with a 1-byte dummy value and has no read handler.
     */
    suspend fun deviceInfo(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((label, uuid) in INFO_FIELDS) {
            read(DEVICE_INFO, uuid)?.let { v -> out += label to String(v, Charsets.UTF_8).trim { it <= ' ' } }
        }
        read(BATTERY, BATTERY_LEVEL)?.firstOrNull()?.let { out += "Battery" to "${it.toInt() and 0xFF} %" }
        out += "Address" to device.address
        out += "MTU" to "$mtu (max $maxWrite bytes per code)"
        return out
    }

    /**
     * Writes a complete IR table in one programming session (the firmware clears the table on
     * start, so keys not listed here go back to normal Bluetooth behaviour).
     */
    suspend fun program(entries: List<Pair<Int, ByteArray>>) {
        // 5 keys, plus Input possibly written under both of its key IDs (one is ignored).
        require(entries.size <= 6) { "too many keys" }
        for ((id, code) in entries) {
            if (code.size > maxWrite) {
                throw IllegalStateException("code for key 0x%04X is ${code.size} bytes; this link carries $maxWrite per write".format(id))
            }
        }
        log("programming ${entries.size} key(s)")
        write(CONTROL, byteArrayOf(1))
        delay(150)
        for ((id, code) in entries) {
            write(KEY_ID, byteArrayOf((id shr 8).toByte(), id.toByte()))
            write(CODE, code)
            log("  key 0x%04X: %d bytes".format(id, code.size))
            delay(60) // the firmware saves each key to flash as it arrives
        }
        write(CONTROL, byteArrayOf(0))
        delay(250)
        log("programming done")
    }

    /** Removes every IR code; all keys send Bluetooth again. */
    suspend fun clearAll() = program(emptyList())

    /** Zero-length write: every programmed key sends IR. With key ids: those keys send BLE instead. */
    suspend fun setBleKeys(keyIds: List<Int>) {
        val b = ByteArray(keyIds.size * 2)
        keyIds.forEachIndexed { i, id -> b[2 * i] = (id shr 8).toByte(); b[2 * i + 1] = id.toByte() }
        write(SUPPRESS, b)
    }

    /**
     * When enabled the remote reports every press of a programmed IR key to the Shield.
     * The flag is stored in the remote's flash.
     */
    suspend fun setKeyNotifications(on: Boolean) {
        val g = gatt ?: throw IllegalStateException("not connected")
        val c = char(KEY_EVENT)
        g.setCharacteristicNotification(c, on)
        val d = c.getDescriptor(CCCD) ?: throw IllegalStateException("no notification descriptor")
        writeDescriptor(d, if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        log("key notifications ${if (on) "on" else "off"}")
    }
}
