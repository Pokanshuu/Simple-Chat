package com.simplechat.app.data.import

/**
 * 从 `word/document.xml` 里抽出可读正文。
 *
 * ### 为什么不引 XML 解析器
 *
 * 要的是**能读的正文**，不是还原排版。而 docx 的正文结构简单到可以直接
 * 按几个标记做替换：文字都在 `<w:t>` 里，段落边界是 `</w:p>`。
 * 为这一件事拉进来一个 XML 库（以及它的安全配置、命名空间处理），
 * 在一个"轻量优先"的应用里不划算。
 *
 * ### 两个容易写错的地方
 *
 * 1. **段落标记要在剥标签之前处理** —— 先 `replace(Regex("<[^>]+>"), "")`
 *    的话 `</w:p>` 自己就被剥掉了，整篇会挤成一坨。
 * 2. **实体还原的顺序** —— `&amp;` 必须**最后**。反过来的话，
 *    原文里写着的 `&amp;lt;`（也就是字面量 `&lt;`）会被先还原成 `&lt;`，
 *    再被当成实体还原成 `<`，凭空多出一个尖括号。
 *
 * 抽成顶层纯函数是为了能单测：这两点出错都不会报错，只会"读起来不对"。
 */
internal fun docxXmlToText(xml: String): String =
    xml
        // 1. 段落 / 换行 / 制表符 —— 必须在剥标签之前
        .replace("</w:p>", "\n")
        .replace("<w:br/>", "\n")
        .replace("<w:br />", "\n")
        .replace("<w:cr/>", "\n")
        .replace("<w:tab/>", "\t")
        // 2. 剥掉所有标签
        .replace(Regex("<[^>]+>"), "")
        // 3. 实体还原（&amp; 必须最后）
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        // 4. 收掉多余空行，并去掉每行尾部空白（XML 里的缩进会带进来）
        .replace(Regex("\n{3,}"), "\n\n")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()
