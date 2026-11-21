package com.simplechat.app.data

private val standardSizes = mapOf("1:1" to "1024x1024", "3:2" to "1536x1024", "2:3" to "1024x1536")
private val flexibleSizes = standardSizes + mapOf(
    "4:3" to "1280x960", "3:4" to "960x1280",
    "16:9" to "1536x864", "9:16" to "864x1536",
    "21:9" to "1792x768", "9:21" to "768x1792",
)
private val geminiRatios = flexibleSizes.keys - "9:21"
private val flexibleGptImage = Regex("gpt-image-2(?:\\.\\d+)?(?:-(?:flare|sunburst))?(?:-\\d{4}-\\d{2}-\\d{2})?")

internal fun imageAspectRatios(adapter: String?, model: String?): List<String> = when (adapter) {
    "GEMINI" -> geminiRatios.toList()
    "DASHSCOPE", "OPENAI_IMAGES", "RESPONSES" -> imageSizes(adapter, model).keys.toList()
    else -> emptyList()
}

internal fun imageSizeForRatio(adapter: String?, model: String?, ratio: String?): String? =
    imageSizes(adapter, model)[ratio]

private fun imageSizes(adapter: String?, model: String?): Map<String, String> = when {
    adapter == "DASHSCOPE" -> standardSizes + flexibleSizes.filterKeys { it in setOf("4:3", "3:4", "16:9", "9:16") }
    adapter == "OPENAI_IMAGES" && flexibleGptImage.matches(model.orEmpty().substringAfterLast('/').lowercase()) -> flexibleSizes
    adapter == "OPENAI_IMAGES" || adapter == "RESPONSES" -> standardSizes
    else -> emptyMap()
}
