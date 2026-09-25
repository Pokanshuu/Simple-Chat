package com.simplechat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons

/**
 * 空态：一个线性气泡 + 一句话。
 *
 * 刻意**不写说明小字** —— 空态该做的是提示"这里能干什么"，
 * 一句话够了；再加一行解释只会让这块空白显得更空。
 *
 * **暂不暴露模式切换** —— 扮演模式是远期功能，现在把「创作 / 扮演」两个
 * 并列摆出来，等于让用户在两件当前不等价的事情之间做选择。
 * 默认创作模式，`ConversationMode` 保留给以后。
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = AppIcons.MessageSquare,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )

        Spacer(Modifier.size(18.dp))

        Text(
            text = stringResource(R.string.empty_greeting),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}
