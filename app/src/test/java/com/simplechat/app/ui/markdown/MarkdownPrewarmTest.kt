package com.simplechat.app.ui.markdown

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPrewarmTest {
    @Test fun `warm only rendered Markdown blocks and preserve their exact math keys`() = runBlocking {
        val content = "- \$x\$\n      continuation\n\n\\[y^2\\]\n\n`\$z\$` and \\(w\\)"
        val warmed = mutableListOf<String>()
        warmMarkdownCache(content) { warmed.add(it) }
        assertEquals(listOf("- `SCMATH0`\n      continuation", "`\$z\$` and `SCMATH0`"), warmed)
    }

    @Test fun `skip oversized input and cap tiny paragraphs`() = runBlocking {
        val warmed = mutableListOf<String>()
        warmMarkdownCache("x".repeat(MarkdownPrewarmMaxChars + 1)) { warmed.add(it) }
        assertTrue(warmed.isEmpty())
        warmMarkdownCache((1..100).joinToString("\n\n")) { warmed.add(it) }
        assertEquals((1..48).map { it.toString() }, warmed)
    }

    @Test fun `cancelled window stops before parsing the next block`() = runBlocking {
        val warmed = mutableListOf<String>()
        val job = launch {
            warmMarkdownCache("first\n\nsecond\n\nthird") {
                warmed.add(it)
                cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("first"), warmed)
    }
}
