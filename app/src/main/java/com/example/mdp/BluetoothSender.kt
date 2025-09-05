package com.example.mdp

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException

object BluetoothSender {
    var socket: BluetoothSocket? = null

    /**
     * Sends a string over the socket's OutputStream. Call this from a background thread if sending many bytes.
     * Returns true if successful, false otherwise.
     */
    fun sendString(payload: String): Boolean {
        val s = socket ?: run {
            Log.w("BluetoothSender", "No socket set")
            return false
        }
        return try {
            val out = s.outputStream
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
            Log.i("BluetoothSender", "Sent: $payload")
            true
        } catch (e: IOException) {
            Log.e("BluetoothSender", "Send failed: ${e.message}")
            false
        }
    }
}
