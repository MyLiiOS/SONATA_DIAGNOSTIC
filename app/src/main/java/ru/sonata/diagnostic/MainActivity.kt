package ru.sonata.diagnostic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

private val SPP_UUID: UUID =
    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SonataScreen()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun SonataScreen() {
    val context = LocalContext.current
    val adapter = remember { BluetoothAdapter.getDefaultAdapter() }
    val handler = remember { Handler(Looper.getMainLooper()) }

    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var status by remember { mutableStateOf("Нажмите «Найти сопряжённые устройства»") }
    var adapterResponse by remember { mutableStateOf("") }
    var ecuStatus by remember { mutableStateOf("ЭБУ: не проверен") }
    var socket by remember { mutableStateOf<BluetoothSocket?>(null) }
    var connectedName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refreshDevices() {
        if (adapter == null) {
            status = "Bluetooth не поддерживается этим телефоном"
            return
        }
        if (!adapter.isEnabled) {
            status = "Включите Bluetooth на телефоне"
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            status = "Нужно разрешение «Устройства поблизости»"
            return
        }

        devices = adapter.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
        status = if (devices.isEmpty()) {
            "Сопряжённых устройств нет. Сначала выполните сопряжение в настройках Bluetooth."
        } else {
            "Сопряжённых устройств: ${devices.size}"
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshDevices()
        else status = "Разрешение Bluetooth не предоставлено. Включите его в настройках приложения."
    }

    fun requestOrRefresh() {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            refreshDevices()
        }
    }

    fun connect(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        busy = true
        status = "Подключение к ${device.name ?: device.address}…"
        thread(name = "ELM327-connect") {
            var newSocket: BluetoothSocket? = null
            try {
                newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                val reset = elmCommand(newSocket!!, "ATZ", 3000)
                val identity = elmCommand(newSocket!!, "ATI", 2000)
                handler.post {
                    socket = newSocket
                    connectedName = device.name ?: device.address
                    adapterResponse = listOf(reset, identity).filter { it.isNotBlank() }.joinToString(" | ")
                    status = "Bluetooth SPP подключён"
                    ecuStatus = "ЭБУ: ожидает проверки"
                    busy = false
                }
            } catch (e: Exception) {
                try { newSocket?.close() } catch (_: IOException) {}
                handler.post {
                    status = "Ошибка подключения: ${e.message ?: e.javaClass.simpleName}"
                    busy = false
                }
            }
        }
    }

    fun checkEcu() {
        val current = socket
        if (current == null || !current.isConnected) {
            status = "Сначала подключитесь к адаптеру"
            return
        }
        busy = true
        ecuStatus = "Проверка ЭБУ…"
        thread(name = "ELM327-ecu") {
            try {
                elmCommand(current, "ATE0", 1000)
                elmCommand(current, "ATL0", 1000)
                elmCommand(current, "ATS0", 1000)
                elmCommand(current, "ATH0", 1000)
                elmCommand(current, "ATSP0", 1500)
                val response = elmCommand(current, "0100", 6000)
                val ok = response.replace(" ", "").uppercase().contains("4100")
                handler.post {
                    ecuStatus = if (ok) "ЭБУ ответил на запрос 0100: $response"
                    else "Ответ ЭБУ не подтверждён: ${response.ifBlank { "пустой ответ" }}"
                    busy = false
                }
            } catch (e: Exception) {
                handler.post {
                    ecuStatus = "Ошибка обмена с ЭБУ: ${e.message ?: e.javaClass.simpleName}"
                    busy = false
                }
            }
        }
    }

    fun disconnect() {
        val old = socket
        socket = null
        connectedName = ""
        adapterResponse = ""
        ecuStatus = "ЭБУ: не проверен"
        status = "Отключено"
        thread { try { old?.close() } catch (_: IOException) {} }
    }

    Column(
        Modifier.fillMaxSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SONATA", fontSize = 30.sp)
        Text("DIAGNOSTIC · v0.2", fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text("Hyundai Sonata TagAZ 2.0")
        Spacer(Modifier.height(12.dp))
        Text(status)
        if (connectedName.isNotBlank()) Text("Адаптер: $connectedName")
        if (adapterResponse.isNotBlank()) Text("Ответ адаптера: $adapterResponse")
        Text(ecuStatus)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { requestOrRefresh() }, enabled = !busy) {
            Text("Найти сопряжённые устройства")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(devices) { device ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(device.name ?: "Устройство без имени")
                        Text(device.address, fontSize = 12.sp)
                        Button(
                            onClick = { connect(device) },
                            enabled = !busy && socket == null
                        ) { Text("Подключить") }
                    }
                }
            }
        }
        if (socket != null) {
            Button(onClick = { checkEcu() }, enabled = !busy) {
                Text("Проверить связь с ЭБУ")
            }
            Button(onClick = { disconnect() }, enabled = !busy) {
                Text("Отключить адаптер")
            }
        }
    }
}

private fun elmCommand(socket: BluetoothSocket, command: String, timeoutMs: Long): String {
    val input = socket.inputStream
    val output = socket.outputStream

    // Clear any stale bytes before sending a new command.
    val pending = input.available()
    if (pending > 0) input.skip(pending.toLong())

    output.write((command + "\r").toByteArray(Charsets.US_ASCII))
    output.flush()

    val answer = StringBuilder()
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastByteAt = 0L

    while (System.currentTimeMillis() < deadline) {
        val count = input.available()
        if (count > 0) {
            val buffer = ByteArray(count.coerceAtMost(512))
            val read = input.read(buffer)
            if (read > 0) {
                answer.append(String(buffer, 0, read, Charsets.US_ASCII))
                lastByteAt = System.currentTimeMillis()
                if (answer.contains(">")) break
            }
        } else if (answer.isNotEmpty() && System.currentTimeMillis() - lastByteAt > 500) {
            break
        } else {
            Thread.sleep(40)
        }
    }

    return answer.toString().replace(">", "").replace("\r", " ").replace("\n", " ").trim()
}
