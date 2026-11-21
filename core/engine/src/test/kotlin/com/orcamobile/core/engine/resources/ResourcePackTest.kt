package com.orcamobile.core.engine.resources

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourcePackTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Writes the format build-logic's PackEngineResourcesTask produces. */
    private fun pack(entries: List<Pair<String, String>>, magic: String = "OPK1"): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.write(magic.toByteArray(Charsets.US_ASCII))
            out.writeInt(entries.size)
            for ((path, content) in entries) {
                val name = path.toByteArray(Charsets.UTF_8)
                val data = content.toByteArray(Charsets.UTF_8)
                out.writeShort(name.size)
                out.write(name)
                out.writeInt(data.size)
                out.write(data)
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `unpacks every entry under the target directory`() {
        val target = temp.newFolder("resources")
        val count = ResourcePack.unpack(
            ByteArrayInputStream(
                pack(
                    listOf(
                        "profiles/Creality.json" to """{"name":"Creality"}""",
                        "profiles/Creality/machine/Creality K1 (0.4 nozzle).json" to "{}",
                    ),
                ),
            ),
            target,
        )

        assertEquals(2, count)
        assertEquals("""{"name":"Creality"}""", target.resolve("profiles/Creality.json").readText())
        assertEquals("{}", target.resolve("profiles/Creality/machine/Creality K1 (0.4 nozzle).json").readText())
    }

    @Test
    fun `rejects paths that escape the target directory`() {
        val target = temp.newFolder("resources")
        assertFailsWith<IOException> {
            ResourcePack.unpack(ByteArrayInputStream(pack(listOf("../evil.json" to "x"))), target)
        }
        assertFailsWith<IOException> {
            ResourcePack.unpack(ByteArrayInputStream(pack(listOf("/etc/evil" to "x"))), target)
        }
    }

    @Test
    fun `rejects data that is not a resource pack`() {
        assertFailsWith<IOException> {
            ResourcePack.unpack(ByteArrayInputStream(pack(emptyList(), magic = "ZIP!")), temp.newFolder())
        }
    }
}
