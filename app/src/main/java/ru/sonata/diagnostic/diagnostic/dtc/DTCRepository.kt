package ru.sonata.diagnostic.diagnostic.dtc

class DTCRepository {

    fun parseAndDescribe(
        rawResponse: String,
        mode: Int
    ): List<DTC> {
        return DTCParser.parse(rawResponse, mode).map { dtc ->
            dtc.copy(
                description = DTCDatabase.getDescription(dtc.code)
            )
        }
    }

    fun getDescription(code: String): String {
        return DTCDatabase.getDescription(code)
    }
}
