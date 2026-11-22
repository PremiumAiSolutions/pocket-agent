package solutions.premiumai.pocketagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

// Small helpers for reading loosely typed JSON without crashing on a missing field.
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject?.s(key: String): String? = this?.get(key).str()
fun JsonObject?.o(key: String): JsonObject? = this?.get(key).obj()
fun JsonObject?.a(key: String): JsonArray = this?.get(key).arr()

/**
 * Thin client for the OpenCode v2 server HTTP API.
 * Auth is HTTP Basic with the fixed username "opencode" and the server password.
 */
class OpenCodeApi(baseUrl: String, password: String) {
    private val base = normalize(baseUrl)
    private val auth = Credentials.basic("opencode", password)
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // The event stream stays open, so it must not time out between events.
    private val streamHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonObject? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val url = "$base$path".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()
        val requestBody = when {
            body != null -> body.toString().toRequestBody(jsonMedia)
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .method(method, requestBody)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { json.parseToJsonElement(text).obj().s("message") }.getOrNull()
                val message = when {
                    response.code == 401 -> "Wrong password"
                    !detail.isNullOrBlank() -> detail
                    else -> "Server returned ${response.code}"
                }
                throw ApiException(response.code, message)
            }
            if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    suspend fun info(): JsonObject = request("GET", "/api/info").obj() ?: JsonObject(emptyMap())

    suspend fun projects(): JsonArray = request("GET", "/api/project").arr()

    suspend fun sessions(): JsonArray =
        request("GET", "/api/session", mapOf("limit" to "50", "order" to "desc")).obj().a("data")

    suspend fun createSession(directory: String?): JsonObject? {
        val body = buildJsonObject {
            if (!directory.isNullOrBlank()) putJsonObject("location") { put("directory", directory) }
        }
        return request("POST", "/api/session", body = body).obj().o("data")
    }

    /** Newest 200 messages, returned oldest first. */
    suspend fun messages(sessionId: String): List<JsonObject> =
        request("GET", "/api/session/$sessionId/message", mapOf("limit" to "200", "order" to "desc"))
            .obj().a("data").mapNotNull { it.obj() }.reversed()

    suspend fun prompt(sessionId: String, text: String) {
        request("POST", "/api/session/$sessionId/prompt", body = buildJsonObject { put("text", text) })
    }

    suspend fun interrupt(sessionId: String) {
        request("POST", "/api/session/$sessionId/interrupt")
    }

    suspend fun permissions(sessionId: String): JsonArray =
        request("GET", "/api/session/$sessionId/permission").obj().a("data")

    suspend fun replyPermission(sessionId: String, requestId: String, decision: String) {
        request(
            "POST",
            "/api/session/$sessionId/permission/$requestId/reply",
            body = buildJsonObject { put("decision", decision) },
        )
    }

    suspend fun models(): JsonArray =
        request("GET", "/api/model", mapOf("types" to "chat")).obj().a("data")

    suspend fun switchModel(sessionId: String, modelId: String, providerId: String) {
        val body = buildJsonObject {
            putJsonObject("model") {
                put("id", modelId)
                put("providerID", providerId)
            }
        }
        request("POST", "/api/session/$sessionId/model", body = body)
    }

    suspend fun sessionDiff(sessionId: String): JsonArray =
        request("GET", "/api/session/$sessionId/diff").obj().a("data")

    /** Live server events. Each item is one event object: {id, type, data}. */
    fun events(): Flow<JsonObject> = callbackFlow {
        val request = Request.Builder()
            .url("$base/api/event")
            .header("Authorization", auth)
            .header("Accept", "text/event-stream")
            .build()
        val call = streamHttp.newCall(request)
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw ApiException(response.code, "Event stream failed (${response.code})")
                    val source = response.body?.source() ?: throw ApiException(0, "Empty event stream")
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        } else if (line.isEmpty() && data.isNotEmpty()) {
                            val event = runCatching { json.parseToJsonElement(data.toString()).obj() }.getOrNull()
                            data.clear()
                            if (event != null) send(event)
                        }
                    }
                }
                close()
            } catch (error: Exception) {
                close(error)
            }
        }
        awaitClose { call.cancel() }
    }

    companion object {
        /** Accepts "192.168.1.5:4096" as well as a full http(s) URL. */
        fun normalize(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
