package ru.sonata.diagnostic.core.obd

data class OBDCommand(
    val command: String,
    val description: String,
    val timeoutMs: Long = 2000,
    val requiresEcuResponse: Boolean = true
)
