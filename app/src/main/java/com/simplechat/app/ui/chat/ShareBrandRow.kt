package com.simplechat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.LocalUseSimpleCatIcon
import com.simplechat.app.ui.theme.LocalTypographyFonts

/**
 * 导出图片的品牌行。用 Compose 渲染成 952 × 40 px 的位图再贴到页画布上，
 * 猫标与字标由同一个 Row 居中对齐，避免原生 Path 与基线各算一套。
 */
@Composable
internal fun ShareBrandRow() {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (LocalUseSimpleCatIcon.current) {
            Icon(
                AppIcons.SimpleCat,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            "SimpleChat",
            color = color,
            fontSize = 13.sp,
            lineHeight = 13.sp,
            fontFamily = LocalTypographyFonts.current.ui,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            // 字面视觉中心比行盒高约 1dp；下移对齐猫标。
            modifier = Modifier.offset(y = (-1).dp),
        )
    }
}
