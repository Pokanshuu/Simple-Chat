package com.simplechat.app.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.simplechat.app.data.FontFiles
import com.simplechat.app.data.ImportedFont
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.TypographyDraft
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户发言层字体（自己发出的消息气泡）。
 *
 * `null` = 不覆盖，跟随全局 UI 字体 / 系统字体。
 */
val LocalUserFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * 模型回复层字体（AI 回复正文、Markdown、代码块之外的正文）。
 *
 * `null` = 跟随全局。
 */
val LocalAssistantFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * 字体文件 → FontFamily 的缓存。
 *
 * 同一个字体文件只构造一次：`FontFamily` 每次新对象都会让所有用到它的
 * `TextStyle` 变成"不同实例"，Compose 会判为样式变化、触发文本重排版。
 * 用户只是滚动列表，不该让整屏文字重新排。
 */
private val fontFamilyCache = ConcurrentHashMap<String, FontFamily?>()

/** 按导入的元数据取出 FontFamily；文件丢失 / 解析失败返回 null（回退系统字体）。 */
fun fontFamilyOf(context: Context, font: ImportedFont): FontFamily? {
    val file = FontFiles.fileOf(context, font.fileName)
    if (!file.exists()) return null
    return fontFamilyCache.getOrPut(file.absolutePath) {
        runCatching {
            FontFamily(Font(file = file, weight = FontWeight.Normal))
        }.getOrNull()
    }
}

/** 清理某个字体的缓存（删除字体后调用，避免删了还用着旧对象）。 */
fun forgetFontFamily(context: Context, font: ImportedFont) {
    fontFamilyCache.remove(FontFiles.fileOf(context, font.fileName).absolutePath)
}

/** Resolve the three selected font IDs from an editor draft using the same cache as runtime. */
fun typographyFontsOf(context: Context, fonts: List<ImportedFont>, draft: TypographyDraft): TypographyFonts {
    fun find(id: String?) = id?.let { selected -> fonts.firstOrNull { it.id == selected }?.let { fontFamilyOf(context, it) } }
    return TypographyFonts(
        ui = find(draft.uiFontId),
        user = find(draft.userFontId),
        assistant = find(draft.assistantFontId),
    )
}

fun typographyFontsOf(context: Context, settings: StoredSettings): TypographyFonts =
    typographyFontsOf(context, settings.fonts, TypographyDraft(
        fontScale = settings.fontScale,
        followSystemFont = settings.followSystemFont,
        uiFontId = settings.uiFontId,
        userFontId = settings.userFontId,
        assistantFontId = settings.assistantFontId,
        typography = settings.typography,
    ))

/**
 * 把整套排版换成指定字体族。
 *
 * 逐字段复制而不是 `Typography()` 重新构造：字号、行高、字重全在
 * [SimpleChatTypography] 里精调过，这里只该动 `fontFamily` 一项。
 */
fun Typography.withFontFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
