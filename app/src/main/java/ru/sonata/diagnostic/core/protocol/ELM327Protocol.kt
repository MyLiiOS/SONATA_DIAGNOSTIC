package ru.sonata.diagnostic.core.protocol

import kotlinx.coroutines.delay
import ru.sonata.diagnostic.core.connection.OBDConnection
import ru.sonata.diagnostic.core.obd.OBDCommand
import ru.sonata.diagnostic.core.obd.OBDResponse

class ELM327Protocol(
    private val connection: OBDConnection
) {

    private val initializationCommands = listOf(
        OBDCommand("ATZ", "Перезагрузка адаптера", 5000, false),
        OBDCommand("ATE0", "Отключение эха", 2000, false),
        OBDCommand("ATL0", "Отключение переносов строк", 2000, false),
        OBDCommand("ATS0", "Отключение пробелов", 2000, false),
        OBDCommand("ATH1", "Включение заголовков", 2000, false),
        OBDCommand("ATSP0", "Автоматический выбор протокола", 2000, false)
    )

    suspend fun initialize(): List<OBDResponse> {
        val results = mutableListOf<OBDResponse>()

        for (command in initializationCommands) {
            val response = sendCommand(command)
            results.add(response)

            if (!response.isSuccessful) {
                break
            }

            delay(100)
        }

        return results
    }

    suspend fun sendCommand(command: OBDCommand): OBDResponse {
        return try {
            val data = (command.command + "\r")
                .toByteArray(Charsets.US_ASCII)

            connection.write(data)

            val rawResponse = connection.read(command.timeoutMs)
                .toString(Charsets.US_ASCII)
                .trim()

            val normalized = rawResponse
                .uppercase()
                .replace(" ", "")
                .replace("\r", "")
                .replace("\n", "")

            val successful = normalized.isNotEmpty() &&
                !normalized.contains("ERROR") &&
                !normalized.contains("UNABLETOCONNECT") &&
                !normalized.contains("STOPPED")

            OBDResponse(
                command = command.command,
                rawData = rawResponse,
                isSuccessful = successful,
                errorMessage = if (successful) null else "Адаптер вернул ошибку или пустой ответ"
            )
        } catch (error: Exception) {
            OBDResponse(
                command = command.command,
                rawData = "",
                isSuccessful = false,
                errorMessage = error.message ?: "Ошибка обмена с адаптером"
            )
        }
    }
}
