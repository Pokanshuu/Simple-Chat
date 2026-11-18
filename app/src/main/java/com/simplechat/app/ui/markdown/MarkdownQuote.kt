package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.simplechat.app.ui.theme.LocalChatColors
import org.intellij.markdown.MarkdownTokenTypes

/** A single continuous quote bar, with one paragraph gap rather than a full line per AST EOL. */
internal val continuousMarkdownQuote: MarkdownComponent = { model ->
    val bar = LocalChatColors.current.markdownQuoteBar
    val components = LocalMarkdownComponents.current
    Column(Modifier.fillMaxWidth().drawBehind {
        drawLine(bar, Offset.Zero, Offset(0f, size.height), 2.dp.toPx())
    }.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.node.children.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.BLOCK_QUOTE }.forEach { child ->
            MarkdownElement(node = child, components = components, content = model.content, includeSpacer = false)
        }
    }
}
