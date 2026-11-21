package com.simplechat.app.net

/** Only expose image generation when the current user turn asks to create or edit an image. */
internal fun requestsImageGeneration(text: String): Boolean {
    val currentTurn = text.trim()
    if (currentTurn.isEmpty()) return false

    val negativeOrInformational = Regex(
        "(?:不要|别|不必|不需要|不用|没让你|没有让你|谁让你|为什么要|怎么才能|如何才能).{0,24}(?:生成|生图|画|绘制|图片|图像|照片)|" +
            "(?:don't|do not|didn't ask|why did you|who asked).{0,48}(?:generate|create|draw|image|picture|photo)",
        RegexOption.IGNORE_CASE,
    )
    if (negativeOrInformational.containsMatchIn(currentTurn)) return false

    val createImage = Regex(
        "(?:生成|绘制|画出|画一张|创作|制作|做一张|做个).{0,24}(?:图片|图像|照片|插画|头像|壁纸|封面|图标|配图|图)|" +
            "(?:generate|create|draw|paint|design|make).{0,48}(?:image|picture|photo|illustration|avatar|wallpaper|cover|icon)",
        RegexOption.IGNORE_CASE,
    )
    if (createImage.containsMatchIn(currentTurn)) return true

    val editImage = Regex(
        "(?:把|将|让|请把|请将).{0,24}(?:这张|这个|该|此)?(?:图片|图像|照片|图|它).{0,32}(?:改|调整|变|换|加|删|移除|修|扩展|重绘)|" +
            "(?:edit|modify|change|adjust|redraw|remove|add to).{0,32}(?:this |the )?(?:image|picture|photo)|" +
            "(?:make|turn).{0,24}(?:this |the )?(?:image|picture|photo).{0,24}(?:brighter|darker|red|blue|warmer|cooler|more|less)",
        RegexOption.IGNORE_CASE,
    )
    return editImage.containsMatchIn(currentTurn)
}