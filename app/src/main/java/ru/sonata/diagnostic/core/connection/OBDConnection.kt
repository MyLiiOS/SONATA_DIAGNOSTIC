package ru.sonata.diagnostic.core.connection

interface OBDConnection {
    val isConnected: Boolean

    suspend fun connect(address: String)

    suspend fun write(data: ByteArray)

    suspend fun read(timeoutMs: Long): ByteArray

    suspend fun disconnect()
}
