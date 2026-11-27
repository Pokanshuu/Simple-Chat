package com.simplechat.app.data

import kotlinx.serialization.Serializable

/**
 * 「生成分享图片」的外观选项。
 *
 * 只保留一个开关：勾选时导出图跟随当前主题色、字体、字号、深浅色与壁纸；
 * 取消时回到旧版的浅色纯底外观。以后要加细项时，直接在这个对象里补字段，
 * 不用迁移 DataStore 的键。
 */
@Serializable
data class ShareImageSettings(
    val usePersonalizedStyle: Boolean = true,
)
