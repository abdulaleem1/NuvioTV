package com.nuvio.tv.data.mediaserver.nexflix

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

internal class NexflixClient(http: OkHttpClient) {
    // A cold catalog can take Nexflix up to 20 portal pages to assemble.
    private val http = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(CATALOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CATALOG_TIMEOUT_SECONDS + 15, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap()
    ): T = decode(deserializer, execute("GET", baseUrl, path, query))

    suspend fun <T> post(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap()
    ): T = decode(deserializer, execute("POST", baseUrl, path, query))

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: String? = null
    ): String {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .header("Accept", "application/json")
                .method(
                    method,
                    body?.toRequestBody(JSON_MEDIA_TYPE) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null
                )
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val (status, text) = try {
            send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        if (status !in 200..299) throw ServerException(failureFor(status), "HTTP $status ${problemCode(text).orEmpty()}".trim())
        return text
    }

    private suspend fun send(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = response.body.let { readBoundedBody(it.source(), it.contentLength()) }
                        if (continuation.isActive) continuation.resume(response.code to body)
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (_: SerializationException) {
            throw ServerException(ServerFailure.UNSUPPORTED, "Unexpected Nexflix response")
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.UNSUPPORTED, "Unexpected Nexflix response")
        }

    private fun problemCode(body: String): String? =
        runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("code")?.jsonPrimitive?.content }.getOrNull()

    private fun failureFor(status: Int): ServerFailure = when (status) {
        // Nexflix answers 401 when the portal rejects the saved profile, not for a missing Nuvio login.
        401 -> ServerFailure.AUTH_REQUIRED
        403 -> ServerFailure.FORBIDDEN
        404 -> ServerFailure.NOT_FOUND
        422 -> ServerFailure.UNSUPPORTED
        in 500..599 -> ServerFailure.UNREACHABLE
        else -> ServerFailure.FAILED
    }

    private companion object {
        const val CATALOG_TIMEOUT_SECONDS = 90L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun readBoundedBody(source: BufferedSource, contentLength: Long, maxBytes: Long = 16L * 1024L * 1024L): String {
    if (contentLength > maxBytes) throw IOException("Response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("Response exceeds size limit")
    }
}
