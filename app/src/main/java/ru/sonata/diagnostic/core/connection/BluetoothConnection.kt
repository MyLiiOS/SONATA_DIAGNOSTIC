package ru.sonata.diagnostic.core.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

class BluetoothConnection : OBDConnection {

    companion object {
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        disconnect()

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IOException("Bluetooth не поддерживается")

        val device = adapter.getRemoteDevice(address)
        val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

        try {
            newSocket.connect()
            socket = newSocket
        } catch (error: Exception) {
            try {
                newSocket.close()
            } catch (_: IOException) {
            }
            throw IOException("Не удалось подключиться: ${error.message}", error)
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val current = socket ?: throw IOException("Нет подключения")
        current.outputStream.write(data)
        current.outputStream.flush()
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val current = socket ?: throw IOException("Нет подключения")
        val input = current.inputStream
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(256)

        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val available = input.available()

                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, available))
                    if (count < 0) break
                    result.write(buffer, 0, count)

                    val text = result.toString(Charsets.US_ASCII.name())
                    if (text.contains(">")) break
                } else {
                    kotlinx.coroutines.delay(20)
                }
            }
        }

        result.toByteArray()
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                socket?.close()
            } catch (_: IOException) {
            } finally {
                socket = null
            }
        }
    }
}
