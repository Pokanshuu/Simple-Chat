package com.simplechat.app.ui.theme.tokens

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 主题文件 schema（docs/33 §33.4）。
 *
 * - **所有字段可选**：没写的就用下一层（内置默认值）。一个只改主色的主题文件只需要
 *   `{"schema":1,"light":{"colors":{"primary":"#FF6A00"}}}`。
 * - 未知字段忽略（老版本 App 读新主题文件不会崩）；`schema` 比当前支持的版本新时拒绝加载。
 * - 颜色一律 `#RRGGBB` 或 `#AARRGGBB` 字符串；尺寸单位 dp；透明度 0..1。
 * - 资源（字体、壁纸）用相对路径，指向主题包（.sctheme = zip）内的文件，见 §33.6。
 */

const val ThemeSchemaVersion = 1

@Serializable
data class ThemeSpec(
    val schema: Int = ThemeSchemaVersion,
    val id: String? = null,
    val name: String? = null,
    val author: String? = null,
    val version: String? = null,
    val light: ThemePalette? = null,
    val dark: ThemePalette? = null,
    val shapes: ShapeSpec? = null,
    val space: SpaceSpec? = null,
    val surface: SurfaceSpec? = null,
    val fonts: FontSpec? = null,
    val wallpaper: WallpaperSpec? = null,
)

/** 一套明暗模式下的配色。键名与 [com.simplechat.app.ui.theme.ChatColors] / ColorScheme 的语义对应。 */
@Serializable
data class ThemePalette(
    val colors: ColorSpec? = null,
)

@Serializable
data class ColorSpec(
    val primary: String? = null,
    val onPrimary: String? = null,
    val pageBackground: String? = null,
    val settingsBackground: String? = null,
    val card: String? = null,
    val menuBackground: String? = null,
    /** 正文色。次级文字、占位符、线条都按它 × alpha 派生（docs/32 §32.4.5），不单独给。 */
    val text: String? = null,
    val userBubble: String? = null,
    val onUserBubble: String? = null,
    val danger: String? = null,
    val warning: String? = null,
    val codeBlock: String? = null,
    val inlineCode: String? = null,
    val quoteBar: String? = null,
)

@Serializable
data class ShapeSpec(
    val soft: Float? = null,
    val menu: Float? = null,
    val container: Float? = null,
    val bubble: Float? = null,
    val bubbleTail: Float? = null,
)

@Serializable
data class SpaceSpec(
    val screen: Float? = null,
    val inline: Float? = null,
    val gap: Float? = null,
    val section: Float? = null,
)

@Serializable
data class SurfaceSpec(
    val drawerFloor: Float? = null,
    val userBubbleFloor: Float? = null,
    val rowFloor: Float? = null,
    val floatingFloorBlurred: Float? = null,
    val floatingFloorPlain: Float? = null,
    val floatingMaxBlur: Float? = null,
    val accentTint: Float? = null,
)

/** 预留：主题包内的字体文件（相对路径）。当前版本解析但不加载。 */
@Serializable
data class FontSpec(
    val ui: String? = null,
    val user: String? = null,
    val assistant: String? = null,
)

/** 预留：主题包内的壁纸与默认调节参数。当前版本解析但不加载。 */
@Serializable
data class WallpaperSpec(
    val image: String? = null,
    @SerialName("imageDark") val imageDark: String? = null,
    val opacity: Float? = null,
    val blur: Int? = null,
    val dim: Float? = null,
    val glass: Boolean? = null,
)

/** 解析主题文件。失败（JSON 错 / schema 太新）返回带原因的 failure，调用方据此提示用户。 */
object ThemeFiles {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): Result<ThemeSpec> = runCatching {
        val spec = json.decodeFromString(ThemeSpec.serializer(), text)
        require(spec.schema in 1..ThemeSchemaVersion) {
            "Unsupported theme schema ${spec.schema} (max $ThemeSchemaVersion)"
        }
        spec
    }

    fun encode(spec: ThemeSpec): String =
        Json { prettyPrint = true; encodeDefaults = false }.encodeToString(ThemeSpec.serializer(), spec)
}
