package ru.sonata.diagnostic.diagnostic.dtc

enum class DTCStatus {
    STORED,
    PENDING,
    PERMANENT
}

data class DTC(
    val code: String,
    val status: DTCStatus,
    val ecuAddress: String? = null,
    val description: String? = null
)

data class DTCReadResult(
    val codes: List<DTC>,
    val rawResponse: String,
    val isSuccessful: Boolean,
    val errorMessage: String? = null
)
