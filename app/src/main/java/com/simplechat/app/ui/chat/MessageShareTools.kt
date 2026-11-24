package com.simplechat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.simplechat.app.R
import com.simplechat.app.data.copyExportFile
import com.simplechat.app.data.userMessage
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import com.simplechat.app.ui.theme.LocalAccentColor
import com.simplechat.app.ui.theme.LocalTypographySettings
import com.simplechat.app.ui.theme.LocalTypographyFonts
import com.simplechat.app.ui.theme.LocalTypographyGlobalScale
import com.simplechat.app.ui.theme.LocalSystemTextScale
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MessageShareTools(
    selection: MessageShareSelection, groups: List<MessageShareGroup>, title: String, modifier: Modifier = Modifier,
    excerpt: MessageShareDocument? = null,
    onCloseExcerpt: () -> Unit = {},
) {
    val context = LocalContext.current
    val accent = LocalAccentColor.current
    val typography = LocalTypographySettings.current
    val typographyFonts = LocalTypographyFonts.current
    val globalFontScale = LocalTypographyGlobalScale.current
    val systemFontScale = LocalSystemTextScale.current ?: LocalDensity.current.fontScale
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var markdownFile by remember { mutableStateOf<File?>(null) }
    var images by remember { mutableStateOf<List<File>>(emptyList()) }
    var preview by remember { mutableStateOf(false) }
    var imageDocument by remember { mutableStateOf<MessageShareDocument?>(null) }
    var imageMode by rememberSaveable { mutableStateOf(ShareImageMode.LONG) }
    var hideBranding by rememberSaveable { mutableStateOf(false) }

    fun runExport(block: suspend () -> Unit) {
        if (busy) return
        busy = true; hint = null
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { hint = resources.getString(R.string.share_failed, error.userMessage()) }
            finally { busy = false }
        }
    }
    fun document() = MessageShareDocument(title, selection.selected(groups)).also { require(it.groups.isNotEmpty()) }
    fun openChooser(intent: Intent) {
        context.startActivity(Intent.createChooser(intent, resources.getString(R.string.action_share)))
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val file = markdownFile
        if (uri != null && file != null) runExport {
            try {
                withContext(Dispatchers.IO) { copyExportFile(file, { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) }) }
                hint = resources.getString(R.string.share_saved)
            } catch (error: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                throw error
            }
        }
    }
    val saveImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val snapshot = images
        if (tree != null && snapshot.isNotEmpty()) runExport {
            withContext(Dispatchers.IO) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val created = mutableListOf<android.net.Uri>()
                try {
                    val stamp = System.currentTimeMillis()
                    snapshot.forEachIndexed { index, file ->
                        val uri = checkNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "image/png", "SimpleChat-$stamp-${index + 1}.png"))
                        created += uri
                        copyExportFile(file, { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) })
                    }
                } catch (error: Throwable) {
                    created.forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                    throw error
                }
            }
            hint = resources.getString(R.string.share_saved)
        }
    }

    if (excerpt != null) {
        androidx.compose.runtime.LaunchedEffect(excerpt) {
            runExport {
                images = exportSharedImages(context, excerpt, ShareImageMode.LONG, accent,
                    typography = typography, typographyFonts = typographyFonts,
                    fontScale = globalFontScale, systemFontScale = systemFontScale)
                preview = true
            }
        }
        if (!preview) AlertDialog(
            onDismissRequest = onCloseExcerpt,
            title = { Text(stringResource(R.string.share_excerpt)) },
            text = { if (hint != null) Text(hint!!) else LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { OutlinedButton(onClick = onCloseExcerpt) { Text(stringResource(R.string.action_close)) } },
        )
    }
    fun closePreview() {
        preview = false
        if (excerpt != null) onCloseExcerpt()
    }

    if (excerpt == null) MessageShareBottomBar(selection.selected(groups).size, busy, hint,
        onImage = {
            imageDocument = document()
        },
        onMarkdown = {
            val snapshot = document()
            runExport {
                val file = exportSharedMarkdown(context, snapshot)
                markdownFile = file
                saveMarkdown.launch(file.name)
            }
        },
        onCopy = {
            val snapshot = document()
            runExport {
                val text = withContext(Dispatchers.Default) {
                    require(snapshot.messages.sumOf { it.content.length.toLong() + it.attachments.filterNot { a -> a.isImage }.sumOf { a -> a.data.length.toLong() } } <= MAX_SHARE_TEXT_CHARS) {
                        resources.getString(R.string.share_copy_too_long)
                    }
                    sharedPlainText(snapshot, resources.getString(R.string.export_role_me), resources.getString(R.string.export_role_ai), resources.getString(R.string.share_image_attachment_note)).also {
                        require(it.length <= MAX_SHARE_TEXT_CHARS) { resources.getString(R.string.share_copy_too_long) }
                    }
                }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, text))
                hint = resources.getString(R.string.share_copied)
            }
        },
        onShare = {
            val snapshot = document()
            runExport {
                val file = exportSharedMarkdown(context, snapshot)
                openChooser(messageShareIntent(context, listOf(file), "text/markdown", snapshot.title))
            }
        }, modifier = modifier,
    )

    imageDocument?.let { snapshot ->
        MessageShareImageOptions(
            mode = imageMode,
            onModeChange = { imageMode = it },
            hideBranding = hideBranding,
            onHideBrandingChange = { hideBranding = it },
            onDismiss = { imageDocument = null },
            onGenerate = { hideReasoning ->
                val mode = imageMode
                val branding = hideBranding
                imageDocument = null
                runExport {
                    images = exportSharedImages(context, snapshot, mode, accent, hideBranding = branding,
                        hideReasoning = hideReasoning, typography = typography, typographyFonts = typographyFonts,
                        fontScale = globalFontScale, systemFontScale = systemFontScale)
                    preview = true
                }
            },
        )
    }

    if (preview) Dialog(onDismissRequest = { if (!busy) closePreview() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val dark = LocalIsDarkTheme.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.share_image_preview, images.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                IconCircleButton(AppIcons.Close, stringResource(R.string.action_close), { if (!busy) closePreview() })
            }
            // Decode visible regions only: previewing a long screenshot must not allocate it again.
            val tiles by produceState<List<SharePreviewTile>>(emptyList(), images) {
                value = withContext(Dispatchers.IO) {
                    images.flatMap { file ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        (0 until bounds.outHeight step 1600).map { top ->
                            SharePreviewTile(file, bounds.outWidth, top, minOf(1600, bounds.outHeight - top), top + 1600 >= bounds.outHeight)
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                items(tiles, key = { "${it.file.absolutePath}:${it.top}" }) { tile ->
                    val image by produceState<android.graphics.Bitmap?>(null, tile) {
                        value = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            val decoder = checkNotNull(BitmapRegionDecoder.newInstance(tile.file.path, false))
                            try { decoder.decodeRegion(Rect(0, tile.top, tile.width, tile.top + tile.height), BitmapFactory.Options()) }
                            finally { decoder.recycle() }
                        }
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(tile.width.toFloat() / tile.height)) {
                        image?.let { Image(it.asImageBitmap(), stringResource(R.string.share_image),
                            Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    }
                    if (tile.last) Spacer(Modifier.height(16.dp))
                }
            }
            if (hint != null) Text(hint!!, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { saveImages.launch(null) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share_save_images)) }
                Button(onClick = { runExport { openChooser(messageShareIntent(context, images, "image/png", title)) } },
                    enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_share)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private data class SharePreviewTile(val file: File, val width: Int, val top: Int, val height: Int, val last: Boolean)
