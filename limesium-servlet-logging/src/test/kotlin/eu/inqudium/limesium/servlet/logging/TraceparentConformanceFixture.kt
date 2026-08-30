package eu.inqudium.limesium.servlet.logging

import org.springframework.core.io.ClassPathResource

/** Reader of the shared `traceparent/conformance.txt` fixture (format documented in the file). */
internal object TraceparentConformanceFixture {
    private fun lines(): List<List<String>> =
        ClassPathResource("traceparent/conformance.txt")
            .inputStream
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('|') }

    /** `(header, expected trace id, expected parent/span id)` per valid line. */
    fun valid(): List<Triple<String, String, String>> = lines().filter { it[0] == "valid" }.map { Triple(it[1], it[2], it[3]) }

    fun invalid(): List<String> = lines().filter { it[0] == "invalid" }.map { it[1] }
}
