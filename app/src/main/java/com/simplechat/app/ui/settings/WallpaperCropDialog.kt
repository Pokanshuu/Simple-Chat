package com.simplechat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.ui.theme.WallpaperState
import com.simplechat.app.ui.theme.drawWallpaper

/**
 * 壁纸裁剪器：选完图之后先让用户圈定"自己屏幕上显示哪一块"。
 *
 * 与系统壁纸的裁剪手势一致：**拖动平移、双指捏合缩放**，框就是整块屏幕
 * （比例天然等于用户屏幕，不需要再让用户选 16:9 / 4:3 之类）。
 *
 * 确认后写回的是 `scale / offsetX / offsetY` 三个已有参数 + `fit = COVER`，
 * 所以裁剪结果和"画面"分组里那几个滑块是同一份数据 —— 裁剪完再微调也行。
 */
@Composable
fun WallpaperCropDialog(
    image: ImageBitmap,
    initialScale: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onDismiss: () -> Unit,
    onConfirm: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(initialScale.coerceIn(1f, 3f)) }
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            /*
                             * 平移量要除以「当前缩放后的画布尺寸」：放大 2 倍之后，
                             * 同样的手指位移对应的相对位置变化只有一半 ——
                             * 不除的话放大后拖动会飞快。
                             */
                            val nextScale = (scale * zoom).coerceIn(1f, 3f)
                            scale = nextScale
                            offsetX = (offsetX - pan.x / (size.width * nextScale)).coerceIn(0f, 1f)
                            offsetY = (offsetY - pan.y / (size.height * nextScale)).coerceIn(0f, 1f)
                        }
                    }
                    .drawBehind {
                        drawWallpaper(
                            image,
                            WallpaperState(
                                fit = "COVER",
                                scale = scale,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                opacity = 1f,
                            ),
                        )
                    },
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "拖动调整位置，双指捏合缩放",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.White.copy(alpha = 0.75f))
                    }
                    Button(onClick = { onConfirm(scale, offsetX, offsetY) }) {
                        Text("完成")
                    }
                }
            }
        }
    }
}
