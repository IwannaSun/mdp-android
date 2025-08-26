package com.example.mdp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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

class MainActivity : ComponentActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var devicePickerDialog: AlertDialog? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var connectJob: Job? = null

    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button
    private lateinit var etMessage: EditText
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var bottomIcon: ImageView
    private lateinit var tvStatus: TextView

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private var receiverRegistered = false

    private val requestBtEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 用户处理完开启蓝牙对话框后再尝试连接
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 权限结果回调，无需额外处理；继续由按钮流程判断
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val name = safeDeviceName(device)
                        val address = device.address ?: "UNKNOWN"
                        val key = "$name\n$address"
                        discoveredDevices[key] = device
                        // 对话框中的适配器会在展示时被引用更新
                        currentListAdapter?.let { adapter ->
                            if ((0 until adapter.count).none { adapter.getItem(it) == key }) {
                                adapter.add(key)
                                adapter.notifyDataSetChanged()
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

    private var currentListAdapter: ArrayAdapter<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)
        etMessage = findViewById(R.id.etMessage)
        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)
        bottomIcon = findViewById(R.id.bottomIcon)
        tvStatus = findViewById(R.id.tvStatus)

        tvLog.movementMethod = ScrollingMovementMethod()

        btnConnect.setOnClickListener { onConnectClicked() }
        btnSend.setOnClickListener { onSendClicked() }
        bottomIcon.setOnClickListener { onConnectClicked() } // 底部图标也可触发连接

        updateStatus("Idle")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        unregisterDiscoveryReceiverIfNeeded()
        closeConnection()
    }

    // region UI actions

    private fun onConnectClicked() {
        if (!ensureBluetoothReady()) return
        showDevicePickerDialog()
    }

    private fun onSendClicked() {
        val msg = etMessage.text.toString()
        if (msg.isEmpty()) return
        sendData("$msg\n")
        appendLog("[AA -> Robot] $msg")
        etMessage.setText("")
    }

    // endregion

    // region Bluetooth setup & permissions

    private fun ensureBluetoothReady(): Boolean {
        if (bluetoothAdapter == null) {
            toast("Device does not support Bluetooth.")
            return false
        }
        if (!hasAllRequiredPermissions()) {
            requestAllPermissions()
            return false
        }
        if (!(bluetoothAdapter?.isEnabled ?: false)) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBtEnableLauncher.launch(intent)
            return false
        }
        return true
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION // 低于 Android 12 进行发现需要位置信息
            )
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        requestPermissionsLauncher.launch(requiredPermissions())
    }

    // endregion

    // region Device picker and discovery
    // 添加到 MainActivity 内部（任意位置的 helpers 区域）
    private fun bondedDevicesSafe(): Set<BluetoothDevice> {
        val hasConnectPerm =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

        if (!hasConnectPerm) return emptySet()

        return try {
            bluetoothAdapter?.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
    }

    private fun showDevicePickerDialog() {
        discoveredDevices.clear()

        val items = ArrayList<String>()
        val localAddress = bluetoothAdapter?.address
        for (device in bondedDevicesSafe()) {
            val name = safeDeviceName(device)
            val address = device.address ?: "UNKNOWN"
            if (localAddress != null && address == localAddress) continue
            val key = "$name\n$address"
            discoveredDevices[key] = device
            items.add(key)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        currentListAdapter = adapter

        val dialogView = layoutInflater.inflate(R.layout.dialog_device_picker, null)
        val listView = dialogView.findViewById<ListView>(R.id.deviceListView)
        val btnScan = dialogView.findViewById<Button>(R.id.btnScan)
        listView.adapter = adapter

        val builder = AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setView(dialogView)
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                devicePickerDialog = null
                stopDiscovery()
            }

        val dialog = builder.create()
        devicePickerDialog = dialog

        listView.setOnItemClickListener { _, _, position, _ ->
            val key = adapter.getItem(position) ?: return@setOnItemClickListener
            val device = discoveredDevices[key] ?: return@setOnItemClickListener
            dialog.dismiss()
            devicePickerDialog = null
            stopDiscovery()
            connectToDevice(device)
        }

        btnScan.setOnClickListener {
            startDiscoveryAndKeepDialogOpen()
        }

        dialog.setOnShowListener {
            startDiscoveryAndKeepDialogOpen()
        }
        dialog.show()
    }

    // Add this function to your MainActivity class

    private fun startDiscoveryAndKeepDialogOpen() {
        registerDiscoveryReceiverIfNeeded()
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery() // Cancel any ongoing discovery
            }
            val discoveryStarted = bluetoothAdapter?.startDiscovery()
            if (discoveryStarted == true) {
                appendLog("[Scan] Starting discovery...")
            } else {
                appendLog("[Scan] Failed to start discovery.")
                // Optionally, you could try to close the dialog or show an error
                // if discovery fails to start, but for now, we'll just log it.
            }
        } catch (e: SecurityException) {
            appendLog("[Scan] Permission denied for discovery: ${e.message}")
            // Handle the case where Bluetooth permissions might be missing or denied at runtime
            // This might involve requesting permissions again or informing the user.
        }
    }
    private fun stopDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
    }

    private fun registerDiscoveryReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
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
                // 权限检查（Android 12+）
                val socket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                    bluetoothSocket
                } else {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                    bluetoothSocket
                }

                bluetoothAdapter?.cancelDiscovery()
                socket?.connect()

                withContext(Dispatchers.Main) {
                    updateStatus("Connected to ${safeDeviceName(device)}")
                    appendLog("[Conn] Connected to ${safeDeviceName(device)}")
                }

                startReadLoop()
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    updateStatus("Connection failed")
                    appendLog("[Conn] Failed: ${e.message}")
                    toast("Connection failed: ${e.message}")
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

    private fun closeConnection() {
        readJob?.cancel()
        readJob = null
        connectJob?.cancel()
        connectJob = null
        try {
            bluetoothSocket?.close()
        } catch (_: IOException) {
        }
        bluetoothSocket = null
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

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Unknown"
        } catch (_: SecurityException) {
            "Unknown"
        }
    }

    private fun appendLog(line: String) {
        val msg = if (line.endsWith("\n")) line else "$line\n"
        tvLog.append(msg)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }



    // endregion
}