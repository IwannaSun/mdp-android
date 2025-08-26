package com.example.mdp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {

    // region Bluetooth core

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = getSystemService(BluetoothManager::class.java)
        mgr?.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var connectJob: Job? = null
    private var pendingDevice: BluetoothDevice? = null
    private var bondStateReceiver: BroadcastReceiver? = null

    // endregion

    // region UI

    private lateinit var btnConnect: Button
    private lateinit var btnClear: Button
    private lateinit var btnSend: Button
    private lateinit var etMessage: EditText
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var bottomIcon: ImageView
    private lateinit var tvStatus: TextView

    // endregion

    // region Discovery state

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private var receiverRegistered = false
    private var currentListAdapter: ArrayAdapter<String>? = null

    // endregion

    // region Result launchers

    private val requestBtEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Will manually trigger again after user returns */ }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* Same as above */ }

    // endregion

    // region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)
        btnClear = findViewById(R.id.btnClear)
        etMessage = findViewById(R.id.etMessage)
        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)
        bottomIcon = findViewById(R.id.bottomIcon)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnConnect.setOnClickListener { onConnectClicked() }
        bottomIcon.setOnClickListener { onConnectClicked() }
        btnSend.setOnClickListener { onSendClicked() }
        btnClear.setOnClickListener { onClearClicked() }

        updateStatus("Idle")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        unregisterDiscoveryReceiverIfNeeded()
        unregisterBondStateReceiver()
        closeConnection()
    }

    // endregion

    // region UI actions

    private fun onConnectClicked() {
        if (!ensureBluetoothReady()) return
        showDevicePickerDialog()
    }

    private fun onSendClicked() {
        val msg = etMessage.text.toString().trim()
        if (msg.isEmpty()) return
        sendData("$msg\n")
        appendLog("[AA -> Robot] $msg")
        etMessage.setText("")
    }

    // endregion
    private fun onClearClicked() {
        tvLog.text = ""
        appendLog("[System] Log cleared")
    }

    // region Permissions / readiness

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBtScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestAllPermissions() {
        requestPermissionsLauncher.launch(requiredPermissions())
    }

    private fun ensureBluetoothReady(): Boolean {
        if (bluetoothAdapter == null) {
            toast("Device does not support Bluetooth.")
            return false
        }
        if (!hasAllRequiredPermissions()) {
            requestAllPermissions()
            return false
        }
        if (bluetoothAdapter?.isEnabled != true) {
            requestBtEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }

    // endregion

    // region Discovery / Device picker

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try { device.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
    }

    private fun safeDeviceAddress(device: BluetoothDevice): String {
        return try { device.address ?: "UNKNOWN" } catch (_: SecurityException) { "UNKNOWN" }
    }

    private fun bondedDevicesSafe(): Set<BluetoothDevice> {
        if (!hasBtConnectPermission()) return emptySet()
        return try { bluetoothAdapter?.bondedDevices ?: emptySet() } catch (_: SecurityException) { emptySet() }
    }

    private fun getDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun containsDeviceAddress(address: String): Boolean {
        return discoveredDevices.values.any {
            try { it.address == address } catch (_: SecurityException) { false }
        }
    }

    private fun showDevicePickerDialog() {
        discoveredDevices.clear()

        val items = ArrayList<String>()
        for (device in bondedDevicesSafe()) {
            val name = safeDeviceName(device)
            val addr = safeDeviceAddress(device)
            val key = "$name\n$addr"
            discoveredDevices[key] = device
            items.add(key)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        currentListAdapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setAdapter(adapter) { d, which ->
                val key = adapter.getItem(which) ?: return@setAdapter
                val device = discoveredDevices[key] ?: return@setAdapter
                d.dismiss()
                // Modified: Check bond state before connecting
                connectWithBondCheck(device)
            }
            .setNegativeButton("Close", null)
            .setPositiveButton("Scan", null)
            .setNeutralButton("Stop", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startDiscoveryAndKeepDialogOpen()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                stopDiscovery()
            }
        }
        dialog.setOnDismissListener {
            stopDiscovery()
        }

        dialog.show()
        startDiscoveryAndKeepDialogOpen()
    }

    private fun startDiscoveryAndKeepDialogOpen() {
        if (!hasAllRequiredPermissions()) {
            requestAllPermissions()
            return
        }
        try {
            registerDiscoveryReceiverIfNeeded()

            if (bluetoothAdapter?.isDiscovering == true) {
                appendLog("[Scan] Discovery already running.")
                return
            }

            safeCancelDiscovery()

            val started = if (hasBtScanPermission()) {
                bluetoothAdapter?.startDiscovery() == true
            } else false

            if (started) {
                appendLog("[Scan] Discovery started...")
            } else {
                appendLog("[Scan] Discovery start failed.")
            }
        } catch (e: SecurityException) {
            toast("No permission to scan: ${e.message}")
        }
    }

    private fun stopDiscovery() {
        safeCancelDiscovery()
    }

    private fun safeCancelDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true && hasBtScanPermission()) {
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        }
    }

    private fun registerDiscoveryReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterDiscoveryReceiverIfNeeded() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (_: IllegalArgumentException) {
        } finally {
            receiverRegistered = false
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    appendLog("[Scan] Discovery started (broadcast).")
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = getDeviceFromIntent(intent) ?: return
                    val name = safeDeviceName(device)
                    val addr = safeDeviceAddress(device)
                    if (addr == "UNKNOWN") return
                    if (!containsDeviceAddress(addr)) {
                        val key = "$name\n$addr"
                        discoveredDevices[key] = device
                        currentListAdapter?.let { a ->
                            runOnUiThread {
                                a.add(key)
                                a.notifyDataSetChanged()
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    appendLog("[Scan] Discovery finished.")
                }
            }
        }
    }

    // endregion

    // region Bond Management

    private fun connectWithBondCheck(device: BluetoothDevice) {
        pendingDevice = device

        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                // Already paired, connect directly
                appendLog("[Bond] Device already paired, connecting...")
                connectToDevice(device)
            }
            BluetoothDevice.BOND_NONE -> {
                // Not paired, initiate pairing first
                registerBondStateReceiver()
                appendLog("[Bond] Starting pairing with ${safeDeviceName(device)}")
                try {
                    device.createBond()
                } catch (e: Exception) {
                    appendLog("[Bond] Failed: ${e.message}")
                    toast("Pairing failed: ${e.message}")
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                // Pairing in progress, wait for result
                registerBondStateReceiver()
                appendLog("[Bond] Pairing in progress...")
            }
        }
    }

    private fun registerBondStateReceiver() {
        if (bondStateReceiver != null) return

        bondStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) return

                val device = getDeviceFromIntent(intent) ?: return
                if (pendingDevice?.address != device.address) return

                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        appendLog("[Bond] Pairing successful, proceeding to connect")
                        unregisterBondStateReceiver()
                        connectToDevice(device)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        appendLog("[Bond] Pairing failed or cancelled")
                        unregisterBondStateReceiver()
                    }
                }
            }
        }

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun unregisterBondStateReceiver() {
        bondStateReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
            bondStateReceiver = null
        }
    }

    // endregion

    // region Connect / Disconnect

    private fun connectToDevice(device: BluetoothDevice) {
        stopDiscovery()
        unregisterDiscoveryReceiverIfNeeded()
        closeConnection()
        updateStatus("Connecting to ${safeDeviceName(device)} ...")
        appendLog("[Conn] Connecting to ${safeDeviceName(device)}")

        connectJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!hasBtConnectPermission()) {
                    withContext(Dispatchers.Main) {
                        updateStatus("Permission error")
                        appendLog("[Conn] Missing BLUETOOTH_CONNECT")
                        toast("Need BLUETOOTH_CONNECT permission")
                    }
                    return@launch
                }

                safeCancelDiscovery()

                val socket = tryConnectWithFallback(device, timeoutSeconds = 10)
                    ?: throw IOException("Unable to open RFCOMM socket. Make sure the PC has SPP service enabled.")

                bluetoothSocket = socket

                withContext(Dispatchers.Main) {
                    updateStatus("Connected to ${safeDeviceName(device)}")
                    appendLog("[Conn] Connected to ${safeDeviceName(device)}")
                    toast("Connected to ${safeDeviceName(device)}")
                    btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_green_light, null))
                }

                startReadLoop()
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    updateStatus("Connection failed")
                    appendLog("[Conn] Failed: ${e.message}")
                    toast("Connection failed: ${e.message}")
                    if (e.message?.contains("RFCOMM", ignoreCase = true) == true) {
                        toast("Make sure the PC has SPP service enabled")
                        appendLog("[Tip] For Windows: Setup an incoming COM port in Bluetooth settings")
                    }
                    closeConnection()
                }
            } catch (e: TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    updateStatus("Connection timeout")
                    appendLog("[Conn] Timeout")
                    toast("Connection timeout")
                    closeConnection()
                }
            } catch (se: SecurityException) {
                withContext(Dispatchers.Main) {
                    updateStatus("Permission error")
                    appendLog("[Conn] Permission error: ${se.message}")
                    toast("Permission error: ${se.message}")
                    closeConnection()
                }
            }
        }
    }

    private suspend fun tryConnectWithFallback(
        device: BluetoothDevice,
        timeoutSeconds: Int
    ): BluetoothSocket? {
        // Try insecure connection first
        val insecure = runCatching {
            device.createInsecureRfcommSocketToServiceRecord(sppUuid)
        }.getOrNull()

        insecure?.let { sock ->
            runCatching {
                withTimeout(timeoutSeconds.seconds) { sock.connect() }
            }.onSuccess {
                appendLog("[Conn] Connected using insecure RFCOMM")
                return sock
            }.onFailure {
                appendLog("[Conn] Insecure connection failed, trying secure...")
                runCatching { sock.close() }
            }
        }

        // Try secure connection
        val secure = runCatching {
            device.createRfcommSocketToServiceRecord(sppUuid)
        }.getOrNull()

        secure?.let { sock ->
            runCatching {
                withTimeout(timeoutSeconds.seconds) { sock.connect() }
            }.onSuccess {
                appendLog("[Conn] Connected using secure RFCOMM")
                return sock
            }.onFailure {
                appendLog("[Conn] Secure connection also failed")
                runCatching { sock.close() }
            }
        }

        // Try fallback method using reflection (last resort)
        try {
            appendLog("[Conn] Trying fallback method...")
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val fallbackSocket = m.invoke(device, 1) as BluetoothSocket
            withTimeout(timeoutSeconds.seconds) { fallbackSocket.connect() }
            appendLog("[Conn] Connected using fallback method")
            return fallbackSocket
        } catch (e: Exception) {
            appendLog("[Conn] Fallback method failed: ${e.javaClass.simpleName}")
        }

        return null
    }

    private fun closeConnection() {
        readJob?.cancel(); readJob = null
        connectJob?.cancel(); connectJob = null
        try { bluetoothSocket?.close() } catch (_: IOException) { }
        bluetoothSocket = null

        runCatching {
            runOnUiThread {
                btnConnect.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            }
        }
    }

    // endregion

    // region Read / Write

    private fun startReadLoop() {
        val socket = bluetoothSocket ?: return
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            try {
                val input = socket.inputStream
                while (isActive) {
                    val n = input.read(buffer)
                    if (n > 0) {
                        val text = String(buffer, 0, n, Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            appendLog("[Robot -> AA] $text")
                        }
                    } else if (n < 0) {
                        throw IOException("Stream closed")
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    appendLog("[Conn] Disconnected: ${e.message}")
                    updateStatus("Disconnected")
                }
                closeConnection()
            }
        }
    }

    private fun sendData(text: String) {
        val socket = bluetoothSocket
        if (socket == null) {
            toast("Not connected.")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                socket.outputStream.write(text.toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    appendLog("[Send] Failed: ${e.message}")
                    toast("Send failed: ${e.message}")
                }
            }
        }
    }

    // endregion

    // region Helpers

    private fun appendLog(line: String) {
        val msg = if (line.endsWith("\n")) line else "$line\n"
        runOnUiThread {
            tvLog.append(msg)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            tvStatus.text = text
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // endregion
}