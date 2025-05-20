package com.zucham.qbslibdemo

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zucham.qbslibdemo.ui.theme.QBSLibDemoTheme
import java.util.Date
import java.util.Locale
import java.util.UUID


private const val TAG = "QBSLibDemo"
private lateinit var globalEncryptor: GanGen2Encryptor

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager

    // Target service and characteristic UUIDs
    private val TARGET_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dc4179")
    private val TARGET_COMMAND_CHARACTERISTIC_UUID =
        UUID.fromString("28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4")
    private val TARGET_STATE_CHARACTERISTIC_UUID =
        UUID.fromString("28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    @RequiresApi(Build.VERSION_CODES.S)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)

        // Request necessary permissions
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        enableEdgeToEdge()
        setContent {
            QBSLibDemoTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("QBSLib Demo") }
                        )
                    }
                ) { innerPadding ->
                    MainContent(
                        bleManager = bleManager,
                        targetServiceUuid = TARGET_SERVICE_UUID,
                        commandCharacteristicUuid = TARGET_COMMAND_CHARACTERISTIC_UUID,
                        stateCharacteristicUuid = TARGET_STATE_CHARACTERISTIC_UUID,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onStart() {
        super.onStart()
        if (!bleManager.hasRequiredPermissions()) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION

                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun MainContent(
    bleManager: BleManager,
    targetServiceUuid: UUID,
    commandCharacteristicUuid: UUID,
    stateCharacteristicUuid: UUID,
    modifier: Modifier = Modifier
) {
    val connectionState by bleManager.connectionState.collectAsStateWithLifecycle()
    val scannedDevices by bleManager.scannedDevices.collectAsStateWithLifecycle()
    val services by bleManager.discoveredServices.collectAsStateWithLifecycle()
    val characteristicData by bleManager.characteristicData.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    val logMessages = remember { mutableStateListOf<String>() }
    fun addLog(message: String) {
        logMessages.add(
            "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $message"
        )
    }

    // Auto-stop scan when leaving scanning state
    LaunchedEffect(connectionState) {
        if (connectionState != BleManager.ConnectionState.SCANNING) {
            bleManager.stopScan()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConnectionStatusCard(
            connectionState = connectionState,
            scannedDevices = scannedDevices,
            onConnectClick = {
                bleManager.scanForDevices()
                addLog("Scanning for devices...")
            },
            onDisconnectClick = {
                bleManager.disconnect()
                addLog("Disconnected")
            },
            onDeviceClick = {
                addLog("Connecting to ${it.address}…")
                val salt: ByteArray = it.address
                    .split(':')
                    .map { it.toInt(16).toByte() }
                    .toByteArray().reversedArray()

                globalEncryptor = GanGen2Encryptor(salt = salt)

                bleManager.connectToDevice(it)
            }
        )

        // If connected, find our target service and characteristics
        if (connectionState == BleManager.ConnectionState.CONNECTED) {
            val targetService = services.find { it.uuid == targetServiceUuid }

            if (targetService != null) {
                val commandChar = targetService.characteristics.find {
                    it.uuid == commandCharacteristicUuid
                }

                val stateChar = targetService.characteristics.find {
                    it.uuid == stateCharacteristicUuid
                }

                // Enable notifications for the state characteristic
                LaunchedEffect(stateChar) {
                    if (stateChar != null) {
                        Log.d(
                            TAG,
                            "Enabling notifications for state characteristic ${stateChar.uuid}"
                        )
                        bleManager.enableNotifications(targetServiceUuid, stateChar.uuid)
                        addLog("Enabled notifications for state characteristic")
                    }
                }

                if (commandChar != null && stateChar != null) {
                    // Show control panel with found characteristics
                    SmartCubeControlPanel(
                        bleManager = bleManager,
                        encryptor = globalEncryptor,
                        serviceUuid = targetServiceUuid,
                        commandCharacteristic = commandChar,
                        stateCharacteristic = stateChar,
                        characteristicData = characteristicData,
                        addLog = { addLog(it) }
                    )

                    LogDisplay(logMessages = logMessages)
                } else {
                    // If missing characteristics, report which ones
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Characteristic status:", fontWeight = FontWeight.Bold)
                            if (commandChar == null) {
                                Text("Command characteristic not found")
                                addLog("Command characteristic not found")
                            }
                            if (stateChar == null) {
                                Text("State characteristic not found")
                                addLog("State characteristic not found")
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            Text(
                                "Available characteristics in service:",
                                fontWeight = FontWeight.Bold
                            )
                            targetService.characteristics.forEach { characteristic ->
                                Text("UUID: ${characteristic.uuid}")
                                Text(
                                    "Properties: ${getPropertyNames(characteristic.properties)}",
                                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                                )
                                addLog(
                                    "Found characteristic: ${characteristic.uuid} with properties: ${
                                        getPropertyNames(
                                            characteristic.properties
                                        )
                                    }"
                                )
                            }
                        }
                    }

                    LogDisplay(logMessages = logMessages)
                }
            } else {
                // If we can't find the specific service, show all available services for debugging
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Target service not found. Available services:",
                            fontWeight = FontWeight.Bold
                        )
                        addLog("Target service not found")
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(services) { service ->
                                Text("Service: ${service.uuid}")
                                addLog("Found service: ${service.uuid}")
                                service.characteristics.forEach { characteristic ->
                                    Text(
                                        "  Char: ${characteristic.uuid} (${
                                            getPropertyNames(
                                                characteristic.properties
                                            )
                                        })",
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                    addLog(
                                        "  Char: ${characteristic.uuid} (${
                                            getPropertyNames(
                                                characteristic.properties
                                            )
                                        })"
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }

                LogDisplay(logMessages = logMessages)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun ConnectionStatusCard(
    connectionState: BleManager.ConnectionState,
    scannedDevices: List<BluetoothDevice>,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status: ${connectionState.name}",
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        if (connectionState == BleManager.ConnectionState.CONNECTED) {
                            onDisconnectClick()
                            expanded = false
                        } else {
                            expanded = !expanded
                            if (expanded) {
                                onConnectClick()
                            }
                        }
                    }
                ) {
                    Text(
                        if (connectionState == BleManager.ConnectionState.CONNECTED)
                            "Disconnect"
                        else if (expanded && connectionState == BleManager.ConnectionState.SCANNING)
                            "Cancel"
                        else
                            "Connect"
                    )
                }
            }

            if (expanded && connectionState == BleManager.ConnectionState.SCANNING) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Available devices:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                DeviceList(
                    devices = scannedDevices,
                    onDeviceClick = { device ->
                        onDeviceClick(device)
                        expanded = false
                    },
                    modifier = Modifier.height(300.dp)
                )
            }
        }
    }
}


@Composable
fun SmartCubeControlPanel(
    bleManager: BleManager,
    encryptor: GanGen2Encryptor,
    serviceUuid: UUID,
    commandCharacteristic: BluetoothGattCharacteristic,
    stateCharacteristic: BluetoothGattCharacteristic,
    characteristicData: Map<String, ByteArray>,
    addLog: (String) -> Unit
) {
    val driver = remember { GanGen2ProtocolDriver() }
    val stateData = characteristicData[stateCharacteristic.uuid.toString()]
    val decryptedBytes = remember(stateData) {
        stateData?.let { encryptor.decrypt(it) }
    }

    var lastLogged by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(decryptedBytes) {
        decryptedBytes?.let { db ->
            if (lastLogged == null || !lastLogged!!.contentEquals(db)) {
                val detailLines = db.mapIndexed { i, b ->
                    val hex = String.format("%02x", b)
                    val bin = Integer.toBinaryString(b.toInt() and 0xFF)
                        .padStart(8, '0')
                    "Byte $i:  $hex = $bin"
                }.joinToString("\n")
                addLog("Decrypted bytes:\n$detailLines")
                lastLogged = db
            }
        }
    }

    var latestEvent by remember { mutableStateOf<GanCubeEvent?>(null) }
    LaunchedEffect(decryptedBytes) {
        decryptedBytes?.let { db ->
            val evs = driver.handleStateEvent(db)
            if (evs.isNotEmpty()) {
                val ev = evs.last()
                latestEvent = ev
                addLog("Parsed event: $ev")
            }
        }
    }

    val encryptedHexData = stateData?.let { bytesToHex(it) }
        ?: "No encrypted data received yet"
    val decryptedHexData = decryptedBytes?.let { bytesToHex(it) }
        ?: "No decrypted data available"

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Cube characteristics",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("Service: $serviceUuid")
                Text("State char: ${stateCharacteristic.uuid}")
                Text("Command char: ${commandCharacteristic.uuid}")
            }
        }

        // State data display
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Cube state data",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("Encrypted data:", fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(encryptedHexData)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text("Decrypted data:", fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(decryptedHexData)
                }

                Button(
                    onClick = {
                        bleManager.enableNotifications(
                            serviceUuid,
                            stateCharacteristic.uuid
                        )
                        addLog("Refreshed notifications for state characteristic")
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Refresh notifications")
                }
            }
        }

        // Send command panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Send command",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // helper local function
                fun send(cmd: GanCubeCommand, label: String) {
                    val msg = driver.createCommandMessage(cmd)
                    if (msg != null) {

                        val encrypted = encryptor.encrypt(msg)
                        bleManager.writeCharacteristic(
                            serviceUuid,
                            commandCharacteristic.uuid,
                            encrypted
                        )
                        addLog("Sent $label: ${bytesToHex(encrypted)}")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { send(GanCubeCommand.RequestFacelets, "Request facelets") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Facelets")
                    }
                    Button(
                        onClick = { send(GanCubeCommand.RequestHardware, "Request hardware") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Hardware")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { send(GanCubeCommand.RequestBattery, "Request battery") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Battery")
                    }
                    Button(
                        onClick = { send(GanCubeCommand.RequestReset, "Request reset") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Latest event",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (latestEvent == null) {
                    Text("No event parsed yet")
                } else {
                    when (val ev = latestEvent!!) {
                        is GanCubeEvent.Gyro -> {
                            Text("Type: GYRO")
                            Text(
                                "Quaternion: x=${"%.3f".format(ev.quaternion.x)}, " +
                                        "y=${"%.3f".format(ev.quaternion.y)}, " +
                                        "z=${"%.3f".format(ev.quaternion.z)}, " +
                                        "w=${"%.3f".format(ev.quaternion.w)}"
                            )
                            Text(
                                "Velocity: x=${"%.3f".format(ev.velocity.x)}, " +
                                        "y=${"%.3f".format(ev.velocity.y)}, " +
                                        "z=${"%.3f".format(ev.velocity.z)}"
                            )
                        }

                        is GanCubeEvent.Move -> {
                            val faces = "URFDLB"
                            val faceChar = faces.getOrNull(ev.face) ?: '?'
                            val suffix = if (ev.direction == 1) "'" else ""
                            val moveNotation = "$faceChar$suffix"

                            Text("Type: MOVE")
                            Text("Move: $moveNotation")
                            Text("Serial: ${ev.serial}")
                            Text("Cube timestamp: ${ev.cubeTimestamp}")
                        }

                        is GanCubeEvent.Facelets -> {
                            Text("Type: FACELETS")
                            Text("Serial: ${ev.serial}")
                            Text("CP: ${ev.cp}")
                            Text("CO: ${ev.co}")
                            Text("EP: ${ev.ep}")
                            Text("EO: ${ev.eo}")
                        }

                        is GanCubeEvent.Hardware -> {
                            Text("Type: HARDWARE")
                            Text("Name: ${ev.name}")
                            Text("HW version: ${ev.hwVersion}")
                            Text("SW version: ${ev.swVersion}")
                            Text("Gyro supported: ${ev.gyroSupported}")
                        }

                        is GanCubeEvent.Battery -> {
                            Text("Type: BATTERY")
                            Text("Level: ${ev.batteryLevel}%")
                        }

                        is GanCubeEvent.Disconnect -> {
                            Text("Type: DISCONNECT")
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun DeviceList(
    devices: List<BluetoothDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    if (devices.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Scanning for devices...")
        }
    } else {
        // Sort devices so GAN cubes (starting with AB:12:34) appear at the top
        val sortedDevices = remember(devices) {
            devices.sortedWith { device1, device2 ->
                val isGan1 = device1.address.startsWith("AB:12:34")
                val isGan2 = device2.address.startsWith("AB:12:34")

                when {
                    isGan1 && !isGan2 -> -1  // GAN devices first
                    !isGan1 && isGan2 -> 1   // Non-GAN devices after
                    else -> 0                // Keep original order within groups
                }
            }
        }

        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedDevices) { device ->
                val deviceName = if (ActivityCompat.checkSelfPermission(
                        LocalContext.current,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    device.name ?: "Unknown device"
                } else {
                    "Unknown device"
                }

                // Add a visual indicator for GAN cubes
                val isGanCube = device.address.startsWith("AB:12:34")

                Button(
                    onClick = { onDeviceClick(device) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isGanCube) {
                        // Highlight GAN cubes with a different button color
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )

                            if (isGanCube) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "• GAN",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogDisplay(logMessages: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Log",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (logMessages.isEmpty()) {
                        Text("No log messages yet")
                    } else {
                        logMessages.forEach { message ->
                            Text(text = message, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

// Helper function to get readable property names
fun getPropertyNames(properties: Int): String {
    val props = mutableListOf<String>()

    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WRITE_NO_RESPONSE")
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")

    return props.joinToString(", ")
}

fun bytesToHex(bytes: ByteArray): String {
    val hexChars = "0123456789ABCDEF".toCharArray()
    val result = StringBuilder(bytes.size * 2)

    bytes.forEach { byte ->
        val i = byte.toInt() and 0xFF
        result.append(hexChars[i shr 4])
        result.append(hexChars[i and 0x0F])
    }

    return result.toString()
}