package com.simplechat.app.data

import org.junit.Assert.*
import org.junit.Test

class ImportReportTest {
    @Test fun `partial attachments or unverified inventory cannot report complete`() {
        val report = ImportReport(2, 5, 0, 0, expected = 2, indexVerified = true)
        assertTrue(report.complete)
        assertFalse(report.copy(droppedImages = 1).complete)
        assertFalse(report.copy(indexVerified = false).complete)
        assertFalse(report.copy(expected = 3).complete)
    }
}
