package com.example.mdp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.DragEvent
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
import android.util.Log
import android.view.*
import kotlin.math.roundToInt
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
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

    // region UI
    private lateinit var btnConnect: Button
    private lateinit var btnClear: Button
    private lateinit var btnSend: Button
    private lateinit var etMessage: EditText
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var bottomIcon: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvRobotStatus: TextView

    // Directional buttons
    private lateinit var btnUp: Button
    private lateinit var btnDown: Button
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnRotateLeft: Button
    private lateinit var btnRotateRight: Button

    // Palette references
    private lateinit var paletteObstacleLabel: TextView
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            Log.i("MainActivity", "BLUETOOTH_CONNECT granted = $granted")
        }

    private lateinit var gridContainer: FrameLayout
    private lateinit var gridView: GridWithAxesView
    private var nextObstacleId = 1
    private var hasRobot = false

    // Robot state for local simulation
    private var robotCol: Int? = null // bottom-left col
    private var robotRow: Int? = null // bottom-left row
    private var robotDirection: String = "N" // "N", "E", "S", "W"
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
        tvRobotStatus = findViewById(R.id.tvRobotStatus)
        paletteObstacleLabel = findViewById(R.id.palette_obstacle_label)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnConnect.setOnClickListener { onConnectClicked() }
        bottomIcon.setOnClickListener { onConnectClicked() }
        btnSend.setOnClickListener { onSendClicked() }
        btnClear.setOnClickListener { onClearClicked() }

        //Directional buttons
        btnUp = findViewById(R.id.btnUp)
        btnDown = findViewById(R.id.btnDown)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnRotateLeft = findViewById(R.id.btnRotateLeft)
        btnRotateRight = findViewById(R.id.btnRotateRight)

        // Add button listeners
        btnUp.setOnClickListener { moveRobot("f") }
        btnDown.setOnClickListener { moveRobot("r") }
        btnLeft.setOnClickListener { moveRobot("sl") }
        btnRight.setOnClickListener { moveRobot("sr") }
        btnRotateLeft.setOnClickListener { moveRobot("tl") }
        btnRotateRight.setOnClickListener { moveRobot("tr") }

        gridContainer = findViewById(R.id.grid_view_container)
        gridView = GridWithAxesView(this)
        gridView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        gridContainer.addView(gridView)

        // Palette拖拽逻辑 - Obstacle
        paletteObstacleLabel.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val dragData = ClipData.newPlainText("obstacle", nextObstacleId.toString())
                    val shadow = View.DragShadowBuilder(v)
                    v.startDragAndDrop(dragData, shadow, null, 0)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        // Palette拖拽逻辑 - Robot
        val paletteRobotImage = findViewById<ImageView>(R.id.palette_robot_image)
        paletteRobotImage.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hasRobot) {
                        val dragData = ClipData.newPlainText("robot", "robot")
                        val shadow = View.DragShadowBuilder(v)
                        v.startDragAndDrop(dragData, shadow, null, 0)
                    } else {
                        toast("Robot already placed! Clear to place a new one.")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        // gridContainer接收拖拽
        gridContainer.setOnDragListener { v: View, event: DragEvent ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> true
                DragEvent.ACTION_DRAG_EXITED -> true
                DragEvent.ACTION_DROP -> {
                    val x = event.x
                    val y = event.y
                    val clipData = event.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val draggedData = clipData.getItemAt(0).text.toString()
                        if (draggedData == "robot") {
                            createAndStartRobotDrag(x, y)
                        } else {
                            createAndStartDragFromPalette(x, y)
                        }
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }

        // runtime permission request for Bluetooth on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            }
        }

        registerAclReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        unregisterDiscoveryReceiverIfNeeded()
        unregisterBondStateReceiver()
        unregisterAclReceiver()
        closeConnection()
        stopReconnectJob()
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

    private fun onClearClicked() {
        tvLog.text = ""
        appendLog("[System] Log cleared")
        clearAllObstacles()
        clearRobot()
        nextObstacleId = 1
        updatePaletteLabel()
        appendLog("[System] All obstacles and robot cleared")
    }

    private fun clearAllObstacles() {
        val childrenToRemove = mutableListOf<View>()
        for (i in 0 until gridContainer.childCount) {
            val child = gridContainer.getChildAt(i)
            if (child is ObstacleView) {
                childrenToRemove.add(child)
            }
        }
        childrenToRemove.forEach {
            gridContainer.removeView(it)
        }
    }

    private fun clearRobot() {
        val childrenToRemove = mutableListOf<View>()
        for (i in 0 until gridContainer.childCount) {
            val child = gridContainer.getChildAt(i)
            if (child is RobotView) {
                childrenToRemove.add(child)
            }
        }
        childrenToRemove.forEach {
            gridContainer.removeView(it)
        }
        hasRobot = false
    }

    private fun updatePaletteLabel() {
        paletteObstacleLabel.text = nextObstacleId.toString()
    }
    // endregion

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

        // Check permission first
        if (!hasBtConnectPermission()) {
            appendLog("[Bond] Missing BLUETOOTH_CONNECT permission")
            toast("Bluetooth connect permission required")
            requestAllPermissions()
            return
        }

        try {
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
        } catch (se: SecurityException) {
            appendLog("[Bond] Permission error: ${se.message}")
            toast("Permission error: ${se.message}")
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
            try { unregisterReceiver(it) } catch (_: Exception) {}
            bondStateReceiver = null
        }
    }
    // endregion

    // region Connect / Disconnect
    private fun connectToDevice(device: BluetoothDevice) {
        stopDiscovery()
        unregisterDiscoveryReceiverIfNeeded()
        closeConnection()
        stopReconnectJob()
        lastConnectedDevice = device
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
            } catch (_: TimeoutCancellationException) {
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
        // Check for BLUETOOTH_CONNECT permission first
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("BLUETOOTH_CONNECT permission required")
        }
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
                            processIncomingMessage(text)
                        }
                    } else if (n < 0) {
                        throw IOException("Stream closed")
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    appendLog("[Conn] Disconnected: ${e.message}")
                    updateStatus("Disconnected - attempting to reconnect...")
                }
                closeConnection()
                startReconnectJob()
            }
        }
    }

    private fun processIncomingMessage(message: String) {
        try {
            val jsonObject = JSONObject(message.trim())
            if (jsonObject.has("status")) {
                val status = jsonObject.getString("status").uppercase(Locale.ROOT)
                updateRobotStatus(status)
                return
            }
        } catch (_: Exception) {
            // 不是JSON或解析出错，按普通消息处理
        }
        appendLog("[Robot -> AA] $message")
    }

    private fun updateRobotStatus(status: String) {
        runOnUiThread {
            tvRobotStatus.text = status
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

    private fun sendRobotCommand(command: String) {
        if (bluetoothSocket == null) {
            toast("Not connected to robot")
            return
        }
        sendData(command)
        appendLog("[Control] Sent command: $command")
    }
    // endregion


    private fun moveRobot(command: String) {
        // Find the RobotView
        val robotView = (0 until gridContainer.childCount)
            .map { gridContainer.getChildAt(it) }
            .find { it is RobotView } as? RobotView ?: run {
            toast("Robot not placed on grid")
            return
        }
        // If first move, initialize state from view position
        if (robotCol == null || robotRow == null) {
            val centerX = robotView.x + robotView.width / 2f - gridView.x
            val centerY = robotView.y + robotView.height / 2f - gridView.y
            val cell = gridView.pixelToCell(centerX, centerY)
            if (cell != null) {
                robotCol = cell.first - 1
                robotRow = cell.second - 1
            } else {
                toast("Robot is outside grid")
                return
            }
        }
        // Update direction and position
        var col = robotCol!!
        var row = robotRow!!
        var dir = robotDirection
        when (command) {
            "f" -> { // move forward
                when (dir) {
                    "N" -> row += 1
                    "E" -> col += 1
                    "S" -> row -= 1
                    "W" -> col -= 1
                }
            }
            "r" -> { // move backward
                when (dir) {
                    "N" -> row -= 1
                    "E" -> col -= 1
                    "S" -> row += 1
                    "W" -> col += 1
                }
            }
            "tl" -> { // turn left
                dir = when (dir) {
                    "N" -> "W"
                    "W" -> "S"
                    "S" -> "E"
                    "E" -> "N"
                    else -> dir
                }
            }
            "tr" -> { // turn right
                dir = when (dir) {
                    "N" -> "E"
                    "E" -> "S"
                    "S" -> "W"
                    "W" -> "N"
                    else -> dir
                }
            }
            "sl" -> { // strafe left
                when (dir) {
                    "N" -> col -= 1
                    "E" -> row += 1
                    "S" -> col += 1
                    "W" -> row -= 1
                }
            }
            "sr" -> { // strafe right
                when (dir) {
                    "N" -> col += 1
                    "E" -> row -= 1
                    "S" -> col -= 1
                    "W" -> row += 1
                }
            }
        }
        // Clamp to grid bounds (assume grid is 20x20, bottom-left is (0,0))
        col = col.coerceIn(0, 17) // 3x3 robot, so max col is 17
        row = row.coerceIn(0, 17)
        robotCol = col
        robotRow = row
        robotDirection = dir
        // Move robotView
        val center = gridView.cellCenterPixels(col + 1, row + 1)
        val size = robotView.width
        robotView.x = center.first - size / 2f
        robotView.y = center.second - size / 2f
        // Optionally, rotate robotView (if you have a direction indicator)
        robotView.rotation = when (dir) {
            "N" -> 0f
            "E" -> 90f
            "S" -> 180f
            "W" -> 270f
            else -> 0f
        }
        // Log position
        val logMsg = "[Local] Robot position: ($dir, $col, $row)"
        appendLog(logMsg)
        // Send to robot if connected
        if (bluetoothSocket != null) {
            val payload = "ROBOT;$col;$row;$dir\n"
            sendData(payload)
            appendLog("[AA -> Robot] $command -> ($dir, $col, $row)")
        }
    }
    // region Drag and Drop Functions
    private fun createAndStartRobotDrag(x: Float, y: Float) {
        if (hasRobot) {
            toast("Robot already placed! Clear to place a new one.")
            return
        }
        try {
            val robotView = RobotView(this).apply {
                id = View.generateViewId()
            }
            gridView.post {
                val cellSize = gridView.getCellSizePx()
                val size = if (cellSize > 0) (cellSize * 3).roundToInt() else 240
                val lp = FrameLayout.LayoutParams(size, size)
                gridContainer.addView(robotView, lp)

                val gridLocalX = x
                val gridLocalY = y

                Log.i("MainActivity", "Robot coordinates: x=$x, y=$y")

                val cell = gridView.pixelToCell(gridLocalX, gridLocalY)
                if (cell != null) {
                    val (col, row) = cell
                    val (cx, cy) = gridView.cellCenterPixels(col, row)
                    val targetX = cx - size / 2f
                    val targetY = cy - size / 2f

                    robotView.x = targetX
                    robotView.y = targetY
                    hasRobot = true

                    // Default direction is 'N' (North) on placement
                    val direction = "N"
                    val bottomLeftCol = col - 1
                    val bottomLeftRow = row - 1
                    val payload = "ROBOT;$bottomLeftCol;$bottomLeftRow;$direction\n"
                    if (bluetoothSocket != null) {
                        sendData(payload)
                        appendLog("[AA -> Robot] Robot position: ($direction, $bottomLeftCol, $bottomLeftRow)")
                    } else {
                        appendLog("[AA] Robot position: ($direction, $bottomLeftCol, $bottomLeftRow)")
                    }
                } else {
                    gridContainer.removeView(robotView)
                    hasRobot = false
                    toast("Robot removed - dropped outside grid")
                    return@post
                }

                attachRobotDragHandler(robotView)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating robot: ${e.message}")
            toast("Failed to create robot: ${e.message}")
        }
    }

    private fun attachRobotDragHandler(robot: View) {
        var dX = 0f
        var dY = 0f
        var isDragging = false
        var direction = "N" // Default direction

        robot.setOnTouchListener { v, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.bringToFront()
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragging && (Math.abs(v.x - (event.rawX + dX)) > 10 ||
                                    Math.abs(v.y - (event.rawY + dY)) > 10)) {
                            isDragging = true
                        }
                        val newX = event.rawX + dX
                        val newY = event.rawY + dY
                        v.x = newX
                        v.y = newY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isDragging) {
                            v.performClick()
                        } else {
                            val robotCenterX = v.x + v.width / 2f
                            val robotCenterY = v.y + v.height / 2f
                            val gridLocalX = robotCenterX - gridView.x
                            val gridLocalY = robotCenterY - gridView.y

                            val cell = gridView.pixelToCell(gridLocalX, gridLocalY)
                            if (cell == null) {
                                (v.parent as FrameLayout).removeView(v)
                                hasRobot = false
                                toast("Robot removed - dropped outside grid")
                            } else {
                                val (col, row) = cell
                                val (cx, cy) = gridView.cellCenterPixels(col, row)
                                val targetX = gridView.x + cx - v.width / 2f
                                val targetY = gridView.y + cy - v.height / 2f

                                v.x = targetX
                                v.y = targetY

                                // Calculate bottom-left corner coordinates for the 3x3 robot
                                val bottomLeftCol = col - 1
                                val bottomLeftRow = row - 1

                                // Direction can be changed by rotation buttons, for now keep 'N'
                                val payload = "ROBOT;$bottomLeftCol;$bottomLeftRow;$direction\n"
                                if (bluetoothSocket != null) {
                                    sendData(payload)
                                    appendLog("[AA -> Robot] Robot position: ($direction, $bottomLeftCol, $bottomLeftRow)")
                                } else {
                                    appendLog("[AA] Robot position: ($direction, $bottomLeftCol, $bottomLeftRow)")
                                }
                                toast("Robot placed at ($direction, $bottomLeftCol, $bottomLeftRow)")
                            }
                        }
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in robot touch handling: ${e.message}")
                false
            }
        }

        robot.setOnClickListener {
            toast("Robot clicked")
        }
    }

    private fun createAndStartDragFromPalette(x: Float, y: Float) {
        try {
            val obs = ObstacleView(this).apply {
                id = View.generateViewId()
                setNumber(nextObstacleId++)
            }
            gridView.post {
                val cellSize = gridView.getCellSizePx()
                val size = if (cellSize > 0) cellSize.roundToInt() else 80
                val lp = FrameLayout.LayoutParams(size, size)
                gridContainer.addView(obs, lp)

                val gridLocalX = x
                val gridLocalY = y

                Log.i("MainActivity", "Drop coordinates: x=$x, y=$y")

                val cell = gridView.pixelToCell(gridLocalX, gridLocalY)
                if (cell != null) {
                    val (col, row) = cell
                    val (cx, cy) = gridView.cellCenterPixels(col, row)

                    val targetX = cx - size / 2f
                    val targetY = cy - size / 2f

                    obs.x = targetX
                    obs.y = targetY

                    val payload = "OBS;${obs.id};$col;$row\n"
                    if (bluetoothSocket != null) {
                        sendData(payload)
                        appendLog("[AA -> Robot] Obstacle position: (${obs.id}, $col, $row)")
                    } else {
                        appendLog("[AA] Obstacle position: (${obs.id}, $col, $row)")
                    }
                } else {
                    gridContainer.removeView(obs)
                    toast("Obstacle removed - dropped outside grid")
                    return@post
                }

                attachDragHandlers(obs)
            }
            updatePaletteLabel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating obstacle: ${e.message}")
            toast("Failed to create obstacle: ${e.message}")
        }
    }

    private fun attachDragHandlers(obstacle: View) {
        var dX = 0f
        var dY = 0f
        var isDragging = false

        obstacle.setOnTouchListener { v, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.bringToFront()
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragging && (Math.abs(v.x - (event.rawX + dX)) > 10 ||
                                    Math.abs(v.y - (event.rawY + dY)) > 10)) {
                            isDragging = true
                        }
                        val newX = event.rawX + dX
                        val newY = event.rawY + dY
                        v.x = newX
                        v.y = newY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isDragging) {
                            v.performClick()
                        } else {
                            val obstacleCenterX = v.x + v.width / 2f
                            val obstacleCenterY = v.y + v.height / 2f
                            val gridLocalX = obstacleCenterX - gridView.x
                            val gridLocalY = obstacleCenterY - gridView.y

                            val cell = gridView.pixelToCell(gridLocalX, gridLocalY)
                            if (cell == null) {
                                (v.parent as FrameLayout).removeView(v)
                                toast("Obstacle removed - dropped outside grid")
                            } else {
                                val (col, row) = cell
                                val (cx, cy) = gridView.cellCenterPixels(col, row)
                                val targetX = gridView.x + cx - v.width / 2f
                                val targetY = gridView.y + cy - v.height / 2f

                                v.x = targetX
                                v.y = targetY

                                val payload = "OBS;${v.id};$col;$row\n"
                                if (bluetoothSocket != null) {
                                    sendData(payload)
                                    appendLog("[AA -> Robot] Obstacle position: (${v.id}, $col, $row)")
                                } else {
                                    appendLog("[AA] Obstacle position: (${v.id}, $col, $row)")
                                }
                                toast("Obstacle placed at (ID: ${v.id}, $col, $row)")
                            }
                        }
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in touch handling: ${e.message}")
                false
            }
        }

        obstacle.setOnClickListener {
            toast("Obstacle ${(obstacle as? ObstacleView)?.getNumber() ?: ""} clicked")
        }
    }
    // endregion

    // --- Robust Bluetooth Reconnection Logic ---
    private var reconnectJob: Job? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var aclReceiver: BroadcastReceiver? = null

    private fun startReconnectJob() {
        stopReconnectJob()
        val device = lastConnectedDevice ?: return
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(3000) // Try every 3 seconds
                if (bluetoothSocket == null) {
                    withContext(Dispatchers.Main) {
                        updateStatus("Reconnecting to ${safeDeviceName(device)} ...")
                        appendLog("[Conn] Attempting reconnection...")
                    }
                    try {
                        connectToDevice(device)
                        if (bluetoothSocket != null) {
                            withContext(Dispatchers.Main) {
                                updateStatus("Reconnected to ${safeDeviceName(device)}")
                                appendLog("[Conn] Reconnected!")
                            }
                            break
                        }
                    } catch (_: Exception) {
                        // Ignore, will retry
                    }
                } else {
                    break // Already connected
                }
            }
        }
    }

    private fun stopReconnectJob() {
        reconnectJob?.cancel(); reconnectJob = null
    }

    private fun registerAclReceiver() {
        if (aclReceiver != null) return
        aclReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                val device = getDeviceFromIntent(intent)
                if (device == null || lastConnectedDevice == null) return
                if (device.address != lastConnectedDevice?.address) return
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        appendLog("[ACL] Device connected: ${safeDeviceName(device)}")
                        if (bluetoothSocket == null) {
                            startReconnectJob()
                        }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        appendLog("[ACL] Device disconnected: ${safeDeviceName(device)}")
                        closeConnection()
                        startReconnectJob()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(aclReceiver, filter)
    }

    private fun unregisterAclReceiver() {
        aclReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            aclReceiver = null
        }
    }
    // --- End Robust Bluetooth Reconnection Logic ---
}
