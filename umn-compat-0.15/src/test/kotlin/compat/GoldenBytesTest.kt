package compat

import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * D: the bytes HEAD writes equal those UMN 0.15 wrote (`src/test/resources/golden/`, recorded by
 * `./gradlew umn-compat-0.15:recordGolden015`), and HEAD reads 0.15's. Needs no server.
 */
class GoldenBytesTest {
    private fun golden(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/golden/$name.bin"), "no golden $name").use { it.readBytes() }

    @Test
    fun `HEAD writes the log records 0_15 wrote`() {
        Golden.records().forEach { (name, bytes) -> assertContentEquals(golden(name), bytes, name) }
    }

    @Test
    fun `HEAD writes the proxy messages 0_15 wrote`() {
        Golden.messages().forEach { (name, bytes) -> assertContentEquals(golden(name), bytes, name) }
    }

    @Test
    fun `HEAD reads the log records 0_15 wrote`() {
        Golden.records().keys.forEach { name ->
            val record = assertNotNull(LogRecord.fromByteArray(golden(name), Schema.SERIALIZER, Schema.TYPES), name)
            assertContentEquals(golden(name), record.toByteArray(Schema.SERIALIZER), name)
        }
    }

    @Test
    fun `HEAD reads the proxy messages 0_15 wrote`() {
        Golden.messages().keys.forEach { name ->
            assertContentEquals(golden(name), AbstractMessage.fromByteArray(golden(name)).asByteArray(), name)
        }
    }
}
