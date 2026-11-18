package com.simplechat.app.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompactionCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `checkpoint survives a new store instance and rejects changed source`() {
        val directory = temporary.newFolder()
        val state = CompactionCheckpoint(fingerprint = "source-and-settings", offset = 4000,
            summaries = listOf("accepted-summary"), chunkChars = 20_000, requestWindow = 32_768, completedBatches = 1)
        CompactionCheckpointStore(directory).save("conversation", state)
        val restored = CompactionCheckpointStore(directory)
        assertEquals(state, restored.load("conversation", "source-and-settings"))
        assertNull(restored.load("conversation", "changed-source-or-model"))
        assertNull(restored.load("other-conversation", "source-and-settings"))
        assertEquals(1, directory.listFiles()!!.size)
        restored.delete("conversation")
        assertNull(restored.load("conversation", "source-and-settings"))
    }

    @Test fun `corrupt and invalid checkpoints are ignored`() {
        val directory = temporary.newFolder()
        val store = CompactionCheckpointStore(directory)
        store.save("id", CompactionCheckpoint(fingerprint = "key"))
        directory.listFiles()!!.single().writeText("{ interrupted write")
        assertNull(store.load("id", "key"))
        store.save("id", CompactionCheckpoint(fingerprint = "key", offset = -1))
        assertNull(store.load("id", "key"))
        store.save("id", CompactionCheckpoint(fingerprint = "key", summaries = listOf("")))
        assertNull(store.load("id", "key"))
    }

    @Test fun `fingerprint covers content boundaries configuration and supplementary characters`() {
        val source = "中文😀".repeat(20_000)
        val fingerprint = compactionFingerprint(sequenceOf("model", "endpoint", "key", source))
        assertEquals(fingerprint, compactionFingerprint(sequenceOf("model", "endpoint", "key", source)))
        assertNotEquals(fingerprint, compactionFingerprint(sequenceOf("different-model", "endpoint", "key", source)))
        assertNotEquals(fingerprint, compactionFingerprint(sequenceOf("model", "endpoint", "key", source + "edit")))
        assertNotEquals(compactionFingerprint(sequenceOf("ab", "c")), compactionFingerprint(sequenceOf("a", "bc")))
        assertEquals(64, fingerprint.length)
    }

    @Test fun `checkpoint ids cannot create files outside the private directory`() {
        val directory = temporary.newFolder()
        val store = CompactionCheckpointStore(directory)
        store.save("../outside", CompactionCheckpoint(fingerprint = "key"))
        assertFalse(File(directory.parentFile, "outside.json").exists())
        assertEquals(1, directory.listFiles()!!.size)
        store.clear()
        assertEquals(0, directory.listFiles()!!.size)
    }
}
