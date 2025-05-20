package com.zucham.qbslibdemo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BleManager(private val context: Context) {
    private val TAG = "BleManager"


    // Bluetooth adapter
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothManager.adapter
    }

    // BLE scanner
    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    // Connected device
    private var bluetoothGatt: BluetoothGatt? = null

    // State management
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    // Discovered devices
    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<BluetoothGattService>>(emptyList())
    val discoveredServices = _discoveredServices.asStateFlow()

    private val _characteristicData = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val characteristicData = _characteristicData.asStateFlow()

    // Scan for all devices
    @RequiresApi(Build.VERSION_CODES.S)
    fun scanForDevices() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        _scannedDevices.value = emptyList()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            return
        }

        bleScanner?.startScan(null, scanSettings, scanCallback)
    }

    // Stop scanning
    @RequiresApi(Build.VERSION_CODES.S)
    fun stopScan() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        bleScanner?.stopScan(scanCallback)
        if (connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // Connect to specific device
    @RequiresApi(Build.VERSION_CODES.S)
    fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            return
        }

        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        device.connectGatt(context, false, gattCallback)
    }

    // Disconnect from the device
    fun disconnect() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _discoveredServices.value = emptyList()
        _characteristicData.value = emptyMap()
    }

    // Write to a characteristic
    fun writeCharacteristic(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val service = bluetoothGatt?.getService(serviceUuid)
        val characteristic = service?.getCharacteristic(characteristicUuid)

        if (characteristic != null) {
            characteristic.value = data
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
    }

    // Enable notifications for a characteristic
    fun enableNotifications(serviceUuid: UUID, characteristicUuid: UUID) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val service = bluetoothGatt?.getService(serviceUuid)
        val characteristic = service?.getCharacteristic(characteristicUuid)

        if (characteristic != null) {
            bluetoothGatt?.setCharacteristicNotification(characteristic, true)

            // Enable notifications on the remote device (if applicable)
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGatt?.writeDescriptor(descriptor)
            }
        }
    }

    // Check for required permissions
    @RequiresApi(Build.VERSION_CODES.S)
    fun hasRequiredPermissions(): Boolean {
        val hasScanPermission = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        val hasConnectPermission = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val hasLocationPermission = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocationPermission = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return hasScanPermission && hasConnectPermission && hasLocationPermission && hasCoarseLocationPermission
    }

    // BLE scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val device = result.device

            // Add to list if not already present
            val currentDevices = _scannedDevices.value.toMutableList()
            if (!currentDevices.contains(device)) {
                currentDevices.add(device)
                _scannedDevices.value = currentDevices
                Log.d(TAG, "Found device: ${device.name ?: "Unknown"} (${device.address})")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.ERROR
            Log.e(TAG, "Scan failed with error: $errorCode")
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.CONNECTED
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _discoveredServices.value = gatt.services
                Log.d(TAG, "Discovered ${gatt.services.size} services")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val uuid = characteristic.uuid.toString()
                val newData = _characteristicData.value.toMutableMap()
                newData[uuid] = value
                _characteristicData.value = newData
                Log.d(TAG, "Read characteristic $uuid: ${value.contentToString()}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val uuid = characteristic.uuid.toString()
            val newData = _characteristicData.value.toMutableMap()
            newData[uuid] = value
            _characteristicData.value = newData
            Log.d(TAG, "Characteristic $uuid changed: ${value.contentToString()}")
        }
    }

    // Connection states
    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        PERMISSION_DENIED,
        ERROR
    }
}