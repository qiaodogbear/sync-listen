package com.synclisten.app.nearby

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BleInviteCodec {
    private val roomCode = Regex("[A-Z0-9]{6}")

    fun encode(invite: BleInvite): ByteArray {
        val code = invite.roomCode.trim().uppercase().takeIf(roomCode::matches)
            ?: error("Invalid room code")
        val uri = URI(invite.serverUrl ?: error("Server URL is required"))
        require(uri.scheme == "http" && uri.port != 0 && uri.port <= 65535) { "BLE only supports local HTTP IPv4 invitations" }
        val octets = uri.host.split(".").map(String::toInt)
        require(octets.size == 4 && octets.all { it in 0..255 })
        val port = if (uri.port >= 0) uri.port else 80
        return ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .put(1)
            .apply { octets.forEach { put(it.toByte()) } }
            .putShort(port.toShort())
            .put(code.encodeToByteArray())
            .array()
    }

    fun decode(payload: ByteArray): BleInvite? {
        if (payload.size == 6) {
            return payload.decodeToString().takeIf(roomCode::matches)?.let { BleInvite(it, null) }
        }
        if (payload.size != 13 || payload[0].toInt() != 1) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        val host = (0 until 4).joinToString(".") { buffer.get().toUByte().toString() }
        val port = buffer.short.toUShort().toInt()
        if (port == 0) return null
        val code = ByteArray(6).also(buffer::get).decodeToString()
        return code.takeIf(roomCode::matches)?.let { BleInvite(it, "http://$host:$port") }
    }
}

data class BleInvite(val roomCode: String, val serverUrl: String?)

fun blePermissions(sdkInt: Int, advertise: Boolean): List<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(
            if (advertise) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

enum class BleDiscoveryStatus {
    IDLE,
    ADVERTISING,
    SCANNING,
    UNSUPPORTED,
    BLUETOOTH_DISABLED,
    PERMISSION_REQUIRED,
    ERROR,
}

data class BleRoomDiscoveryState(
    val status: BleDiscoveryStatus = BleDiscoveryStatus.IDLE,
    val invites: Set<BleInvite> = emptySet(),
    val message: String? = null,
)

interface BleRoomDiscovery {
    val state: StateFlow<BleRoomDiscoveryState>
    fun requiredPermissions(advertise: Boolean): List<String>
    fun startAdvertising(invite: BleInvite)
    fun stopAdvertising()
    fun startScanning()
    fun stopScanning()
}

@Singleton
class AndroidBleRoomDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) : BleRoomDiscovery {
    private val serviceUuid = ParcelUuid(UUID.fromString("2d266186-01fb-47c2-8d9f-10b8ec891363"))
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutableState = MutableStateFlow(BleRoomDiscoveryState())
    override val state: StateFlow<BleRoomDiscoveryState> = mutableState

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            mutableState.value = BleRoomDiscoveryState(BleDiscoveryStatus.ADVERTISING)
        }

        override fun onStartFailure(errorCode: Int) {
            mutableState.value = BleRoomDiscoveryState(BleDiscoveryStatus.ERROR, message = "BLE 广播失败：$errorCode")
        }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val invite = result.scanRecord?.getServiceData(serviceUuid)?.let(BleInviteCodec::decode) ?: return
            mutableState.value = mutableState.value.copy(invites = mutableState.value.invites + invite)
        }

        override fun onScanFailed(errorCode: Int) {
            mutableState.value = BleRoomDiscoveryState(BleDiscoveryStatus.ERROR, message = "BLE 扫描失败：$errorCode")
        }
    }

    override fun requiredPermissions(advertise: Boolean) =
        blePermissions(Build.VERSION.SDK_INT, advertise).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    @Suppress("MissingPermission")
    override fun startAdvertising(invite: BleInvite) {
        if (!checkAvailable(advertise = true)) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return unsupported()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(false)
            .build()
        val payload = runCatching { BleInviteCodec.encode(invite) }.getOrElse {
            mutableState.value = BleRoomDiscoveryState(BleDiscoveryStatus.ERROR, message = "此地址不支持 BLE 邀请，请使用二维码或链接")
            return
        }
        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(serviceUuid, payload)
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @Suppress("MissingPermission")
    override fun stopAdvertising() {
        if (requiredPermissions(advertise = true).isEmpty()) runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        if (mutableState.value.status == BleDiscoveryStatus.ADVERTISING) {
            mutableState.value = BleRoomDiscoveryState()
        }
    }

    @Suppress("MissingPermission")
    override fun startScanning() {
        if (!checkAvailable(advertise = false)) return
        val scanner = adapter?.bluetoothLeScanner ?: return unsupported()
        mutableState.value = BleRoomDiscoveryState(BleDiscoveryStatus.SCANNING)
        scanner.startScan(
            emptyList(),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    @Suppress("MissingPermission")
    override fun stopScanning() {
        if (requiredPermissions(advertise = false).isEmpty()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (mutableState.value.status == BleDiscoveryStatus.SCANNING) {
            mutableState.value = BleRoomDiscoveryState()
        }
    }

    @Suppress("MissingPermission")
    private fun checkAvailable(advertise: Boolean): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || adapter == null) {
            unsupported()
            return false
        }
        if (requiredPermissions(advertise).isNotEmpty()) {
            mutableState.value = BleRoomDiscoveryState(
                BleDiscoveryStatus.PERMISSION_REQUIRED,
                message = "需要蓝牙权限；二维码和房间码仍可使用",
            )
            return false
        }
        if (!adapter.isEnabled) {
            mutableState.value = BleRoomDiscoveryState(
                BleDiscoveryStatus.BLUETOOTH_DISABLED,
                message = "蓝牙未开启；二维码和房间码仍可使用",
            )
            return false
        }
        return true
    }

    private fun unsupported() {
        mutableState.value = BleRoomDiscoveryState(
            BleDiscoveryStatus.UNSUPPORTED,
            message = "设备不支持 BLE；二维码和房间码仍可使用",
        )
    }
}
