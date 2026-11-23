package com.simplechat.app.net

import com.simplechat.app.net.ImageGenerationIntent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationIntentTest {
    @Test fun creationDoesNotRequireTheWordImage() {
        listOf(
            "生成一个穿着24号球衣的黑人运动员，球衣是黄紫配色。",
            "生成一张科比布莱恩特的证件照。",
            "生成一张小猫的照片。",
            "Draw a picture of a cat",
            "Could you draw a rainy Hong Kong street?",
            "可以帮我画一幅雨夜街景吗？",
        ).forEach { assertEquals(it, CREATE, imageGenerationIntent(it)) }
    }

    @Test fun contextualEditsRequireAnActualReference() {
        listOf(
            "把它转化成。印象派。风格。",
            "把这张图的背景改成蓝色",
            "人物画错了，应该是科比",
            "再来一张",
            "Make this image brighter",
            "Turn it into an impressionist painting",
        ).forEach {
            assertEquals(it, EDIT, imageGenerationIntent(it, hasReference = true))
            assertEquals(it, NONE, imageGenerationIntent(it))
        }
    }

    @Test fun aNewSubjectAfterRegenerateDoesNotRequireOrReuseAnOldImage() {
        listOf("重新生成一张印象派风格的科比。", "重新生成一张印象派风格的篮球运动员").forEach { text ->
            assertEquals(CREATE, imageGenerationIntent(text))
            assertEquals(CREATE, imageGenerationIntent(text, hasReference = true))
            assertTrue(isExplicitImageRequest(text, CREATE))
        }
        assertEquals(EDIT, imageGenerationIntent("重新生成这张照片", hasReference = true))
        assertEquals(EDIT, imageGenerationIntent("重新生成一张", hasReference = true))
    }

    @Test fun explicitRoutingDoesNotTreatBroadCandidatesOrCapabilityQuestionsAsCommands() {
        fun direct(text: String, hasReference: Boolean = true) =
            isExplicitImageRequest(text, imageGenerationIntent(text, hasReference))

        val edit = "可以把那张照片变成印象派风格的吗？"
        assertTrue(direct(edit))
        assertFalse(direct(edit, hasReference = false))
        assertFalse(direct("你可以生成图片吗？"))
        assertFalse(direct("生成一个穿着24号球衣的运动员"))
        assertFalse(direct("重新生成一张表格"))
        assertFalse(direct("把它改成蓝色"))
    }

    @Test fun capabilityQuestionsDoNotGenerateEvenWithAnEarlierImage() {
        listOf("你可以生成图片吗？", "你怎么不能生成图片了？", "请问你能画图吗？", "Can you generate an image?")
            .forEach { assertEquals(it, CAPABILITY, imageGenerationIntent(it, hasReference = true)) }
    }

    @Test fun unrelatedTurnsNeverExposeTheImageTool() {
        assertFalse(shouldOfferImageTool(true, NONE))
        assertFalse(shouldOfferImageTool(true, CAPABILITY))
        assertTrue(shouldOfferImageTool(true, CREATE))
        assertTrue(shouldOfferImageTool(true, EDIT))
        assertFalse(shouldOfferImageTool(false, CREATE))
    }

    @Test fun discussionAndNonImageOutputsStayInChat() {
        listOf(
            "具体劳动反映人与自然的关系，抽象劳动反映商品生产者的社会关系",
            "请解释刚才图片里的构图", "Explain the image we generated earlier",
            "Who asked you to generate an image?", "不要生成图片，解释这个概念就好",
            "生成一个 Python 程序，用来处理图片", "生成一个处理图片的函数",
            "生成一个图片提示词", "画一张图，不用生成图片，只给提示词",
            "Create an image prompt", "Could you explain how to generate an image?",
        ).forEach { assertEquals(it, NONE, imageGenerationIntent(it, hasReference = true)) }
    }

    @Test fun onlyAnUnambiguousVisualDeliverableCanRequireATool() {
        assertTrue(isDefiniteImageCreation("生成一张科比布莱恩特的证件照。"))
        assertFalse(isDefiniteImageCreation("生成一个穿着24号球衣的运动员"))
        assertFalse(isDefiniteImageCreation("你可以生成图片吗？"))
        assertFalse(isDefiniteImageCreation("制作一个可生成图片的程序"))
    }

    @Test fun visualQuestionsAreDistinctFromGenerationAndUnrelatedTalk() {
        val followUp = "请解释刚才生成的图片里的构图"
        assertTrue(mentionsRecentGeneratedImage(followUp))
        assertEquals(NONE, imageGenerationIntent(followUp, hasReference = true))
        assertFalse(mentionsRecentGeneratedImage("解释一下抽象劳动"))
    }
}
