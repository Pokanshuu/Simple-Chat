package com.simplechat.app.ui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/** Can the library's paragraph be rendered directly as the exact same visible String? */
internal fun isLiteralParagraph(text: String, root: ASTNode): Boolean {
    val paragraph = root.children.singleOrNull() ?: return false
    if (paragraph.type != MarkdownElementTypes.PARAGRAPH || paragraph.startOffset != 0 ||
        paragraph.endOffset != text.length) return false
    // The annotator can unescape a TEXT leaf or decode an entity. Keep those on its normal path.
    if (text.any { it == '\\' || it == '&' || it == '\n' || it == '\r' || it == '\t' }) return false
    return paragraph.children.all { leaf ->
        when (leaf.type) {
            MarkdownTokenTypes.TEXT, MarkdownTokenTypes.COLON, MarkdownTokenTypes.EXCLAMATION_MARK,
            MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN -> true
            // The library drops leading WHITE_SPACE and emits one ASCII space for subsequent runs.
            MarkdownTokenTypes.WHITE_SPACE -> leaf.startOffset > 0 && leaf.endOffset == leaf.startOffset + 1 &&
                text[leaf.startOffset] == ' '
            else -> false
        }
    }
}
