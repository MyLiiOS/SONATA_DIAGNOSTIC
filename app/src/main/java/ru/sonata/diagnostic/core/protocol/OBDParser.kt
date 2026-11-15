package ru.sonata.diagnostic.core.protocol

data class OBDValue(
    val pid: String,
    val name: String,
    val value: Double,
    val unit: String,
    val rawBytes: List<Int>,
    val ecuAddress: String? = null
)

data class OBDNegativeResponse(
    val ecuAddress: String?,
    val requestedService: Int,
    val errorCode: Int
)

object OBDParser {

    private data class Frame(
        val ecuAddress: String?,
        val bytes: List<Int>
    )

    fun parseMode01(rawResponse: String, pid: String): List<OBDValue> {
        val pidBytes = pid.uppercase().removePrefix("01")

        require(pidBytes.matches(Regex("[0-9A-F]{2}"))) {
            "Некорректный PID: $pid"
        }

        val requestedPid = pidBytes.toInt(16)
        val results = mutableListOf<OBDValue>()

        for (frame in parseFrames(rawResponse)) {
            val bytes = frame.bytes

            for (i in 0 until bytes.size - 1) {
                if (bytes[i] != 0x41 || bytes[i + 1] != requestedPid) {
                    continue
                }

                val requiredBytes = if (requestedPid == 0x0C) 2 else 1

                if (bytes.size - i - 2 < requiredBytes) {
                    continue
                }

                val a = bytes[i + 2]
                val b = if (requiredBytes == 2) bytes[i + 3] else 0

                val value = when (requestedPid) {
                    0x0C -> (a * 256 + b) / 4.0
                    0x0D -> a.toDouble()
                    0x05, 0x0F -> (a - 40).toDouble()
                    0x11 -> a * 100.0 / 255.0
                    else -> continue
                }

                val (name, unit) = when (requestedPid) {
                    0x0C -> "Обороты двигателя" to "об/мин"
                    0x0D -> "Скорость автомобиля" to "км/ч"
                    0x05 -> "Температура охлаждающей жидкости" to "°C"
                    0x0F -> "Температура воздуха на впуске" to "°C"
                    0x11 -> "Положение дросселя" to "%"
                    else -> continue
                }

                results.add(
                    OBDValue(
                        pid = "01$pidBytes",
                        name = name,
                        value = value,
                        unit = unit,
                        rawBytes = if (requiredBytes == 2) {
                            listOf(a, b)
                        } else {
                            listOf(a)
                        },
                        ecuAddress = frame.ecuAddress
                    )
                )
            }
        }

        return results
    }

    fun parseNegativeResponses(rawResponse: String): List<OBDNegativeResponse> {
        val results = mutableListOf<OBDNegativeResponse>()

        for (frame in parseFrames(rawResponse)) {
            val bytes = frame.bytes

            if (bytes.size < 3) continue

            for (i in 0..(bytes.size - 3)) {
                if (bytes[i] == 0x7F) {
                    results.add(
                        OBDNegativeResponse(
                            ecuAddress = frame.ecuAddress,
                            requestedService = bytes[i + 1],
                            errorCode = bytes[i + 2]
                        )
                    )
                }
            }
        }

        return results
    }

    private fun parseFrames(raw: String): List<Frame> {
        val frames = mutableListOf<Frame>()

        val cleaned = raw
            .uppercase()
            .replace("SEARCHING...", "")
            .replace("NO DATA", "")
            .replace(">", "")

        for (line in cleaned.lines()) {
            val tokens = line.trim().split(Regex("\\s+"))
                .filter { it.isNotBlank() }

            if (tokens.isEmpty()) continue

            if (tokens.any {
                    it == "ERROR" || it == "STOPPED" ||
                    it == "UNABLE" || it == "?"
                }) continue

            val ecuAddress = if (
                tokens[0].matches(Regex("[0-9A-F]{3}|[0-9A-F]{8}"))
            ) tokens[0] else null

            val dataTokens = if (ecuAddress != null) {
                tokens.drop(1)
            } else {
                tokens
            }

            val validTokens = dataTokens.filter {
                it.matches(Regex("[0-9A-F]{2}"))
            }

            if (validTokens.isEmpty()) continue

            var bytes = validTokens.map { it.toInt(16) }

            // Убираем DLC, если он соответствует количеству оставшихся байтов.
            if (bytes.isNotEmpty() && bytes[0] == bytes.size - 1) {
                bytes = bytes.drop(1)
            }

            if (bytes.isNotEmpty()) {
                frames.add(Frame(ecuAddress, bytes))
            }
        }

        return frames
    }
}
