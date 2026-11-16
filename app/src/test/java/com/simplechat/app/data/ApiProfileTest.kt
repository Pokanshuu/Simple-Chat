package com.simplechat.app.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ApiProfileTest {
    @Test fun `encrypted profiles round trip independently and redact debug output`() {
        val a = ApiProfile("a", "Server A", "CUSTOM", "https://a.invalid", "model-a", "cipher-a")
        val b = a.copy(id = "b", name = "Server B", apiKeyEncrypted = "cipher-b", modelId = "model-b")
        assertEquals(listOf(a, b), decodeApiProfiles(apiProfileJson.encodeToString(listOf(a, b))))
        assertFalse(a.toString().contains("cipher-a"))
        assertFalse(b.toString().contains("cipher-b"))
    }
    @Test fun `invalid registries fail explicitly rather than erasing saved keys`() {
        for (raw in listOf("bad", "[]", "[{\"id\":\"a\"}]")) assertTrue(runCatching { decodeApiProfiles(raw) }.isFailure)
        val a = ApiProfile("same", "A", "CUSTOM", "", "")
        assertTrue(runCatching { decodeApiProfiles(apiProfileJson.encodeToString(listOf(a, a))) }.isFailure)
    }
}
