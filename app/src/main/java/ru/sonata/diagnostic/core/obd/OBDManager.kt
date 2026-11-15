package ru.sonata.diagnostic.core.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sonata.diagnostic.core.connection.OBDConnection
import ru.sonata.diagnostic.core.protocol.ELM327Protocol
import ru.sonata.diagnostic.diagnostic.dtc.DTCReadResult
import ru.sonata.diagnostic.diagnostic.dtc.DTCRepository

class OBDManager(
    private val connection: OBDConnection
) {

    private val protocol = ELM327Protocol(connection)
    private val commandMutex = Mutex()
    private val dtcRepository = DTCRepository()

    suspend fun initialize(): List<OBDResponse> {
        return commandMutex.withLock {
            if (!connection.isConnected) {
                return@withLock listOf(
                    OBDResponse(
                        command = "CONNECT",
                        rawData = "",
                        isSuccessful = false,
                        errorMessage = "Нет подключения к адаптеру"
                    )
                )
            }

            protocol.initialize()
        }
    }

    suspend fun execute(command: OBDCommand): OBDResponse {
        return commandMutex.withLock {
            if (!connection.isConnected) {
                return@withLock OBDResponse(
                    command = command.command,
                    rawData = "",
                    isSuccessful = false,
                    errorMessage = "Нет подключения к адаптеру"
                )
            }

            protocol.sendCommand(command)
        }
    }

    suspend fun readTroubleCodes(mode: Int): DTCReadResult {
        val description = when (mode) {
            0x03 -> "Сохранённые ошибки"
            0x07 -> "Ожидающие ошибки"
            0x0A -> "Постоянные ошибки"
            else -> return DTCReadResult(
                codes = emptyList(),
                rawResponse = "",
                isSuccessful = false,
                errorMessage = "Неподдерживаемый режим DTC: $mode"
            )
        }

        return commandMutex.withLock {
            if (!connection.isConnected) {
                return@withLock DTCReadResult(
                    codes = emptyList(),
                    rawResponse = "",
                    isSuccessful = false,
                    errorMessage = "Нет подключения к адаптеру"
                )
            }

            val command = OBDCommand(
                command = mode.toString(16).uppercase().padStart(2, '0'),
                description = description,
                timeoutMs = 5000
            )

            val response = protocol.sendCommand(command)

            if (!response.isSuccessful) {
                return@withLock DTCReadResult(
                    codes = emptyList(),
                    rawResponse = response.rawData,
                    isSuccessful = false,
                    errorMessage = response.errorMessage
                        ?: "Не удалось получить ответ ЭБУ"
                )
            }

            DTCReadResult(
                codes = dtcRepository.parseAndDescribe(
                    response.rawData,
                    mode
                ),
                rawResponse = response.rawData,
                isSuccessful = true
            )
        }
    }

    suspend fun disconnect() {
        commandMutex.withLock {
            connection.disconnect()
        }
    }
}
