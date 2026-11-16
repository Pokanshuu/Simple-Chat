package com.simplechat.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExportFilesTest {
    @Test
    fun `failed generation removes partial staging file`() = runBlocking {
        val file = File.createTempFile("failed-export-", ".zip")
        val error = runCatching { stageArchive(file) { it.writeText("partial"); throw IOException("disk full") } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(file.exists())
    }

    @Test
    fun `cancelled generation removes staging file and preserves cancellation`() = runBlocking {
        val file = File.createTempFile("cancelled-export-", ".zip")
        val error = runCatching { stageArchive(file) { throw CancellationException("cancel") } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(file.exists())
    }

    @Test
    fun `empty or invalid archives cannot be offered for saving`() = runBlocking {
        for (contents in listOf("", "invalid zip")) {
            val file = File.createTempFile("invalid-export-", ".zip")
            assertTrue(runCatching { stageArchive(file) { it.writeText(contents) } }.isFailure)
            assertFalse(file.exists())
        }
    }

    @Test
    fun `saving reports monotonic byte progress and closes destination`() = runBlocking {
        val file = File.createTempFile("copy-export-", ".zip")
        try {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val progress = mutableListOf<Float>()
            var closed = false
            val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
            copyExportFile(file, { output }) { value ->
                if (value == 1f) assertTrue("100 percent requires a successful close", closed)
                progress += value
            }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        } finally { file.delete() }
    }

    @Test
    fun `destination close failure is not reported as a completed export`() = runBlocking {
        val file = File.createTempFile("close-export-", ".zip")
        try {
            file.writeText("backup")
            val progress = mutableListOf<Float>()
            val output = object : ByteArrayOutputStream() { override fun close() { throw IOException("provider close failed") } }
            assertTrue(runCatching { copyExportFile(file, { output }) { progress += it } }.isFailure)
            assertFalse(progress.contains(1f))
        } finally { file.delete() }
    }

    @Test
    fun `empty source is rejected before opening destination`() = runBlocking {
        val file = File.createTempFile("zero-export-", ".zip")
        try {
            var opened = false
            assertTrue(runCatching { copyExportFile(file, { opened = true; ByteArrayOutputStream() }) }.isFailure)
            assertFalse(opened)
        } finally { file.delete() }
    }
}
