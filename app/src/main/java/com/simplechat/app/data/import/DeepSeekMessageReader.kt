package com.simplechat.app.data.import

/** Decode fields directly: no raw-message copy, JsonObject tree or second copy of one fragment. */
internal fun readDeepSeekMessage(stream: JsonStream): DeepSeekHistory.ParsedMessage {
    var id: Long? = null
    var parent: Long? = null
    var role: String? = null
    var status: String? = null
    var insertedAt: String? = null
    val request = TextParts()
    val response = TextParts()
    val template = TextParts()
    val thinks = mutableListOf<Pair<String, Long>>()
    val images = mutableListOf<String>()
    stream.beginObject()
    while (stream.hasNextField()) {
        when (stream.fieldName()) {
            "id" -> id = stream.textValue()?.toLongOrNull()
            "parent_id" -> parent = stream.textValue()?.toLongOrNull()
            "role" -> role = stream.textValue()
            "status" -> status = stream.textValue()
            "inserted_at" -> insertedAt = stream.textValue()
            "fragments" -> {
                if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                stream.beginArray()
                while (stream.hasNextArrayElement()) {
                    var type: String? = null
                    var content = ""
                    var elapsed = 0L
                    val files = mutableListOf<String>()
                    stream.beginObject()
                    while (stream.hasNextField()) {
                        when (stream.fieldName()) {
                            "type" -> type = stream.textValue()
                            "content" -> content = stream.textValue().orEmpty()
                            "elapsed_secs" -> elapsed = stream.textValue()?.toLongOrNull() ?: 0L
                            "files" -> {
                                if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                                stream.beginArray()
                                while (stream.hasNextArrayElement()) {
                                    var image = false
                                    var path: String? = null
                                    stream.beginObject()
                                    while (stream.hasNextField()) {
                                        when (stream.fieldName()) {
                                            "is_image" -> image = stream.textValue() == "true"
                                            "export_path" -> path = stream.textValue()
                                            else -> stream.skipValue()
                                        }
                                    }
                                    if (image && path != null) files += path
                                }
                            }
                            else -> stream.skipValue()
                        }
                    }
                    when (type) {
                        "REQUEST" -> request.add(content)
                        "RESPONSE" -> response.add(content)
                        "TEMPLATE_RESPONSE" -> template.add(content)
                        "THINK" -> thinks += content to elapsed
                        "FILE" -> images += files
                    }
                }
            }
            else -> stream.skipValue()
        }
    }
    return DeepSeekHistory.ParsedMessage(
        number = requireNotNull(id) { "Message has no supported numeric id" },
        parentNumber = parent, role = role, status = status, insertedAt = insertedAt,
        request = request.value(), response = response.value(), template = template.value(),
        thinks = thinks, imagePaths = images,
    )
}

private class TextParts {
    private var first: String? = null
    private var joined: StringBuilder? = null
    fun add(text: String) {
        if (text.isEmpty()) return
        if (first == null) first = text
        else {
            val builder = joined ?: StringBuilder(first!!).also { joined = it }
            builder.append(text)
        }
    }
    fun value(): String = joined?.toString() ?: first.orEmpty()
}
