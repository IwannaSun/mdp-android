package com.example.mdp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
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

    // Obstacle direction buttons
    private lateinit var btnObstacleDirectionNorth: Button
    private lateinit var btnObstacleDirectionEast: Button
    private lateinit var btnObstacleDirectionSouth: Button
    private lateinit var btnObstacleDirectionWest: Button

    // Current obstacle direction for palette
    private var currentObstacleDirection: String = "N"
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

    // Robust Bluetooth Reconnection Logic
    private var reconnectJob: Job? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var aclReceiver: BroadcastReceiver? = null
    private var acceptJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null

    // region Lifecycle
    @SuppressLint("ClickableViewAccessibility")
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

        // Initialize obstacle direction buttons
        btnObstacleDirectionNorth = findViewById(R.id.btn_obstacle_direction_north)
        btnObstacleDirectionEast = findViewById(R.id.btn_obstacle_direction_east)
        btnObstacleDirectionSouth = findViewById(R.id.btn_obstacle_direction_south)
        btnObstacleDirectionWest = findViewById(R.id.btn_obstacle_direction_west)

        // Set up obstacle direction button listeners
        btnObstacleDirectionNorth.setOnClickListener { setObstacleDirection("N") }
        btnObstacleDirectionEast.setOnClickListener { setObstacleDirection("E") }
        btnObstacleDirectionSouth.setOnClickListener { setObstacleDirection("S") }
        btnObstacleDirectionWest.setOnClickListener { setObstacleDirection("W") }

        // Initialize the button colors
        updateDirectionButtonColors()

        // Set up Connect, Send, and Clear button listeners
        btnConnect.setOnClickListener { onConnectClicked() }
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

        paletteObstacleLabel.setOnClickListener {
            // Handle click if needed
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

        paletteRobotImage.setOnClickListener {
            // Handle click if needed
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

    // 重新计算下一个障碍物ID，基于当前网格中现有的障碍物
    private fun recalculateNextObstacleId() {
        var maxId = 0
        for (i in 0 until gridContainer.childCount) {
            val child = gridContainer.getChildAt(i)
            if (child is ObstacleView) {
                val obstacleNumber = child.getNumber()
                if (obstacleNumber > maxId) {
                    maxId = obstacleNumber
                }
            }
        }
        nextObstacleId = maxId + 1
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
        // Removed stopReconnectJob() call
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
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        // Try secure connection first
        val secureSocket = try {
            device.createRfcommSocketToServiceRecord(sppUuid)
        } catch (e: Exception) {
            appendLog("[Conn] Secure socket creation failed: ${e.message}")
            null
        }

        if (secureSocket != null) {
            try {
                appendLog("[Conn] Trying secure connection...")
                withTimeout(timeoutSeconds.seconds) { secureSocket.connect() }
                return secureSocket
            } catch (e: Exception) {
                appendLog("[Conn] Secure connection failed: ${e.message}")
                runCatching { secureSocket.close() }
            }
        }

        // Try insecure connection
        val insecureSocket = try {
            device.createInsecureRfcommSocketToServiceRecord(sppUuid)
        } catch (e: Exception) {
            appendLog("[Conn] Insecure socket creation failed: ${e.message}")
            null
        }

        if (insecureSocket != null) {
            try {
                appendLog("[Conn] Trying insecure connection...")
                withTimeout(timeoutSeconds.seconds) { insecureSocket.connect() }
                return insecureSocket
            } catch (e: Exception) {
                appendLog("[Conn] Insecure connection failed: ${e.message}")
                runCatching { insecureSocket.close() }
            }
        }

        // Try fallback method using reflection (last resort)
        try {
            appendLog("[Conn] Trying fallback method...")
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val fallbackSocket = m.invoke(device, 1) as BluetoothSocket
            withTimeout(timeoutSeconds.seconds) { fallbackSocket.connect() }
            return fallbackSocket
        } catch (e: Exception) {
            appendLog("[Conn] Fallback method failed: ${e.message}")
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
        val trimmedMessage = message.trim()

        // Handle ROBOT position update messages
        if (trimmedMessage.startsWith("ROBOT,", ignoreCase = true)) {
            try {
                val parts = trimmedMessage.split(",").map { it.trim() }
                if (parts.size >= 4) {
                    val x = parts[1].toInt()
                    val y = parts[2].toInt()
                    val direction = parts[3].uppercase()

                    // Validate direction
                    if (direction in listOf("N", "E", "S", "W")) {
                        updateRobotPositionFromBluetooth(x, y, direction)
                        return
                    } else {
                        appendLog("[Robot -> AA] Invalid direction in ROBOT message: $direction")
                        return
                    }
                }
            } catch (e: NumberFormatException) {
                appendLog("[Robot -> AA] Invalid coordinates in ROBOT message: $trimmedMessage")
                return
            } catch (e: Exception) {
                appendLog("[Robot -> AA] Error parsing ROBOT message: ${e.message}")
                return
            }
        }

        // Handle JSON status messages
        try {
            val jsonObject = JSONObject(trimmedMessage)
            if (jsonObject.has("status")) {
                val status = jsonObject.getString("status").uppercase(Locale.ROOT)
                updateRobotStatus(status)
                return
            }
        } catch (_: Exception) {
            // 不是JSON或解析出错，按普通消息处理
        }

        // Handle TARGET message: TARGET,<obstacle>,<target>
        if (trimmedMessage.startsWith("TARGET,", ignoreCase = true)) {
            try {
                val parts = trimmedMessage.split(",").map { it.trim() }
                if (parts.size >= 3) {
                    val obstacleNum = parts[1].toInt()
                    val targetId = parts[2]
                    // Find the ObstacleView with this number
                    for (i in 0 until gridContainer.childCount) {
                        val child = gridContainer.getChildAt(i)
                        if (child is ObstacleView && child.getNumber() == obstacleNum) {
                            child.setTargetId(targetId)
                            appendLog("[System] Obstacle $obstacleNum updated with Target ID: $targetId")
                            break
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                appendLog("[System] Error parsing TARGET message: ${e.message}")
                return
            }
        }

        // Handle other messages
        appendLog("[Robot -> AA] $trimmedMessage")
    }

    private fun updateRobotPositionFromBluetooth(x: Int, y: Int, direction: String) {
        runOnUiThread {
            // Find existing robot view
            val robotView = (0 until gridContainer.childCount)
                .map { gridContainer.getChildAt(it) }
                .find { it is RobotView } as? RobotView

            if (robotView != null) {
                // Post the update to the view's message queue to ensure it runs after layout.
                robotView.post {
                    // Update robot position on grid
                    val cellSize = gridView.getCellSizePx()
                    val size = robotView.width

                    // Convert robot coordinates to grid position (x,y are bottom-left of 3x3 robot)
                    val centerCol = x + 1  // Center of 3x3 robot
                    val centerRow = y + 1

                    // Check if position is valid
                    if (centerCol >= 1 && centerCol <= gridView.cols - 2 &&
                        centerRow >= 1 && centerRow <= gridView.rows - 2) {

                        val (cx, cy) = gridView.cellCenterPixels(centerCol, centerRow)
                        robotView.x = cx - size / 2f
                        robotView.y = cy - size / 2f

                        // Update robot rotation based on direction
                        robotView.rotation = when (direction) {
                            "N" -> 0f
                            "E" -> 90f
                            "S" -> 180f
                            "W" -> 270f
                            else -> 0f
                        }

                        // Update internal state
                        robotCol = x
                        robotRow = y
                        robotDirection = direction

                        appendLog("[System] Robot position updated via Bluetooth: ($direction, $x, $y)")
                        toast("Robot moved to ($x, $y) facing $direction")
                    } else {
                        appendLog("[Warning] Invalid robot position from Bluetooth: ($x, $y)")
                        toast("Invalid robot position: ($x, $y)")
                    }
                }
            } else {
                // No robot on grid, create one at the specified position
                createRobotAtPosition(x, y, direction)
            }
        }
    }

    private fun sendData(text: String) {
        val socket = bluetoothSocket
        if (socket == null) {
            appendLog("[Send] Not connected")
            return
        }

        try {
            val outputStream = socket.outputStream
            outputStream.write(text.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        } catch (e: IOException) {
            appendLog("[Send] Failed: ${e.message}")
            closeConnection()
            startReconnectJob()
        }
    }

    private fun updateRobotStatus(status: String) {
        runOnUiThread {
            tvRobotStatus.text = "Robot Status: $status"
            appendLog("[Robot Status] $status")
        }
    }

    private fun createRobotAtPosition(x: Int, y: Int, direction: String) {
        if (hasRobot) {
            appendLog("[Warning] Robot already exists, updating position instead")
            return
        }

        try {
            val robotView = RobotView(this).apply {
                id = View.generateViewId()
            }

            val cellSize = gridView.getCellSizePx()
            val size = if (cellSize > 0) (cellSize * 3).roundToInt() else 240
            val lp = FrameLayout.LayoutParams(size, size)
            gridContainer.addView(robotView, lp)

            // Convert robot coordinates to grid position
            val centerCol = x + 1  // Center of 3x3 robot
            val centerRow = y + 1

            // Check if position is valid
            if (centerCol >= 1 && centerCol <= gridView.cols - 2 &&
                centerRow >= 1 && centerRow <= gridView.rows - 2) {

                val (cx, cy) = gridView.cellCenterPixels(centerCol, centerRow)
                robotView.x = cx - size / 2f
                robotView.y = cy - size / 2f

                // Set robot rotation based on direction
                robotView.rotation = when (direction) {
                    "S" -> 180f
                    "W" -> 270f
                    "N" -> 0f
                    "E" -> 90f
                    else -> 0f
                }

                hasRobot = true
                robotCol = x
                robotRow = y
                robotDirection = direction

                attachRobotDragHandler(robotView)

                toast("Robot placed at ($x, $y) facing $direction")
            } else {
                gridContainer.removeView(robotView)
                appendLog("[Warning] Cannot create robot at invalid position: ($x, $y)")
                toast("Cannot place robot at invalid position: ($x, $y)")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating robot from Bluetooth: ${e.message}")
            appendLog("[Error] Failed to create robot: ${e.message}")
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

    // Helper: Get all obstacle positions as Set<Pair<Int, Int>>
    private fun getObstaclePositions(): Set<Pair<Int, Int>> {
        val positions = mutableSetOf<Pair<Int, Int>>()
        for (i in 0 until gridContainer.childCount) {
            val child = gridContainer.getChildAt(i)
            if (child is ObstacleView) {
                // Get center of obstacle
                val centerX = child.x + child.width / 2f - gridView.x
                val centerY = child.y + child.height / 2f - gridView.y
                val cell = gridView.pixelToCell(centerX, centerY)
                if (cell != null) positions.add(cell)
            }
        }
        return positions
    }

    // Helper: Get all robot cells (3x3) given bottom-left col/row
    private fun getRobotCells(bottomLeftCol: Int, bottomLeftRow: Int): Set<Pair<Int, Int>> {
        val cells = mutableSetOf<Pair<Int, Int>>()
        for (dx in 0..2) {
            for (dy in 0..2) {
                cells.add(Pair(bottomLeftCol + dx, bottomLeftRow + dy))
            }
        }
        return cells
    }

    // Helper: Check if robot overlaps any obstacle
    private fun isRobotOverlapping(bottomLeftCol: Int, bottomLeftRow: Int): Boolean {
        val robotCells = getRobotCells(bottomLeftCol, bottomLeftRow)
        val obstacleCells = getObstaclePositions()
        return robotCells.intersect(obstacleCells).isNotEmpty()
    }

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
        // Check for overlap before moving
        if (isRobotOverlapping(col, row)) {
            appendLog("[Warning] Obstacle Ahead!")
            toast("Robot move blocked: Obstacle Present!")
            return
        }
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
            val payload = "ROBOT,$col,$row,$dir\n"
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
                    val bottomLeftCol = col - 1
                    val bottomLeftRow = row - 1
                    val colCount = gridView.cols
                    val rowCount = gridView.rows
                    if (bottomLeftCol < 0 || bottomLeftRow < 0 ||
                        bottomLeftCol > colCount - 3 || bottomLeftRow > rowCount - 3) {
                        gridContainer.removeView(robotView)
                        hasRobot = false
                        toast("Robot cannot be placed at the edge or outside the grid!")
                        return@post
                    }
                    // Check for overlap before placing
                    if (isRobotOverlapping(bottomLeftCol, bottomLeftRow)) {
                        gridContainer.removeView(robotView)
                        hasRobot = false
                        appendLog("[Warning] Robot cannot be placed: overlaps obstacle!")
                        toast("Robot placement blocked: overlaps obstacle")
                        return@post
                    }
                    val (cx, cy) = gridView.cellCenterPixels(col, row)
                    robotView.x = cx - size / 2f
                    robotView.y = cy - size / 2f

                    // Default direction is 'N' (North) on placement
                    val robotDirection = "N"

                    // Set robot rotation based on direction
                    robotView.rotation = when (robotDirection) {
                        "N" -> 0f
                        "E" -> 90f
                        "S" -> 180f
                        "W" -> 270f
                        else -> 0f
                    }

                    hasRobot = true

                    val payload = "ROBOT,$bottomLeftCol,$bottomLeftRow,$robotDirection\n"
                    if (bluetoothSocket != null) {
                        sendData(payload)
                        appendLog("[AA -> Robot] Robot position: ($robotDirection, $bottomLeftCol, $bottomLeftRow)")
                    } else {
                        appendLog("[AA] Robot position: ($robotDirection, $bottomLeftCol, $bottomLeftRow)")
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
        val direction = "N" // Default direction

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
                        if (!isDragging && (kotlin.math.abs(v.x - (event.rawX + dX)) > 10 ||
                                    kotlin.math.abs(v.y - (event.rawY + dY)) > 10)) {
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
                                val bottomLeftCol = col - 1
                                val bottomLeftRow = row - 1
                                // Check for overlap before placing
                                if (isRobotOverlapping(bottomLeftCol, bottomLeftRow)) {
                                    (v.parent as FrameLayout).removeView(v)
                                    hasRobot = false
                                    appendLog("[Warning] Robot cannot be placed: overlaps obstacle!")
                                    toast("Robot placement blocked: overlaps obstacle")
                                    return@setOnTouchListener true
                                }
                                val (cx, cy) = gridView.cellCenterPixels(col, row)
                                val targetX = gridView.x + cx - v.width / 2f
                                val targetY = gridView.y + cy - v.height / 2f

                                v.x = targetX
                                v.y = targetY

                                // Direction can be changed by rotation buttons, for now keep 'N'
                                val payload = "ROBOT,$bottomLeftCol,$bottomLeftRow,$direction\n"
                                if (bluetoothSocket != null) {
                                    sendData(payload)
                                    appendLog("[AA -> Robot] Robot position: ($direction, $bottomLeftCol, $bottomLeftRow)")
                                } else {
                                    appendLog("[AA] Robot position: ($direction, $bottomLeftCol, $bottomLeftRow)")
                                }
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
            val obstacleNumber = nextObstacleId
            val obs = ObstacleView(this).apply {
                id = View.generateViewId()
                setNumber(obstacleNumber)
            }
            nextObstacleId++

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
                    obs.setDirection(currentObstacleDirection)
                    val (col, row) = cell
                    val (cx, cy) = gridView.cellCenterPixels(col, row)

                    val targetX = cx - size / 2f
                    val targetY = cy - size / 2f

                    obs.x = targetX
                    obs.y = targetY

                    val payload = "OBS,$obstacleNumber,$col,$row,$currentObstacleDirection\n"
                    if (bluetoothSocket != null) {
                        sendData(payload)
                        appendLog("[AA -> Robot] Obstacle position: ($obstacleNumber, $col, $row, $currentObstacleDirection)")
                    } else {
                        appendLog("[AA] Obstacle position: ($obstacleNumber, $col, $row, $currentObstacleDirection)")
                    }
                } else {
                    gridContainer.removeView(obs)
                    nextObstacleId--
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
                        if (!isDragging && (kotlin.math.abs(v.x - (event.rawX + dX)) > 10 ||
                                    kotlin.math.abs(v.y - (event.rawY + dY)) > 10)) {
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
                                recalculateNextObstacleId()
                                updatePaletteLabel()
                                toast("Obstacle removed - dropped outside grid")
                            } else {
                                val (col, row) = cell
                                val (cx, cy) = gridView.cellCenterPixels(col, row)
                                val targetX = gridView.x + cx - v.width / 2f
                                val targetY = gridView.y + cy - v.height / 2f

                                v.x = targetX
                                v.y = targetY

                                val obstacleNumber = (v as? ObstacleView)?.getNumber() ?: 0
                                val payload = "OBS,$obstacleNumber,$col,$row,$currentObstacleDirection\n"
                                if (bluetoothSocket != null) {
                                    sendData(payload)
                                    appendLog("[AA -> Robot] Obstacle position: ($obstacleNumber, $col, $row)")
                                } else {
                                    appendLog("[AA] Obstacle position: ($obstacleNumber, $col, $row)")
                                }
                                toast("Obstacle placed at (ID: $obstacleNumber, $col, $row)")
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
    private fun startReconnectJob() {
        stopReconnectJob()

        val device = lastConnectedDevice ?: return

        // Wait for external device to reconnect instead of actively reconnecting
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            runOnUiThread {
                updateStatus("Connection lost. Waiting for ${safeDeviceName(device)} to reconnect...")
                appendLog("[Conn] Connection lost. Waiting for ${safeDeviceName(device)} to reconnect...")
                btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_orange_light, null))
            }

            // Similar to BluetoothPopUp implementation, show a dialog if needed
            waitForConnectionJob()
        }
    }

    private fun waitForConnectionJob() {
        stopReconnectJob()

        // Start server socket to listen for incoming connections
        acceptJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    updateStatus("Waiting for connection...")
                    appendLog("[Conn] Waiting for external device to initiate connection...")
                }

                // Create a server socket to accept incoming connections
                startAcceptThread()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[Conn] Error while waiting for connection: ${e.message}")
                }
            }
        }
    }

    private fun startAcceptThread() {
        if (!hasBtConnectPermission()) {
            return
        }

        try {
            // Close any existing server socket
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                appendLog("[Accept] Error closing existing server socket: ${e.message}")
            }

            // Create a new server socket
            try {
                serverSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                    "MDP_Android", sppUuid
                )
            } catch (se: SecurityException) {
                appendLog("[Accept] Security exception creating server socket: ${se.message}")
                return
            }

            appendLog("[Accept] RFCOM server socket started...")

            // Accept connection in a background thread
            acceptJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val socket = withTimeout(300.seconds) {
                        serverSocket?.accept() ?: throw IOException("Server socket is null")
                    }

                    appendLog("[Accept] RFCOM server socket accepted connection")

                    // Get connected device
                    val connectedDevice = socket.remoteDevice
                    lastConnectedDevice = connectedDevice

                    // Close the server socket as we only need one connection
                    serverSocket?.close()
                    serverSocket = null

                    // Set up the connected socket
                    bluetoothSocket = socket

                    withContext(Dispatchers.Main) {
                        updateStatus("Connected to ${safeDeviceName(connectedDevice)}")
                        appendLog("[Conn] Connected to ${safeDeviceName(connectedDevice)}")
                        toast("Connected to ${safeDeviceName(connectedDevice)}")
                        btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_green_light, null))
                    }

                    // Start read loop
                    startReadLoop()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog("[Accept] Accept failed: ${e.message}")
                    }

                    // Try again
                    delay(1000)
                    startAcceptThread()
                }
            }
        } catch (e: Exception) {
            appendLog("[Accept] Error creating server socket: ${e.message}")
        }
    }

    private fun stopAcceptThread() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            appendLog("[Accept] Error closing server socket: ${e.message}")
        }
        serverSocket = null
    }

    private fun stopReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
        stopAcceptThread()
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
                        appendLog("[ACL] Device ACL connected: ${safeDeviceName(device)}")
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        appendLog("[ACL] Device ACL disconnected: ${safeDeviceName(device)}")
                        appendLog("[ACL] Physical Bluetooth disconnected, waiting for reconnection...")
                        closeConnection()
                        // Wait for reconnection instead of actively reconnecting
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

    // --- End Optimized Bluetooth Reconnection Logic ---
    // region Obstacle Direction Control
    private fun setObstacleDirection(direction: String) {
        currentObstacleDirection = direction
        updateDirectionButtonColors()
    // --- End Bluetooth Reconnection Logic ---
    }
    private fun updateDirectionButtonColors() {
        val greyColor = android.graphics.Color.parseColor("#CCCCCC")
        val yellowColor = android.graphics.Color.parseColor("#FFD700")

        // Reset all buttons to grey
        btnObstacleDirectionNorth.setBackgroundColor(greyColor)
        btnObstacleDirectionEast.setBackgroundColor(greyColor)
        btnObstacleDirectionSouth.setBackgroundColor(greyColor)
        btnObstacleDirectionWest.setBackgroundColor(greyColor)

        // Set the active direction to yellow
        when (currentObstacleDirection) {
            "N" -> btnObstacleDirectionNorth.setBackgroundColor(yellowColor)
            "E" -> btnObstacleDirectionEast.setBackgroundColor(yellowColor)
            "S" -> btnObstacleDirectionSouth.setBackgroundColor(yellowColor)
            "W" -> btnObstacleDirectionWest.setBackgroundColor(yellowColor)
        }
    }
    // endregion

    // Add missing tryConnectWithSecureInsecure function
    private suspend fun tryConnectWithSecureInsecure(
        device: BluetoothDevice,
        timeoutSeconds: Int
    ): BluetoothSocket? {
        // Check for BLUETOOTH_CONNECT permission first
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        // Try both secure and insecure methods in a loop with delays
        for (attempt in 1..3) { // Try 3 rounds
            appendLog("[Conn] Connection attempt $attempt...")

            // Try secure connection first
            val secureSocket = try {
                device.createRfcommSocketToServiceRecord(sppUuid)
            } catch (e: Exception) {
                appendLog("[Conn] Secure socket creation failed: ${e.message}")
                null
            }

            if (secureSocket != null) {
                try {
                    appendLog("[Conn] Trying secure connection (attempt $attempt)...")
                    withTimeout(timeoutSeconds.seconds) { secureSocket.connect() }
                    appendLog("[Conn] Secure connection successful!")
                    return secureSocket
                } catch (e: Exception) {
                    appendLog("[Conn] Secure connection failed: ${e.message}")
                    runCatching { secureSocket.close() }
                }
            }

            // Wait a bit before trying insecure
            delay(1000) // 1 second delay

            // Try insecure connection
            val insecureSocket = try {
                device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            } catch (e: Exception) {
                appendLog("[Conn] Insecure socket creation failed: ${e.message}")
                null
            }

            if (insecureSocket != null) {
                try {
                    appendLog("[Conn] Trying insecure connection (attempt $attempt)...")
                    withTimeout(timeoutSeconds.seconds) { insecureSocket.connect() }
                    appendLog("[Conn] Insecure connection successful!")
                    return insecureSocket
                } catch (e: Exception) {
                    appendLog("[Conn] Insecure connection failed: ${e.message}")
                    runCatching { insecureSocket.close() }
                }
            }

            // Wait before next attempt (except for last attempt)
            if (attempt < 3) {
                appendLog("[Conn] Waiting 2 seconds before next attempt...")
                delay(2000)
            }
        }

        appendLog("[Conn] All connection attempts failed")
        return null
    }
}
