// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

private val TEST_BYTES = "fake-server-binary".toByteArray()
private const val TEST_SHA256 = "e8e1c652c9df246b9d7c3a968aa6905b0e45f8de162ad150e0dd851a241a131d"
private fun tamperedBytes(): ByteArray = TEST_BYTES.copyOf().also { it[0] = 'X'.code.toByte() }

private fun testChecksums(extra: String = ""): String =
    "$TEST_SHA256  csqtt-linux-amd64\n" +
        "aa".repeat(32) + "  csqtt-linux-arm64\n" +
        extra

private fun testProvenance(version: String = "2.1.9", size: Int = TEST_BYTES.size): String =
    """{"schema":"csqtt.server-asset-provenance.v2","package":"csqtt","version":"$version","wireProtocolRevision":"CSQTT-WIRE-3","artifacts":[{"assetName":"csqtt-linux-amd64","binarySize":$size},{"assetName":"csqtt-linux-arm64","binarySize":9},{"assetName":"csqtt-linux-armv7","binarySize":9}]}"""

private fun testFetch(
    checksums: String = testChecksums(),
    provenance: String = testProvenance(),
    binary: ByteArray = TEST_BYTES,
    chunkedBinary: Boolean = false,
    requested: MutableList<String> = mutableListOf(),
): (String, (Long, Long?) -> Unit) -> ByteArray = { url, onChunk ->
    requested.add(url)
    when {
        url.endsWith("/SHA256SUMS") -> checksums.toByteArray()
        url.endsWith("/csqtt.server-provenance.json") -> provenance.toByteArray()
        url.endsWith("/csqtt-linux-amd64") -> {
            if (chunkedBinary) {
                var sent = 0L
                val step = (binary.size / 4).coerceAtLeast(1)
                while (sent < binary.size) {
                    sent = minOf(sent + step, binary.size.toLong())
                    onChunk(sent, binary.size.toLong())
                }
            }
            binary
        }
        else -> throw IOException("HTTP 404 for $url")
    }
}

class ServerBinaryDownloadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun downloadUrlTargetsOwnVersionReleasePerArchitecture() {
        assertEquals(
            "https://github.com/amurcanov/csqtt/releases/download/v2.1.9/csqtt-linux-amd64",
            serverBinaryDownloadUrl("2.1.9", ServerArchitecture.AMD64)
        )
        assertEquals(
            "https://github.com/amurcanov/csqtt/releases/download/v2.1.9/csqtt-linux-arm64",
            serverBinaryDownloadUrl("2.1.9", ServerArchitecture.ARM64)
        )
        assertEquals(
            "https://github.com/amurcanov/csqtt/releases/download/v2.1.9/csqtt-linux-armv7",
            serverBinaryDownloadUrl("2.1.9", ServerArchitecture.ARMV7)
        )
    }

    @Test
    fun downloadUrlNormalizesVersionTagOnce() {
        assertEquals(
            serverBinaryDownloadUrl("2.1.9", ServerArchitecture.AMD64),
            serverBinaryDownloadUrl("v2.1.9", ServerArchitecture.AMD64)
        )
    }

    @Test
    fun parsesGnuChecksumsLine() {
        assertEquals(TEST_SHA256, parseServerChecksums(testChecksums(), "csqtt-linux-amd64"))
    }

    @Test
    fun parsesBinaryMarkerChecksumsLine() {
        val text = "$TEST_SHA256 *csqtt-linux-amd64\n"
        assertEquals(TEST_SHA256, parseServerChecksums(text, "csqtt-linux-amd64"))
    }

    @Test
    fun missingChecksumsEntryParsesToNull() {
        assertNull(parseServerChecksums(testChecksums(), "csqtt-linux-riscv"))
        assertNull(parseServerChecksums("not a checksums file\n", "csqtt-linux-amd64"))
    }

    @Test
    fun verifiedBytesAreReturned() {
        val result = downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch())
        assertArrayEquals(TEST_BYTES, result)
    }

    @Test
    fun tamperedBytesAreRejected() {
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch(binary = tamperedBytes()))
            fail("tampered server binary must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("целостност"))
        }
    }

    @Test
    fun emptyBytesAreRejected() {
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch(binary = ByteArray(0)))
            fail("empty server binary must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun missingChecksumsEntryIsRejected() {
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch(checksums = "00".repeat(32) + "  csqtt-linux-arm64\n"))
            fail("server binary without a checksums entry must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun truncatedBytesAreRejected() {
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch(binary = TEST_BYTES.dropLast(4).toByteArray()))
            fail("truncated server binary must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun sizeMismatchIsRejected() {
        try {
            downloadServerBinary(
                "2.1.9",
                ServerArchitecture.AMD64,
                testFetch(provenance = testProvenance(size = TEST_BYTES.size + 100))
            )
            fail("server binary with mismatched size must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("размер", ignoreCase = true))
        }
    }

    @Test
    fun provenanceMissingArtifactIsRejected() {
        val provenance = testProvenance().replace("{\"assetName\":\"csqtt-linux-amd64\",\"binarySize\":${TEST_BYTES.size}},", "")
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch(provenance = provenance))
            fail("server binary missing from provenance must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun provenanceFailureStopsBeforeBinaryFetch() {
        val requested = mutableListOf<String>()
        val fetch = testFetch(requested = requested)
        val failing: (String, (Long, Long?) -> Unit) -> ByteArray = { url, _ ->
            if (url.endsWith("/csqtt.server-provenance.json")) throw IOException("network down")
            fetch(url) { _, _ -> }
        }
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, failing)
            fail("provenance failure must abort the download")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertTrue(requested.none { it.endsWith("/csqtt-linux-amd64") })
    }

    @Test
    fun unsupportedArchitectureStopsEarly() {
        try {
            serverArchitectureForMachine("QuantumDOS-9000\n")
            fail("unsupported VPS architecture must stop early")
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("x86_64"))
            assertTrue(message.contains("aarch64"))
        }
    }

    @Test
    fun chunkedDownloadReportsMonotonicProgressEndingAtFull() {
        val fractions = mutableListOf<Float?>()
        val dir = tempFolder.newFolder("deploy-progress")
        resolveServerBinary(
            dir, ServerArchitecture.AMD64, "2.1.9",
            testFetch(chunkedBinary = true),
            onDownloadProgress = { fractions.add(it) }
        )
        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.last() == 1.0f)
        val known = fractions.filterNotNull()
        assertTrue(known.isNotEmpty())
        assertTrue(known.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun unchunkedDownloadStillCompletesProgress() {
        val fractions = mutableListOf<Float?>()
        val dir = tempFolder.newFolder("deploy-progress-plain")
        resolveServerBinary(
            dir, ServerArchitecture.AMD64, "2.1.9",
            testFetch(),
            onDownloadProgress = { fractions.add(it) }
        )
        assertEquals(listOf(1.0f), fractions)
    }

    @Test
    fun integrityFailureMapsToFriendlyError() {
        assertEquals(
            "Сервер не прошёл проверку целостности и не был установлен — повторите установку",
            friendlyDeployError("Сервер amd64 (v2.1.9) не прошёл проверку целостности (SHA-256); установка остановлена")
        )
    }

    @Test
    fun downloadFailuresMapToFriendlyError() {
        val expected = "Не удалось скачать сервер с GitHub Releases — проверьте интернет и повторите установку"
        assertEquals(
            expected,
            friendlyDeployError("Не удалось скачать сервер amd64 (v2.1.9): проверьте соединение и повторите")
        )
        assertEquals(
            expected,
            friendlyDeployError("Не удалось получить контрольные суммы сервера amd64 (v2.1.9): проверьте соединение и повторите")
        )
        assertEquals(
            expected,
            friendlyDeployError("Не удалось получить описание релиза v2.1.9: проверьте соединение и повторите")
        )
        assertEquals(
            expected,
            friendlyDeployError("Описание релиза v2.1.9 повреждено")
        )
        assertEquals(
            expected,
            friendlyDeployError("Описание релиза v2.1.9 не содержит csqtt-linux-amd64")
        )
        assertEquals(
            expected,
            friendlyDeployError("Контрольные суммы релиза v2.1.9 не содержат csqtt-linux-amd64")
        )
    }

    @Test
    fun sizeAndEmptyFailuresMapToIntegrityError() {
        val expected = "Сервер не прошёл проверку целостности и не был установлен — повторите установку"
        assertEquals(
            expected,
            friendlyDeployError("Размер сервера amd64 (v2.1.9) не совпадает с описанием релиза; установка остановлена")
        )
        assertEquals(
            expected,
            friendlyDeployError("Скачанный сервер amd64 (v2.1.9) пуст; повторите установку")
        )
    }
    @Test
    fun provenanceVersionMismatchIsRejected() {
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, testFetch(provenance = testProvenance("9.9.9")))
            fail("server binary with mismatched provenance must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun checksumsFailureStopsBeforeBinaryFetch() {
        val requested = mutableListOf<String>()
        val fetch = testFetch(requested = requested)
        val failing: (String, (Long, Long?) -> Unit) -> ByteArray = { url, _ ->
            if (url.endsWith("/SHA256SUMS")) throw IOException("network down")
            fetch(url) { received, total -> }
        }
        try {
            downloadServerBinary("2.1.9", ServerArchitecture.AMD64, failing)
            fail("checksums failure must abort the download")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertTrue(requested.none { it.endsWith("/csqtt-linux-amd64") })
    }

    @Test
    fun resolveWritesVerifiedBinaryIntoWorkingDir() {
        val dir = tempFolder.newFolder("deploy-ok")
        val file = resolveServerBinary(dir, ServerArchitecture.AMD64, "2.1.9", testFetch())
        assertEquals("csqtt-linux-amd64", file.name)
        assertEquals(dir, file.parentFile)
        assertArrayEquals(TEST_BYTES, file.readBytes())
    }

    @Test
    fun resolveRejectsTamperedBinaryLeavingNoFile() {
        val dir = tempFolder.newFolder("deploy-tampered")
        try {
            resolveServerBinary(dir, ServerArchitecture.AMD64, "2.1.9", testFetch(binary = tamperedBytes()))
            fail("tampered server binary must never reach the working dir")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("целостност"))
        }
        assertTrue((dir.listFiles()?.toList().orEmpty()).none { it.name == "csqtt-linux-amd64" })
    }

    @Test
    fun resolveFailureMapsToActionableMessage() {
        val dir = tempFolder.newFolder("deploy-offline")
        val failing: (String, (Long, Long?) -> Unit) -> ByteArray = { _, _ -> throw IOException("HTTP 404") }
        try {
            resolveServerBinary(dir, ServerArchitecture.ARM64, "2.1.9", failing)
            fail("failed download must abort resolution")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Не удалось"))
        }
        assertTrue((dir.listFiles()?.toList().orEmpty()).isEmpty())
    }
}
