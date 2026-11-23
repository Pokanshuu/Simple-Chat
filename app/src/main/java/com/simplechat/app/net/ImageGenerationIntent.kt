package com.simplechat.app.net

internal enum class ImageGenerationIntent { NONE, CAPABILITY, CREATE, EDIT }

/** Tool exposure is stricter than provider availability: unrelated turns must not see image generation. */
internal fun shouldOfferImageTool(
    imageRouteAvailable: Boolean,
    intent: ImageGenerationIntent,
): Boolean = imageRouteAvailable && intent in setOf(ImageGenerationIntent.CREATE, ImageGenerationIntent.EDIT)

private val informational = Regex(
    "^(?:(?:请|帮我|能不能|可以)\\s*)*(?:不要|别|不必|不需要|不用|没让你|没有让你|谁让你|为什么要|怎么才能|如何|怎么(?:画|生成)|" +
        "解释|分析|点评|描述|教我|告诉我|给我.{0,12}(?:提示词|教程)|写.{0,12}提示词)|" +
        "^(?:please\\s+)?(?:don't|do not|didn't ask|why did you|who asked|how (?:to|do|can I)|explain|describe|analy[sz]e|write me a prompt)\\b",
    RegexOption.IGNORE_CASE,
)
private val capability = Regex(
    "^(?:请问[，, ]*)?(?:你\\s*)?(?:现在|还|真的)*(?:可以|能不能|能|会不会|会)\\s*(?:直接)?(?:生图|(?:生成|画|绘制|编辑|修改)\\s*(?:图片|图像|照片|图))\\s*(?:吗|么|嘛|不)?[？?。！! ]*$|" +
        "^(?:你)?(?:为什么|怎么)(?:现在)?(?:不|没).*?(?:生图|生成图片|画图)|" +
        "^(?:你)?(?:刚才)?(?:不(?:是(?:说)?)?|没)(?:有|能|会|可以)\\s*(?:生图(?:工具)?|生成图片|画图)\\s*(?:吗|么|嘛)?[？?。！! ]*$|" +
        "^(?:can|could|do) you (?:still )?(?:generate|create|draw|edit) (?:an? )?(?:images?|pictures?|photos?)[?.! ]*$",
    RegexOption.IGNORE_CASE,
)
private const val zhRequest = "^(?:(?:请问|请|帮我|给我|帮忙|能不能|可不可以|可以|能|麻烦|我想要|我想|你)\\s*)*"
private const val enRequest = "^(?:(?:please|can you|could you|would you)\\s+)*"
private val createImage = Regex(
    zhRequest + "(?:(?:生成|绘制|画出|画|创作|制作|设计|做|来|给我)\\s*.{0,100}(?:图片|图像|照片|插[图画]|头像|壁纸|封面|图标|配图|海报|肖像|证件照|图)|" +
        "(?:画(?:出)?|绘制|生成|来|给我)\\s*(?:一|两|几)?\\s*(?:张|幅)\\s*\\S|" +
        "(?:生成|画(?:出)?|绘制)\\s*(?:一个|一位|一只|一头|一匹|一座|一栋|一片|一群|一朵|一棵)\\s*\\S)|" +
        enRequest + "(?:(?:draw|paint|illustrate)\\s+\\S|(?:generate|create|design|make)\\s+.{0,100}\\b(?:image|picture|photo|illustration|avatar|wallpaper|cover|icon|poster|portrait)\\b)",
    RegexOption.IGNORE_CASE,
)
private val nonImageOutput = Regex(
    "(?:生成|制作|设计|做|给我|画出|画)\\s*(?:一[个份段篇套组条张幅]?|几[个份段篇套组条]?)?\\s*(?:代码|脚本|程序|算法|表格|表单|统计表|流程图|思维导图|示意图|架构图|文案|文章|故事|报告|小说|提示词|描述|视频|音频|计划|方案|函数|模型|链接)|" +
        "\\b(?:generate|create|design|make|draw)\\s+(?:(?:a|an|the|some)\\s+)?(?:prompt|caption|story|article|report|text|code|script|table|diagram|flowchart|video|plan)\\b|" +
        "(?:只要|只写|只给|只提供|只需|写成|改成)\\s*(?:文字|文本|提示词|文案|描述)|(?:^|[，,。；; ]+)(?:不要|不用|无需|不必)\\s*(?:生图|生成图片|画图)|" +
        "(?:生成|做|画|给我)[^，。；]{0,40}(?:代码|程序|函数|提示词|教程|摘要|脚本)|\\b(?:image|art|drawing|photo) prompts?\\b",
    RegexOption.IGNORE_CASE,
)
private val contextualEdit = Regex(
    zhRequest + "(?:(?:把|将|让)\\s*(?:它|这[张个幅]|那[张个幅]|(?:上|刚才的?)(?:一)?[张幅]|(?:图片|图像|照片|背景|颜色|画面)).{0,30}?(?:改|调整|变|转[化换]?成|换|加|删|移除|修|扩展|重绘|裁|调)|" +
        "(?:重画|重绘|重新(?:画|生成)|再(?:画|生成|来)一[张幅]|换个(?:风格|背景|颜色)|改成|换成|调亮|调暗|变亮|变暗|去掉背景)|" +
        "(?:人|人物|主体|背景)?(?:画错了|弄错了).{0,24}(?:应该是|换成|改成))|" +
        enRequest + "(?:(?:edit|modify|redraw|regenerate)\\b|(?:make|turn|change|convert)\\s+(?:it|this|that|the (?:image|picture|photo)|the background)\\s+\\S|" +
        "(?:remove|replace|change)\\s+(?:the background|the colors)|(?:try|make) another (?:version|one))",
    RegexOption.IGNORE_CASE,
)
private val voicePunctuation = Regex("[。！？.!?，,；;\\s]+")

/** Known non-execution requests are a guardrail, not an allowlist of image prompts. */
internal fun blocksImageToolExecution(text: String): Boolean = text.trim().let {
    it.isBlank() || informational.containsMatchIn(it) || capability.containsMatchIn(it) || nonImageOutput.containsMatchIn(it)
}

internal fun requestsContextualImageEdit(text: String): Boolean = imageGenerationIntent(text, hasReference = true) == ImageGenerationIntent.EDIT
private val earlierImageMention = Regex(
    "(?:这|那|上(?:一)?|刚才(?:生成|画|做)?的?)[张幅]?(?:图片|图像|照片|图)|(?:图片|图像|照片|画面)(?:里|中|上的)|" +
        "(?:上一张|刚才那张)(?:[，。？！、\\s]|$)|(?:这个人|它)(?:是|看起来|长什么样)|\\b(?:this|that|the (?:previous|last|generated)) (?:image|picture|photo)\\b",
    RegexOption.IGNORE_CASE,
)

internal fun mentionsRecentGeneratedImage(text: String): Boolean = earlierImageMention.containsMatchIn(text)

private val definiteCreation = Regex(
    zhRequest + "(?:生成|画出?|绘制)\\s*.{0,80}(?:图片|图像|照片|证件照|插画|海报|头像|壁纸)|" +
        enRequest + "(?:generate|create|draw|paint)\\s+(?:a |an )?(?:image|picture|photo|illustration|portrait|poster)\\b",
    RegexOption.IGNORE_CASE,
)

/** The broader candidate rules expose tools but must not force a potentially billable request. */
internal fun isDefiniteImageCreation(text: String): Boolean =
    imageGenerationIntent(text) == ImageGenerationIntent.CREATE && definiteCreation.containsMatchIn(text.trim())

private val freshImage = Regex(
    zhRequest + "(?:重新\\s*)?(?:生成|画出?|绘制)\\s*(?:一|两|几)?\\s*[张幅]\\s*\\S",
)

/** Direct execution is narrower than tool exposure and never relies on the model's prose. */
internal fun isExplicitImageRequest(text: String, intent: ImageGenerationIntent): Boolean = when (intent) {
    ImageGenerationIntent.CREATE -> isDefiniteImageCreation(text) || freshImage.containsMatchIn(text.trim())
    ImageGenerationIntent.EDIT -> mentionsRecentGeneratedImage(text)
    else -> false
}

/** History only resolves an edit/retry reference; it cannot create new intent on its own. */
internal fun imageGenerationIntent(text: String, hasReference: Boolean = false): ImageGenerationIntent {
    val turn = text.trim()
    if (turn.isEmpty() || informational.containsMatchIn(turn)) return ImageGenerationIntent.NONE
    if (capability.containsMatchIn(turn)) return ImageGenerationIntent.CAPABILITY
    if (nonImageOutput.containsMatchIn(turn)) return ImageGenerationIntent.NONE
    val normalized = voicePunctuation.replace(turn, " ")
    if (freshImage.containsMatchIn(normalized) && !mentionsRecentGeneratedImage(turn)) return ImageGenerationIntent.CREATE
    if (contextualEdit.containsMatchIn(normalized)) return if (hasReference) ImageGenerationIntent.EDIT else ImageGenerationIntent.NONE
    if (createImage.containsMatchIn(normalized)) return ImageGenerationIntent.CREATE
    return ImageGenerationIntent.NONE
}
