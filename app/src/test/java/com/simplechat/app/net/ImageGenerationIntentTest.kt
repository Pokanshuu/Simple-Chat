package com.simplechat.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationIntentTest {
    @Test fun explicitImageRequestsEnableGeneration() {
        assertTrue(requestsImageGeneration("生成一个小猫的图片"))
        assertTrue(requestsImageGeneration("Draw a picture of a cat"))
        assertTrue(requestsImageGeneration("把这张图的背景改成蓝色"))
        assertTrue(requestsImageGeneration("Make this image brighter"))
    }

    @Test fun unrelatedFollowUpDoesNotInheritEarlierImageIntent() {
        assertFalse(requestsImageGeneration("具体劳动反映人与自然的关系，抽象劳动反映商品生产者的社会关系"))
        assertFalse(requestsImageGeneration("请解释刚才图片里的构图"))
        assertFalse(requestsImageGeneration("Explain the image we generated earlier"))
        assertFalse(requestsImageGeneration("Who asked you to generate an image?"))
        assertFalse(requestsImageGeneration("不要生成图片，解释这个概念就好"))
    }
}