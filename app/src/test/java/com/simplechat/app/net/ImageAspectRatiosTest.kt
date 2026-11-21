package com.simplechat.app.net

import com.simplechat.app.data.imageAspectRatios
import com.simplechat.app.data.imageSizeForRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAspectRatiosTest {
    @Test fun flexibleImagesUseExactPixelRatios() {
        val model = "openai/gpt-image-2.5-flare"
        val ratios = imageAspectRatios("OPENAI_IMAGES", model)
        assertTrue(ratios.containsAll(listOf("4:3", "3:4", "16:9", "9:16", "21:9")))
        for (ratio in ratios) {
            val (w, h) = requireNotNull(imageSizeForRatio("OPENAI_IMAGES", model, ratio)).split('x').map(String::toInt)
            val (x, y) = ratio.split(':').map(String::toInt)
            assertEquals(w * y, h * x)
            assertEquals(0, w % 16)
            assertEquals(0, h % 16)
        }
    }

    @Test fun legacyRoutesKeepFixedSizesAndGeminiUsesRatioDirectly() {
        assertEquals(listOf("1:1", "3:2", "2:3"), imageAspectRatios("OPENAI_IMAGES", "gpt-image-1"))
        assertNull(imageSizeForRatio("OPENAI_IMAGES", "gpt-image-1", "16:9"))
        assertTrue("16:9" in imageAspectRatios("GEMINI", "gemini-image"))
        assertNull(imageSizeForRatio("GEMINI", "gemini-image", "16:9"))
    }
}
