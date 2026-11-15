package ru.sonata.diagnostic.core.obd

data class OBDResponse(
    val command: String,
    val rawData: String,
    val isSuccessful: Boolean,
    val errorMessage: String? = null
)
