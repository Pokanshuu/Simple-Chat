package com.simplechat.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Base64InputStream
import androidx.annotation.RequiresApi
import java.io.IOException

internal fun Attachment.originalImageMime(): String = mime.takeIf { it.startsWith("image/") }
    ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?.takeIf { it.startsWith("image/") }
    ?: "image/jpeg"

internal fun Attachment.originalImageName(): String {
    val fileName = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "SimpleChat" }
    if (fileName.substringAfterLast('.', "").isNotBlank()) return fileName
    val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(originalImageMime()) ?: "jpg"
    return "$fileName.$extension"
}

@RequiresApi(29)
internal fun saveOriginalImageToGallery(context: Context, attachment: Attachment) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, attachment.originalImageName())
        put(MediaStore.Images.Media.MIME_TYPE, attachment.originalImageMime())
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SimpleChat")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Could not create gallery entry")
    var saved = false
    try {
        copyOriginalImage(context, attachment, target)
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        if (resolver.update(target, published, null, null) != 1) throw IOException("Could not publish gallery entry")
        saved = true
    } finally {
        if (!saved) runCatching { resolver.delete(target, null, null) }
    }
}

internal fun copyOriginalImage(context: Context, attachment: Attachment, target: Uri) {
    val source = if (attachment.isStored) AttachmentFiles.open(context, attachment)
        else Base64InputStream(attachment.data.byteInputStream(Charsets.US_ASCII), Base64.DEFAULT)
    source.use { input ->
        val output = context.contentResolver.openOutputStream(target, "w") ?: throw IOException("Could not open destination")
        output.use {
            if (input.copyTo(it) == 0L) throw IOException("Image is empty")
        }
    }
}
