package com.ink.chat.data.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ink.chat.data.datastore.SettingsStore
import com.ink.chat.network.DeepSeekApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

/**
 * 图片上传编排（M5 §2.1 C2 / §4.6 UploadImage）：
 * 本地预校验（JPEG/PNG/GIF/WebP、≤64MiB）→ `POST /files` → file_id。
 * 【要点】请求体流式读取 ContentResolver——设备 1–2GB RAM，整图不进内存。
 */
class UploadManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: DeepSeekApi,
) {

    sealed class Result {
        data class Ok(val fileId: String, val fileName: String) : Result()
        object TooBig : Result()
        object Unsupported : Result()
        object NoApiKey : Result()
        object Failed : Result()
    }

    companion object {
        /** 单文件上限 64MiB（A.5） */
        const val MAX_BYTES = 64L * 1024 * 1024
    }

    /** 校验 → 上传；调用方按结果转 §3.5 文案 */
    suspend fun upload(uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = queryName(resolver, uri)
        val size = querySize(resolver, uri)
        if (size != null && size > MAX_BYTES) return@withContext Result.TooBig

        val mime = normalizeMime(resolver.getType(uri), fileName)
            ?: return@withContext Result.Unsupported

        val key = settings.plainApiKey() ?: return@withContext Result.NoApiKey

        val id = api.uploadFile(key, fileName, UriBody(resolver, uri, mime, size ?: -1L))
            ?: return@withContext Result.Failed
        Result.Ok(id, fileName)
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image"
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** 白名单归一：设备 MIME 不可靠时按扩展名兜底（如 "image/jpg"） */
    private fun normalizeMime(mime: String?, fileName: String): String? {
        mime?.lowercase()?.let {
            when (it) {
                "image/jpeg", "image/jpg" -> return "image/jpeg"
                "image/png" -> return "image/png"
                "image/gif" -> return "image/gif"
                "image/webp" -> return "image/webp"
            }
        }
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }
    }
}

/** 流式请求体：从 ContentResolver 读取，边读边写（不整图进内存） */
private class UriBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mime: String,
    private val length: Long,
) : RequestBody() {

    override fun contentType(): MediaType? = mime.toMediaTypeOrNull()

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取图片")
        input.use { it.copyTo(sink.outputStream()) }
    }
}
