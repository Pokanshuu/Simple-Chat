package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端模型 id 的存取格式（[ModelIdList]）。
 *
 * 这份东西的失效方式全是"安静"的：多一个空串 → 选择器里一行点不动的空白；
 * 失败的解析 → 列表整个消失，用户看到的就是「拉取成功、模型却是空的」。
 */
class ModelIdListTest {

    @Test
    fun `编解码往返不丢顺序`() {
        val ids = listOf("deepseek-flash", "glm-5.2", "qwen3.8-max")
        assertEquals(ids, ModelIdList.decode(ModelIdList.encode(ids)))
    }

    @Test
    fun `解码丢掉空行与首尾空白`() {
        assertEquals(
            listOf("deepseek-flash", "glm-5.2"),
            ModelIdList.decode("\ndeepseek-flash \n\n  glm-5.2\n"),
        )
    }

    @Test
    fun `解码去重并保留先出现的顺序`() {
        assertEquals(listOf("b", "a"), ModelIdList.decode("b\na\nb"))
    }

    @Test
    fun `null 与空串解码为空列表`() {
        assertTrue(ModelIdList.decode(null).isEmpty())
        assertTrue(ModelIdList.decode("").isEmpty())
        assertTrue(ModelIdList.decode("\n \n").isEmpty())
    }

    @Test
    fun `编码去重并丢掉空 id`() {
        assertEquals("a\nb", ModelIdList.encode(listOf("a", "", " a ", "b")))
        assertTrue(ModelIdList.encode(listOf("", "  ")).isEmpty())
    }

    @Test
    fun `编码有上限`() {
        val many = (1..ModelIdList.MAX_IDS + 50).map { "model-$it" }
        assertEquals(ModelIdList.MAX_IDS, ModelIdList.decode(ModelIdList.encode(many)).size)
    }
}
