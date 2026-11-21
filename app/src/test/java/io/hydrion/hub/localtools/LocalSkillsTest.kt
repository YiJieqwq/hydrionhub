package io.hydrion.hub.localtools

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSkillsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val source = "---\nname: local-notes\ndescription: >-\n  Keep local\n  notes.\n---\n\nRead the fixture text."

    @Test fun boundedTextImportOnly() {
        val doc = SkillDocument.read(source.byteInputStream())
        assertEquals("local-notes", doc.name)
        assertEquals("Keep local notes.", doc.description)
        assertEquals(doc, SkillDocument.parse("\uFEFF" + source.replace("\n", "\r\n")))
        listOf("", source.replace("name:", "allowed-tools:"), source.replace("local-notes", "../outside"),
            source.substringBeforeLast("Read"), source + "中".repeat(SkillByteLimit / 2)).forEach {
            assertTrue(runCatching { SkillDocument.parse(it) }.isFailure)
        }
        assertTrue(runCatching { SkillDocument.read(ByteArrayInputStream(byteArrayOf(0xff.toByte()))) }.isFailure)
        assertTrue(runCatching { SkillDocument.read(ByteArrayInputStream(ByteArray(SkillByteLimit + 1))) }.isFailure)
    }

    @Test fun activationRequiresReviewOfTheExactBytesAndReplacementDropsGrants() = runBlocking {
        val file = skillFile(tmp.root, "owner-a")
        val doc = SkillDocument.parse(source)
        file.update { it.install(doc, null) }
        assertNull(file.read().instructions(doc.name))
        assertTrue(runCatching { file.update { it.enable(doc.name, doc.digest, true) } }.isFailure)
        file.update { it.approve(doc.name, doc.digest).enable(doc.name, doc.digest, true) }
        assertEquals(source, skillFile(tmp.root, "owner-a").read().instructions(doc.name))
        assertNull(skillFile(tmp.root, "owner-b").read().instructions(doc.name))
        assertTrue(runCatching { file.update { it.install(doc, null) } }.isFailure)
        val changed = SkillDocument.parse(source + "\nExample command (inert text): echo fixture")
        file.update { it.install(changed, doc.digest) }
        assertNull(file.read().instructions(doc.name))
        assertFalse(file.read().entries.single().trusted)
        assertTrue(runCatching { file.update { it.approve(doc.name, doc.digest) } }.isFailure)
        file.update { it.approve(changed.name, changed.digest).enable(changed.name, changed.digest, true) }
        assertEquals(changed.source, file.read().instructions(doc.name))
        file.update { it.enable(changed.name, changed.digest, false) }
        assertNull(file.read().instructions(doc.name))
        file.update { it.revoke(changed.name, changed.digest) }
        assertFalse(file.read().entries.single().trusted)
        file.update { it.remove(changed.name, changed.digest) }
        assertTrue(file.read().entries.isEmpty())
    }
}
