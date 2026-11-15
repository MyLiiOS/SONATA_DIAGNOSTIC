package ru.sonata.diagnostic.diagnostic.dtc

object DTCParser {

    private val modeServices = mapOf(
        0x03 to DTCStatus.STORED,
        0x07 to DTCStatus.PENDING,
        0x0A to DTCStatus.PERMANENT
    )

    fun parse(rawResponse: String, mode: Int): List<DTC> {
        val status = modeServices[mode]
            ?: throw IllegalArgumentException("Неподдерживаемый режим: $mode")

        val expectedService = mode + 0x40
        val results = mutableListOf<DTC>()

        for (line in rawResponse.uppercase().lines()) {
            val tokens = line.trim()
                .replace(">", "")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }

            if (tokens.isEmpty()) continue

            val ecuAddress = tokens.firstOrNull {
                it.matches(Regex("[0-9A-F]{3}|[0-9A-F]{8}"))
            }

            val dataTokens = if (ecuAddress != null) {
                tokens.drop(1)
            } else {
                tokens
            }

            val bytes = dataTokens
                .filter { it.matches(Regex("[0-9A-F]{2}")) }
                .map { it.toInt(16) }
                .toMutableList()

            // Удаляем DLC CAN-кадра, если он совпадает
            // с количеством последующих байтов.
            if (bytes.isNotEmpty() && bytes[0] == bytes.size - 1) {
                bytes.removeAt(0)
            }

            val serviceIndex = bytes.indexOf(expectedService)
            if (serviceIndex == -1) continue

            val dtcBytes = bytes.drop(serviceIndex + 1)

            for (i in dtcBytes.indices step 2) {
                if (i + 1 >= dtcBytes.size) break

                val first = dtcBytes[i]
                val second = dtcBytes[i + 1]

                if (first == 0 && second == 0) continue

                val code = decodeCode(first, second)

                results.add(
                    DTC(
                        code = code,
                        status = status,
                        ecuAddress = ecuAddress
                    )
                )
            }
        }

        return results
    }

    private fun decodeCode(first: Int, second: Int): String {
        val prefix = when ((first shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }

        val digit2 = (first shr 4) and 0x03
        val digit3 = first and 0x0F
        val digit4 = (second shr 4) and 0x0F
        val digit5 = second and 0x0F

        return "$prefix$digit2" +
            digit3.toString(16).uppercase() +
            digit4.toString(16).uppercase() +
            digit5.toString(16).uppercase()
    }
}
