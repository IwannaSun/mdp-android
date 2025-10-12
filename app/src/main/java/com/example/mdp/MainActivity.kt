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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds


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
    private lateinit var btnReset: Button  // Added RESET button reference
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
    private lateinit var btnStart: Button
    private var isStarted = false

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

    // Obstacle buffer for sending obstacles at once
    private val obstacleBuffer = mutableListOf<String>()

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
        btnReset = findViewById(R.id.btnReset)  // Initialize RESET button
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
        btnStart = findViewById<Button>(R.id.btnStart)

        // Initialize the Send OBS button
        val btnSendOBS = findViewById<Button>(R.id.btnSendOBS)


        // Set up obstacle direction button listeners
        btnObstacleDirectionNorth.setOnClickListener { setObstacleDirection("N") }
        btnObstacleDirectionEast.setOnClickListener { setObstacleDirection("E") }
        btnObstacleDirectionSouth.setOnClickListener { setObstacleDirection("S") }
        btnObstacleDirectionWest.setOnClickListener { setObstacleDirection("W") }
        btnStart.setOnClickListener { if (!isStarted) {
            // Start逻辑
            sendData("START")
            btnStart.text = "Stop"
            btnStart.setBackgroundColor(Color.RED)
            isStarted = true
        } else {
            // Stop逻辑
            sendData("STOP")
            btnStart.text = "Start"
            btnStart.setBackgroundColor(Color.parseColor("#4CAF23"))
            isStarted = false}
        }

        // Set up the Send OBS button click listener
        btnSendOBS.setOnClickListener { onSendObsClicked() }

        // Initialize the button colors
        updateDirectionButtonColors()

        // Set up Connect, Send, and Clear button listeners
        btnConnect.setOnClickListener { onConnectClicked() }
        btnSend.setOnClickListener { onSendClicked() }
        btnClear.setOnClickListener { onClearClicked() }
        btnReset.setOnClickListener { onResetClicked() } // Add RESET button listener

        //Directional buttons
        btnUp = findViewById(R.id.btnUp)
        btnDown = findViewById(R.id.btnDown)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        btnRotateLeft = findViewById(R.id.btnRotateLeft)
        btnRotateRight = findViewById(R.id.btnRotateRight)

        // Add button listeners
        btnUp.setOnClickListener { moveRobot("FD=200\n") }
        btnDown.setOnClickListener { moveRobot("BD=200\n") }
        btnLeft.setOnClickListener { moveRobot("BL=45\n") }
        btnRight.setOnClickListener { moveRobot("BR=45\n") }
        btnRotateLeft.setOnClickListener { moveRobot("FL=45\n") }
        btnRotateRight.setOnClickListener { moveRobot("FR=45\n") }

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
        // If there's text in the message field, send it
        val msg = etMessage.text.toString().trim()
        if (msg.isNotEmpty()) {
            sendData("$msg\n")
            appendLog("[AA -> Robot] $msg")
            etMessage.setText("")
            return
        }

        // If no text in message field, send all buffered obstacles
        if (obstacleBuffer.isNotEmpty()) {
            // Send all obstacles in the buffer
            val obsCount = obstacleBuffer.size
            for (obs in obstacleBuffer) {
                sendData(obs)
            }
            appendLog("[AA -> Robot] Sent $obsCount obstacles to robot")
            toast("Sent $obsCount obstacles to robot")

            // Clear the buffer after sending
            obstacleBuffer.clear()
        } else {
            toast("No obstacles to send")
        }
    }

    private fun onSendObsClicked() {
        // This function is called when the Send OBS button is clicked
        if (obstacleBuffer.isNotEmpty()) {
            // Create a map to keep track of the latest info for each obstacle ID
            val latestObstacleInfo = mutableMapOf<Int, String>()

            // Process each entry in the buffer to extract the obstacle ID and keep only the latest entry
            for (obsEntry in obstacleBuffer) {
                if (obsEntry.startsWith("OBS,")) {
                    val parts = obsEntry.split(",")
                    if (parts.size >= 2) {
                        try {
                            val obstacleId = parts[1].toInt()
                            latestObstacleInfo[obstacleId] = obsEntry
                        } catch (e: NumberFormatException) {
                            // Skip entries with invalid obstacle IDs
                            appendLog("[Warning] Invalid obstacle entry: $obsEntry")
                        }
                    }
                }
            }

            // Combine the latest obstacle information into a single message
            val combinedObstacles = latestObstacleInfo.values.joinToString("")

            if (combinedObstacles.isNotEmpty()) {
                // Send the consolidated message in one go
                sendData(combinedObstacles)

                val obsCount = latestObstacleInfo.size
                appendLog("[AA -> Robot] Sent $obsCount obstacles to robot")
                toast("Sent $obsCount obstacles to robot")

                // Note: Not clearing the buffer as requested
            } else {
                toast("No valid obstacle information to send")
            }
        } else {
            toast("No obstacles to send")
        }
    }

    private fun onClearClicked() {
        tvLog.text = ""
        appendLog("[System] Log cleared")
        clearAllObstacles()
        clearRobot()
        nextObstacleId = 1
        updatePaletteLabel()

        // Clear the obstacle buffer to prevent old data from being sent
        obstacleBuffer.clear()
        originalObstacleStates.clear()

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

        // Try direct connection to RFCOMM channel 1 first (which is what RPi expects)
        try {
            appendLog("[Conn] Trying direct connection to RFCOMM channel 1...")
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val channelSocket = m.invoke(device, 1) as BluetoothSocket
            withTimeout(timeoutSeconds.seconds) {
                channelSocket.connect()
            }
            appendLog("[Conn] Successfully connected to RFCOMM channel 1")
            return channelSocket
        } catch (e: Exception) {
            appendLog("[Conn] Channel 1 connection failed: ${e.message}")
            // Continue to other methods if this fails
        }

        // Try secure connection next
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
            var partialMessage = "" // 用于存储不完整的消息

            try {
                val input = socket.inputStream
                while (isActive) {
                    val n = input.read(buffer)
                    if (n > 0) {
                        // 将新读取的内容添加到partialMessage
                        val text = String(buffer, 0, n, Charsets.UTF_8)
                        partialMessage += text

                        // 检查是否包含完整的行（以\n结尾）
                        while (partialMessage.contains('\n')) {
                            val lineEndIndex = partialMessage.indexOf('\n')
                            val completeLine = partialMessage.substring(0, lineEndIndex + 1) // 包含\n
                            partialMessage = partialMessage.substring(lineEndIndex + 1) // 剩余部分

                            // 只处理非空行
                            if (completeLine.trim().isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    processIncomingMessage(completeLine)
                                }
                            }
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
        // 特殊处理STOP消息，重置Start按钮状态
        if (message.trim().equals("STOP", ignoreCase = true)) {
            runOnUiThread {
                btnStart.text = "Start"
                btnStart.setBackgroundColor(Color.parseColor("#4CAF23"))
                isStarted = false
                appendLog("[Robot -> AA] Received STOP command, reset button state")
            }
            return
        }

        // Special handling for TARGET messages to preserve the newline character
        if (message.startsWith("TARGET,", ignoreCase = true)) {
            try {
                // Use message directly without trimming to preserve the \n at the end
                val messageParts = message.split(",").map { it.trim() }
                if (messageParts.size >= 3) {
                    val obstacleNum = messageParts[1].toInt()
                    // Get the target ID which might contain the newline at the end
                    val targetId = messageParts[2] // Don't trim here to preserve possible newline

                    // Find the ObstacleView with this number
                    for (i in 0 until gridContainer.childCount) {
                        val child = gridContainer.getChildAt(i)
                        if (child is ObstacleView && child.getNumber() == obstacleNum) {
                            // Store original obstacle number before setting target ID
                            if (!originalObstacleStates.containsKey(child)) {
                                originalObstacleStates[child] = obstacleNum
                            }

                            // Update obstacle with target ID (keeping the newline if present)
                            child.setTargetId(targetId)
                            appendLog("[System] Obstacle $obstacleNum updated with Target ID: $targetId")
                            break
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                appendLog("[System] Error parsing TARGET message: ${e.message}")
                appendLog("[System] Raw TARGET message: '${message.replace("\n", "\\n")}'") // Show exact message with \n
                return
            }
        }

        // For all other messages, continue using the trimmed version
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

    private fun isRobotOverlapping(bottomLeftCol: Int, bottomLeftRow: Int): Boolean {
        val robotCells = getRobotCells(bottomLeftCol, bottomLeftRow)
        val obstacleCells = getObstaclePositions()
        return robotCells.intersect(obstacleCells).isNotEmpty()
    }

    private fun moveRobot(command: String) {
        // Find the RobotView
        val robotView = (0 until gridContainer.childCount)
            .map { gridContainer.getChildAt(it) }
            .find { it is RobotView } as? RobotView

        // Always send command to robot if connected, regardless of whether robot is on grid
        if (bluetoothSocket != null) {
            sendData("$command\n")
            // Removed logging: appendLog("[AA -> Robot] $command")
        }

        // If no robot on grid, just send command and return
        if (robotView == null) {
            // Removed logging for not connected case
            return
        }

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
            "FD=200\n" -> { // move forward (corrected to match button command)
                when (dir) {
                    "N" -> row += 2
                    "E" -> col += 2
                    "S" -> row -= 2
                    "W" -> col -= 2
                }
            }
            "BD=200\n" -> { // move backward (corrected to match button command)
                when (dir) {
                    "N" -> row -= 2
                    "E" -> col -= 2
                    "S" -> row += 2
                    "W" -> col += 2
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
        // Removed position logging: appendLog("[Local] Robot position: ($dir, $col, $row)")
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

    private fun createAndStartDragFromPalette(x: Float, y: Float) {
        try {
            // 创建一个新的ObstacleView
            val obstacleView = ObstacleView(this).apply {
                id = View.generateViewId()
                setNumber(nextObstacleId)
                setDirection(currentObstacleDirection)
            }

            gridView.post {
                val cellSize = gridView.getCellSizePx()
                val size = if (cellSize > 0) cellSize.roundToInt() else 80
                val lp = FrameLayout.LayoutParams(size, size)
                gridContainer.addView(obstacleView, lp)

                val gridLocalX = x
                val gridLocalY = y

                // 找到最近的有效单元格
                val cell = gridView.pixelToNearestCell(gridLocalX, gridLocalY)
                val (col, row) = cell

                // 计算单元格中心位置
                val (cx, cy) = gridView.cellCenterPixels(col, row)
                obstacleView.x = gridView.x + cx - size / 2f
                obstacleView.y = gridView.y + cy - size / 2f

                // 为障碍物添加拖拽处理
                attachDragHandler(obstacleView, false)

                // 准备障碍物信息但不立即发送，而是存入缓冲区
                val payload = "OBS,$nextObstacleId,$col,$row,$currentObstacleDirection\n"

                // 不再立即发送，而是添加到缓冲区
                obstacleBuffer.add(payload)
                appendLog("[AA] Obstacle created: ($nextObstacleId, $col, $row, $currentObstacleDirection)")
                toast("Obstacle placed at (ID: $nextObstacleId, $col, $row)")

                // 更新下一个障碍物ID
                nextObstacleId++
                updatePaletteLabel()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating obstacle: ${e.message}")
            toast("Failed to create obstacle: ${e.message}")
        }
    }

    private fun attachDragHandler(view: View, isRobot: Boolean) {
        var dX = 0f
        var dY = 0f
        var isDragging = false
        var startX = 0f
        var startY = 0f
        var originalX = 0f
        var originalY = 0f

        view.setOnTouchListener { v, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.bringToFront()
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        startX = event.rawX
                        startY = event.rawY
                        isDragging = false
                        // 记录拖拽前的位置
                        originalX = v.x
                        originalY = v.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val distanceMoved = kotlin.math.sqrt(
                            (event.rawX - startX).pow(2) +
                            (event.rawY - startY).pow(2)
                        )
                        if (!isDragging && distanceMoved > 10) {
                            isDragging = true
                        }
                        val newX = event.rawX + dX
                        val newY = event.rawY + dY
                        v.x = newX
                        v.y = newY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val distanceMoved = kotlin.math.sqrt(
                            (event.rawX - startX).pow(2) +
                            (event.rawY - startY).pow(2)
                        )
                        if (distanceMoved < 10) {
                            v.performClick()
                        } else {
                            val centerX = v.x + v.width / 2f
                            val centerY = v.y + v.height / 2f
                            val gridLocalX = centerX - gridView.x
                            val gridLocalY = centerY - gridView.y
                            var overlapped = false
                            if (isRobot) {
                                // 机器人拖拽处理逻辑
                                val cell = gridView.pixelToCell(gridLocalX, gridLocalY)
                                if (cell == null) {
                                    // 拖出网格，移除机器人
                                    gridContainer.removeView(v)
                                    hasRobot = false
                                    robotCol = null
                                    robotRow = null
                                    toast("Robot removed - dropped outside grid")
                                    appendLog("[System] Robot removed by dragging outside grid")
                                } else {
                                    val (col, row) = cell
                                    val bottomLeftCol = col - 1
                                    val bottomLeftRow = row - 1
                                    val colCount = gridView.cols
                                    val rowCount = gridView.rows
                                    if (bottomLeftCol < 0 || bottomLeftRow < 0 ||
                                        bottomLeftCol > colCount - 3 || bottomLeftRow > rowCount - 3) {
                                        // 边界外，需要调整到有效位置
                                        val validCol = bottomLeftCol.coerceIn(0, colCount - 3)
                                        val validRow = bottomLeftRow.coerceIn(0, rowCount - 3)

                                        if (isRobotOverlapping(validCol, validRow)) {
                                            v.x = originalX
                                            v.y = originalY
                                            toast("Robot placement blocked: overlaps obstacle, reverted to previous position")
                                        } else {
                                            val (cx, cy) = gridView.cellCenterPixels(validCol + 1, validRow + 1)
                                            v.x = cx - v.width / 2f
                                            v.y = cy - v.height / 2f
                                            robotCol = validCol
                                            robotRow = validRow
                                            val payload = "ROBOT,$validCol,$validRow,$robotDirection\n"
                                            if (bluetoothSocket != null) {
                                                sendData(payload)
                                                appendLog("[AA -> Robot] Robot position adjusted: ($robotDirection, $validCol, $validRow)")
                                            } else {
                                                appendLog("[AA] Robot position adjusted: ($robotDirection, $validCol, $validRow)")
                                            }
                                            toast("Robot placed at ($validCol, $validRow)")
                                        }
                                    } else {
                                        if (isRobotOverlapping(bottomLeftCol, bottomLeftRow)) {
                                            v.x = originalX
                                            v.y = originalY
                                            toast("Robot placement blocked: overlaps obstacle, reverted to previous position")
                                        } else {
                                            val (cx, cy) = gridView.cellCenterPixels(col, row)
                                            v.x = cx - v.width / 2f
                                            v.y = cy - v.height / 2f
                                            robotCol = bottomLeftCol
                                            robotRow = bottomLeftRow
                                            val payload = "ROBOT,$bottomLeftCol,$bottomLeftRow,$robotDirection\n"
                                            if (bluetoothSocket != null) {
                                                sendData(payload)
                                                appendLog("[AA -> Robot] Robot position: ($robotDirection, $bottomLeftCol, $bottomLeftRow)")
                                            } else {
                                                appendLog("[AA] Robot position: ($robotDirection, $bottomLeftCol, $bottomLeftRow)")
                                            }
                                            toast("Robot placed at ($bottomLeftCol, $bottomLeftRow)")
                                        }
                                    }
                                }
                            } else {
                                // 障碍物拖拽处理逻辑
                                val cell = gridView.pixelToNearestCell(gridLocalX, gridLocalY)

                                // 检查是否超出网格边界
                                if (gridLocalX < 0 || gridLocalY < 0 ||
                                    gridLocalX > gridView.width || gridLocalY > gridView.height) {
                                    // 拖出网格，移除障碍物
                                    val obstacleNumber = (v as? ObstacleView)?.getNumber() ?: 0
                                    gridContainer.removeView(v)
                                    toast("Obstacle $obstacleNumber removed - dropped outside grid")
                                    appendLog("[System] Obstacle $obstacleNumber removed by dragging outside grid")

                                    // 重新计算下一个障碍物ID
                                    recalculateNextObstacleId()
                                    updatePaletteLabel()
                                } else {
                                    val (col, row) = cell
                                    // 检查是否与机器人重叠
                                    val robotCells = if (robotCol != null && robotRow != null) getRobotCells(robotCol!!, robotRow!!) else emptySet()
                                    if (robotCells.contains(Pair(col, row))) {
                                        v.x = originalX
                                        v.y = originalY
                                        toast("Obstacle placement blocked: overlaps robot, reverted to previous position")
                                    } else {
                                        val (cx, cy) = gridView.cellCenterPixels(col, row)
                                        v.x = cx - v.width / 2f
                                        v.y = cy - v.height / 2f
                                        val obstacleNumber = (v as? ObstacleView)?.getNumber() ?: 0
                                        val direction = (v as? ObstacleView)?.getDirection() ?: currentObstacleDirection
                                        val payload = "OBS,$obstacleNumber,$col,$row,$direction\n"

                                        // 添加到缓冲区而不是直接发送
                                        obstacleBuffer.add(payload)
                                        appendLog("[AA] Obstacle position: ($obstacleNumber, $col, $row, $direction)")
                                        toast("Obstacle placed at (ID: $obstacleNumber, $col, $row)")
                                    }
                                }
                            }
                        }
                        isDragging = false
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in touch handling: "+e.message)
                false
            }
        }

        // 设置点击监听器
        view.setOnClickListener {
            if (isRobot) {
                // 机器人点击处理
                toast("Robot clicked")
            } else {
                // 障碍物点击处理
                val obstacleView = view as? ObstacleView
                if (obstacleView != null) {
                    // 获取当前方向
                    val currentDir = obstacleView.getDirection()
                    // 顺时针旋转方向
                    val newDirection = when (currentDir) {
                        "N" -> "E"
                        "E" -> "S"
                        "S" -> "W"
                        "W" -> "N"
                        else -> "N"
                    }

                    // 更新障碍物方向
                    obstacleView.setDirection(newDirection)

                    // 通知用户
                    val obstacleNumber = obstacleView.getNumber()
                    toast("Obstacle $obstacleNumber now facing $newDirection")

                    // 获取障碍物位置
                    val centerX = view.x + view.width / 2f - gridView.x
                    val centerY = view.y + view.height / 2f - gridView.y
                    val cell = gridView.pixelToCell(centerX, centerY)

                    // 如果有有效位置并且连接了蓝牙，添加到缓冲区而不是立即发送
                    if (cell != null) {
                        val (col, row) = cell
                        val payload = "OBS,${obstacleNumber},${col},${row},${newDirection}\n"
                        // 添加到缓冲区
                        obstacleBuffer.add(payload)
                        appendLog("[AA] Obstacle direction updated: ($obstacleNumber, $newDirection)")
                    }
                } else {
                    toast("Obstacle clicked")
                }
            }
        }
    }

    // 处理机器人拖拽的专用函数
    private fun handleRobotDrag(view: View, gridLocalX: Float, gridLocalY: Float) {
        val cell = gridView.pixelToCell(gridLocalX, gridLocalY)
        if (cell == null) {
            (view.parent as FrameLayout).removeView(view)
            hasRobot = false
            toast("Robot removed - dropped outside grid")
        } else {
            val (col, row) = cell
            val bottomLeftCol = col - 1
            val bottomLeftRow = row - 1

            // 确保机器人不会超出边界
            val colCount = gridView.cols
            val rowCount = gridView.rows
            if (bottomLeftCol < 0 || bottomLeftRow < 0 ||
                bottomLeftCol > colCount - 3 || bottomLeftRow > rowCount - 3) {
                // 获取有效的最近网格位置
                val validCol = bottomLeftCol.coerceIn(0, colCount - 3)
                val validRow = bottomLeftRow.coerceIn(0, rowCount - 3)

                // 检查调整后的位置是否与障碍物重叠
                if (isRobotOverlapping(validCol, validRow)) {
                    (view.parent as FrameLayout).removeView(view)
                    hasRobot = false
                    appendLog("[Warning] Robot cannot be placed: overlaps obstacle!")
                    toast("Robot placement blocked: overlaps obstacle")
                    return
                }

                val (cx, cy) = gridView.cellCenterPixels(validCol + 1, validRow + 1)
                val targetX = gridView.x + cx - view.width / 2f
                val targetY = gridView.y + cy - view.height / 2f

                view.x = targetX
                view.y = targetY

                // 更新机器人状态
                robotCol = validCol
                robotRow = validRow

                val payload = "ROBOT,$validCol,$validRow,$robotDirection\n"
                if (bluetoothSocket != null) {
                    sendData(payload)
                    appendLog("[AA -> Robot] Robot position adjusted: ($robotDirection, $validCol, $validRow)")
                } else {
                    appendLog("[AA] Robot position adjusted: ($robotDirection, $validCol, $validRow)")
                }
                toast("Robot placed at ($validCol, $validRow)")
            } else {
                // 检查是否与障碍物重叠
                if (isRobotOverlapping(bottomLeftCol, bottomLeftRow)) {
                    (view.parent as FrameLayout).removeView(view)
                    hasRobot = false
                    appendLog("[Warning] Robot cannot be placed: overlaps obstacle!")
                    toast("Robot placement blocked: overlaps obstacle")
                    return
                }

                val (cx, cy) = gridView.cellCenterPixels(col, row)
                val targetX = gridView.x + cx - view.width / 2f
                val targetY = gridView.y + cy - view.height / 2f

                view.x = targetX
                view.y = targetY

                // 更新机器人状态
                robotCol = bottomLeftCol
                robotRow = bottomLeftRow

                val payload = "ROBOT,$bottomLeftCol,$bottomLeftRow,$robotDirection\n"
                if (bluetoothSocket != null) {
                    sendData(payload)
                    appendLog("[AA -> Robot] Robot position: ($robotDirection, $bottomLeftCol, $bottomLeftRow)")
                } else {
                    appendLog("[AA] Robot position: ($robotDirection, $bottomLeftCol, $bottomLeftRow)")
                }
                toast("Robot placed at ($bottomLeftCol, $bottomLeftRow)")
            }
        }
    }

    // 处理障碍物拖拽的专用函数
    private fun handleObstacleDrag(view: View, gridLocalX: Float, gridLocalY: Float) {
        // 使用pixelToNearestCell方法，确保总是能找到最近的有效格子
        val cell = gridView.pixelToNearestCell(gridLocalX, gridLocalY)

        // 获取目标位置
        val (col, row) = cell
        val (cx, cy) = gridView.cellCenterPixels(col, row)
        val targetX = gridView.x + cx - view.width / 2f
        val targetY = gridView.y + cy - view.height / 2f

        // 将障碍物移动到目标位置
        view.x = targetX
        view.y = targetY

        // 发送更新信息
        val obstacleNumber = (view as? ObstacleView)?.getNumber() ?: 0
        val direction = (view as? ObstacleView)?.getDirection() ?: currentObstacleDirection
        val payload = "OBS,$obstacleNumber,$col,$row,$direction\n"
        if (bluetoothSocket != null) {
            sendData(payload)
            appendLog("[AA -> Robot] Obstacle position: ($obstacleNumber, $col, $row, $direction)")
        } else {
            appendLog("[AA] Obstacle position: ($obstacleNumber, $col, $row, $direction)")
        }
        toast("Obstacle placed at (ID: $obstacleNumber, $col, $row)")
    }

    // 修改后的attachRobotDragHandler，调用通用函数
    private fun attachRobotDragHandler(robot: View) {
        attachDragHandler(robot, true)
    }

    // 修改后的attachDragHandlers，调用通用函数
    private fun attachDragHandlers(obstacle: View) {
        attachDragHandler(obstacle, false)
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
    private fun setObstacleDirection(direction: String) {
        currentObstacleDirection = direction
        updateDirectionButtonColors()

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

    // Store original obstacle states before TARGET updates
    private val originalObstacleStates = mutableMapOf<ObstacleView, Int>()

    // region RESET button functionality
    private fun onResetClicked() {
        // Count of obstacles with stored original states
        val restoredCount = originalObstacleStates.size

        if (restoredCount > 0) {
            // Restore the original states for obstacles that were updated by TARGET
            for ((obstacleView, originalId) in originalObstacleStates) {
                // Clear the target ID to restore original obstacle number display
                obstacleView.setTargetId(null)

                // Get the obstacle position to update the command
                val centerX = obstacleView.x + obstacleView.width / 2f - gridView.x
                val centerY = obstacleView.y + obstacleView.height / 2f - gridView.y
                val cell = gridView.pixelToCell(centerX, centerY)

                if (cell != null) {
                    val (col, row) = cell
                    val direction = obstacleView.getDirection()

                    // Create and send updated obstacle info
                    val payload = "OBS,$originalId,$col,$row,$direction\n"
                    obstacleBuffer.add(payload)

                    // Send to robot if connected
                    if (bluetoothSocket != null) {
                        sendData(payload)
                    }

                    appendLog("[System] Restored obstacle $originalId to original state")
                }
            }

            // Request a UI refresh to ensure obstacle numbers are properly displayed
            gridView.invalidate()

            // Clear the original states map after restoration is complete
            originalObstacleStates.clear()

            toast("Restored ${restoredCount} obstacles to original state")
        } else {
            toast("No obstacles to restore")
        }
    }
    // endregion
}
