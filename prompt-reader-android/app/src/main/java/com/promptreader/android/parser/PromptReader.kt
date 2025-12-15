package com.promptreader.android.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.promptreader.android.novelai.NovelAiStealthDecoder
import com.promptreader.android.png.PngTextChunkReader
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream

object PromptReader {
    fun parse(context: Context, uri: Uri): PromptParseResult {
        val resolver = context.contentResolver

        // Detect format by magic bytes
        val header = resolver.openInputStream(uri)?.use { it.readNBytesCompat(16) } ?: ByteArray(0)
        val isPng = header.size >= 8 && header.sliceArray(0 until 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        )
        val isJpeg = header.size >= 2 && (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xFF) == 0xD8
        val isWebp = header.size >= 12 && String(header, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(header, 8, 4, Charsets.ISO_8859_1) == "WEBP"

        return when {
            isPng -> parsePng(context, uri)
            isJpeg || isWebp -> parseExifLike(context, uri)
            else -> PromptParseResult(tool = "Unknown", positive = "", negative = "", setting = "", raw = "Unsupported file")
        }
    }

    private fun parsePng(context: Context, uri: Uri): PromptParseResult {
        val resolver = context.contentResolver
        val textMap = resolver.openInputStream(uri)?.use { PngTextChunkReader.readTextChunks(it) } ?: emptyMap()

        val parameters = textMap["parameters"]
        val prompt = textMap["prompt"]
        val workflow = textMap["workflow"]
        val comment = textMap["Comment"]
        val software = textMap["Software"]
        val description = textMap["Description"]

        // SwarmUI in PNG: parameters contains sui_image_params
        if (!parameters.isNullOrBlank() && parameters.contains("sui_image_params")) {
            val r = SwarmUiParser.parse(parameters)
            return PromptParseResult(
                tool = "StableSwarmUI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // NovelAI legacy (must run before Fooocus; NovelAI Comment is also JSON)
        if (software == "NovelAI" && !description.isNullOrBlank() && !comment.isNullOrBlank()) {
            val r = NovelAiParser.parseLegacy(description, comment)
            return PromptParseResult(
                tool = "NovelAI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // Fooocus in PNG: Comment is JSON (but not every JSON comment is Fooocus)
        if (!comment.isNullOrBlank() && looksLikeFooocusComment(comment)) {
            val r = FooocusParser.parse(comment)
            return PromptParseResult(
                tool = "Fooocus",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // ComfyUI
        if (!prompt.isNullOrBlank()) {
            val r = ComfyUiParser.parse(promptJsonText = prompt, workflowText = workflow)
            return PromptParseResult(
                tool = r.tool,
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // A1111
        if (!parameters.isNullOrBlank()) {
            val r = A1111Parser.parse(parameters)
            val tool = if (textMap.containsKey("prompt")) "ComfyUI (A1111 compatible)" else "A1111 webUI"
            return PromptParseResult(
                tool = tool,
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // NovelAI stealth: decode bitmap and try
        val stealth = tryDecodeStealth(context, uri)
        if (stealth != null) {
            val r = NovelAiParser.parseStealth(stealth)
            return PromptParseResult(
                tool = "NovelAI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        return PromptParseResult(tool = "Unknown", positive = "", negative = "", setting = "", raw = textMap.toString())
    }

    private fun looksLikeFooocusComment(comment: String): Boolean {
        val trimmed = comment.trim()
        if (!trimmed.startsWith("{")) return false
        val obj = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() as? JSONObject ?: return false
        // Fooocus typically uses these keys.
        if (!obj.has("negative_prompt")) return false
        // Prompt might be named 'prompt' in Fooocus.
        return obj.has("prompt") || obj.has("styles") || obj.has("performance")
    }

    private fun parseExifLike(context: Context, uri: Uri): PromptParseResult {
        val resolver = context.contentResolver
        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }

        val userComment = exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)
        val software = exif?.getAttribute(ExifInterface.TAG_SOFTWARE)
        val imageDescription = exif?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)

        // SwarmUI in JPEG: user comment contains sui_image_params JSON
        if (!userComment.isNullOrBlank() && userComment.contains("sui_image_params")) {
            val r = SwarmUiParser.parse(userComment)
            return PromptParseResult(
                tool = "StableSwarmUI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // Fooocus: some variants store JSON in comment/usercomment
        if (!userComment.isNullOrBlank() && userComment.trim().startsWith("{") && userComment.contains("negative_prompt")) {
            val r = FooocusParser.parse(userComment)
            return PromptParseResult(
                tool = "Fooocus",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // A1111 / EasyDiffusion: usercomment often contains the full text
        if (!userComment.isNullOrBlank()) {
            val r = A1111Parser.parse(userComment)
            return PromptParseResult(
                tool = "A1111 webUI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // NovelAI stealth may exist in WEBP: try decode
        val stealth = tryDecodeStealth(context, uri)
        if (stealth != null) {
            val r = NovelAiParser.parseStealth(stealth)
            return PromptParseResult(
                tool = "NovelAI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        // NovelAI legacy sometimes shows up via EXIF tags.
        if (software == "NovelAI" && !imageDescription.isNullOrBlank()) {
            val comment = userComment ?: "{}"
            val r = NovelAiParser.parseLegacy(imageDescription, comment)
            return PromptParseResult(
                tool = "NovelAI",
                positive = r.positive,
                negative = r.negative,
                setting = r.setting,
                raw = r.raw,
                settingEntries = r.settingEntries,
                settingDetail = r.settingDetail,
            )
        }

        return PromptParseResult(tool = "Unknown", positive = "", negative = "", setting = "", raw = "No readable metadata")
    }

    private fun tryDecodeStealth(context: Context, uri: Uri): JSONObject? {
        val resolver = context.contentResolver
        val bitmap: Bitmap = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        return NovelAiStealthDecoder.tryDecode(bitmap)
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var readTotal = 0
        while (readTotal < n) {
            val r = this.read(buf, readTotal, n - readTotal)
            if (r <= 0) break
            readTotal += r
        }
        return if (readTotal == n) buf else buf.copyOf(readTotal)
    }
}
