package com.synclisten.app.nearby

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BleInviteCodec {
    private const val PREFIX = "SyncListen:"
    private val roomCode = Regex("[A-Z0-9]{6}")

    fun encode(code: String): ByteArray = "$PREFIX${code.trim().uppercase()}".encodeToByteArray()

    fun decode(payload: ByteArray): String? {
        val value = payload.decodeToString()
        if (!value.startsWith(PREFIX)) return null
        return value.removePrefix(PREFIX).takeIf(roomCode::matches)
    }
}

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
    val roomCodes: Set<String> = emptySet(),
    val message: String? = null,
)

interface BleRoomDiscovery {
    val state: StateFlow<BleRoomDiscoveryState>
    fun requiredPermissions(advertise: Boolean): List<String>
    fun startAdvertising(roomCode: String)
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
            val code = result.scanRecord?.getServiceData(serviceUuid)?.let(BleInviteCodec::decode) ?: return
            mutableState.value = mutableState.value.copy(roomCodes = mutableState.value.roomCodes + code)
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
    override fun startAdvertising(roomCode: String) {
        if (!checkAvailable(advertise = true)) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return unsupported()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, BleInviteCodec.encode(roomCode))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @Suppress("MissingPermission")
    override fun stopAdvertising() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
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
            listOf(ScanFilter.Builder().setServiceUuid(serviceUuid).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    @Suppress("MissingPermission")
    override fun stopScanning() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
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
