package ru.sonata.diagnostic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.launch
import ru.sonata.diagnostic.core.connection.BluetoothConnection
import ru.sonata.diagnostic.core.obd.OBDCommand
import ru.sonata.diagnostic.core.obd.OBDManager
import ru.sonata.diagnostic.diagnostic.dtc.DTCReadResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var status by remember {
        mutableStateOf("Нажмите «Найти сопряжённые устройства»")
    }
    var adapterResponse by remember { mutableStateOf("") }
    var ecuStatus by remember { mutableStateOf("ЭБУ: не проверен") }
    var connectedName by remember { mutableStateOf("") }

    var connection by remember { mutableStateOf<BluetoothConnection?>(null) }
    var obdManager by remember { mutableStateOf<OBDManager?>(null) }

    var busy by remember { mutableStateOf(false) }
    var dtcResult by remember { mutableStateOf<DTCReadResult?>(null) }
    var dtcTitle by remember { mutableStateOf("") }
    var protocolReport by remember { mutableStateOf("") }

    fun refreshDevices() {
        if (adapter == null) {
            status = "Bluetooth не поддерживается этим телефоном"
            return
        }

        if (!adapter.isEnabled) {
            status = "Включите Bluetooth на телефоне"
            return
        }

        if (
            Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            status = "Нужно разрешение «Устройства поблизости»"
            return
        }

        devices = adapter.bondedDevices?.sortedBy {
            it.name ?: it.address
        } ?: emptyList()

        status = if (devices.isEmpty()) {
            "Сопряжённых устройств нет. Сначала выполните сопряжение в настройках Bluetooth."
        } else {
            "Сопряжённых устройств: ${devices.size}"
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshDevices()
        } else {
            status = "Разрешение Bluetooth не предоставлено"
        }
    }

    fun requestOrRefresh() {
        if (
            Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            refreshDevices()
        }
    }

    fun connect(device: BluetoothDevice) {
        if (
            Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        scope.launch {
            busy = true
            status = "Подключение к ${device.name ?: device.address}…"
            adapterResponse = ""
            ecuStatus = "ЭБУ: ожидает проверки"
            dtcResult = null

            val newConnection = BluetoothConnection()

            try {
                newConnection.connect(device.address)

                val newManager = OBDManager(newConnection)
                obdManager = newManager
                connection = newConnection
                connectedName = device.name ?: device.address

                status = "Bluetooth SPP подключён. Инициализация адаптера…"

                val initResults = newManager.initialize()

                val initSummary = initResults.joinToString(" | ") {
                    "${it.command}: ${it.rawData.ifBlank { it.errorMessage ?: "нет ответа" }}"
                }

                val identity = newManager.execute(
                    OBDCommand(
                        command = "ATI",
                        description = "Идентификация адаптера",
                        timeoutMs = 2000,
                        requiresEcuResponse = false
                    )
                )

                adapterResponse = buildString {
                    append(initSummary)
                    if (identity.rawData.isNotBlank()) {
                        append(" | ATI: ")
                        append(identity.rawData)
                    }
                }

                status = if (initResults.all { it.isSuccessful }) {
                    "Адаптер подключён и инициализирован"
                } else {
                    "Адаптер подключён, но инициализация выполнена не полностью"
                }
            } catch (error: Exception) {
                try {
                    newConnection.disconnect()
                } catch (_: Exception) {
                }

                connection = null
                obdManager = null
                connectedName = ""
                status = "Ошибка подключения: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun checkEcu() {
        val manager = obdManager

        if (manager == null || connection?.isConnected != true) {
            status = "Сначала подключитесь к адаптеру"
            return
        }

        scope.launch {
            busy = true
            ecuStatus = "Проверка связи с ЭБУ…"

            try {
                val response = manager.execute(
                    OBDCommand(
                        command = "0100",
                        description = "Запрос поддерживаемых PID",
                        timeoutMs = 6000
                    )
                )

                val normalized = response.rawData
                    .replace(" ", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .uppercase()

                ecuStatus = when {
                    !response.isSuccessful ->
                        "Ошибка запроса ЭБУ: ${response.errorMessage ?: response.rawData}"

                    normalized.contains("4100") ->
                        "ЭБУ ответил на запрос 0100: ${response.rawData}"

                    else ->
                        "Ответ ЭБУ не подтверждён: ${response.rawData.ifBlank { "пустой ответ" }}"
                }
            } catch (error: Exception) {
                ecuStatus = "Ошибка обмена с ЭБУ: ${error.message}"
            } finally {
                busy = false
            }
        }
    }

    fun runProtocolDiagnostics() {
        val manager = obdManager

        if (manager == null || connection?.isConnected != true) {
            status = "Сначала подключитесь к адаптеру"
            return
        }

        scope.launch {
            busy = true
            protocolReport = "Выполняется диагностика..."

            val commands = listOf(
                "ATDP",
                "ATDPN",
                "0100",
                "010C",
                "010B"
            )

            val report = StringBuilder()

            try {
                for (command in commands) {
                    val response = manager.execute(
                        OBDCommand(
                            command = command,
                            description = "Диагностика протокола: $command",
                            timeoutMs = if (command.startsWith("01")) 6000 else 2000,
                            requiresEcuResponse = command.startsWith("01")
                        )
                    )

                    report.append(command)
                    report.append(": ")
                    report.append(
                        response.rawData.ifBlank {
                            response.errorMessage ?: "Пустой ответ"
                        }
                    )
                    report.append("\\n")
                }

                protocolReport = report.toString()
            } catch (error: Exception) {
                protocolReport = report.toString() +
                    "Ошибка: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun readDtc(mode: Int, title: String) {
        val manager = obdManager

        if (manager == null || connection?.isConnected != true) {
            status = "Сначала подключитесь к адаптеру"
            return
        }

        scope.launch {
            busy = true
            dtcTitle = title
            dtcResult = null

            try {
                dtcResult = manager.readTroubleCodes(mode)
            } catch (error: Exception) {
                dtcResult = DTCReadResult(
                    codes = emptyList(),
                    rawResponse = "",
                    isSuccessful = false,
                    errorMessage = error.message ?: "Ошибка чтения кодов"
                )
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        val manager = obdManager

        scope.launch {
            busy = true

            try {
                manager?.disconnect()
            } catch (error: Exception) {
                status = "Ошибка отключения: ${error.message}"
            }

            connection = null
            obdManager = null
            connectedName = ""
            adapterResponse = ""
            ecuStatus = "ЭБУ: не проверен"
            dtcResult = null
            dtcTitle = ""
            status = "Отключено"
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SONATA", fontSize = 30.sp)
        Text("DIAGNOSTIC · v0.3", fontSize = 20.sp)

        Spacer(Modifier.height(12.dp))

        Text("Универсальная диагностика OBD-II")
        Text("Hyundai Sonata TagAZ 2.0 — профиль автомобиля")

        Spacer(Modifier.height(12.dp))

        Text(status)

        if (connectedName.isNotBlank()) {
            Text("Адаптер: $connectedName")
        }

        if (adapterResponse.isNotBlank()) {
            Text("Ответ адаптера: $adapterResponse")
        }

        Text(ecuStatus)

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { requestOrRefresh() },
            enabled = !busy && obdManager == null
        ) {
            Text("Найти сопряжённые устройства")
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(devices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(device.name ?: "Устройство без имени")
                        Text(device.address, fontSize = 12.sp)

                        Button(
                            onClick = { connect(device) },
                            enabled = !busy && obdManager == null
                        ) {
                            Text("Подключить")
                        }
                    }
                }
            }

            if (obdManager != null) {
                item {
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { checkEcu() },
                        enabled = !busy
                    ) {
                        Text("Проверить связь с ЭБУ")
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { runProtocolDiagnostics() },
                        enabled = !busy
                    ) {
                        Text("Диагностика протокола")
                    }

                    if (protocolReport.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Результаты диагностики", fontSize = 18.sp)
                        Text(protocolReport)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Чтение кодов неисправностей")

                    Button(
                        onClick = { readDtc(0x03, "Сохранённые ошибки") },
                        enabled = !busy
                    ) {
                        Text("Сохранённые ошибки")
                    }

                    Button(
                        onClick = { readDtc(0x07, "Ожидающие ошибки") },
                        enabled = !busy
                    ) {
                        Text("Ожидающие ошибки")
                    }

                    Button(
                        onClick = { readDtc(0x0A, "Постоянные ошибки") },
                        enabled = !busy
                    ) {
                        Text("Постоянные ошибки")
                    }

                    Button(
                        onClick = { disconnect() },
                        enabled = !busy
                    ) {
                        Text("Отключить адаптер")
                    }
                }
            }

            if (dtcResult != null) {
                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Text(dtcTitle, fontSize = 18.sp)

                    val result = dtcResult!!

                    if (!result.isSuccessful) {
                        Text(
                            "Не удалось прочитать ошибки: " +
                                (result.errorMessage ?: "неизвестная ошибка")
                        )
                    } else if (result.codes.isEmpty()) {
                        Text("Коды неисправностей не обнаружены в полученном ответе.")
                    } else {
                        result.codes.forEach { code ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(code.code, fontSize = 20.sp)
                                    Text("Статус: ${code.status}")
                                    Text(code.description ?: "Описание отсутствует")
                                    if (code.ecuAddress != null) {
                                        Text("Адрес ЭБУ: ${code.ecuAddress}")
                                    }
                                }
                            }
                        }
                    }

                    if (result.rawResponse.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Исходный ответ: ${result.rawResponse}")
                    }
                }
            }
        }
    }
}
