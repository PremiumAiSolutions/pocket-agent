package solutions.premiumai.pocketagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

data class ShellResult(val ok: Boolean, val output: String)

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

    suspend fun createSession(directory: String?, model: ModelItem?): JsonObject? {
        val body = buildJsonObject {
            if (!directory.isNullOrBlank()) putJsonObject("location") { put("directory", directory) }
            if (model != null) putJsonObject("model") {
                put("id", model.id)
                put("providerID", model.providerId)
            }
        }
        return request("POST", "/api/session", body = body).obj().o("data")
    }

    /** Newest 200 messages, returned oldest first. */
    suspend fun messages(sessionId: String): List<JsonObject> =
        request("GET", "/api/session/$sessionId/message", mapOf("limit" to "200", "order" to "desc"))
            .obj().a("data").mapNotNull { it.obj() }.reversed()

    /** Sends a message. Images travel inline as data URIs, the same way the desktop client attaches them. */
    suspend fun prompt(sessionId: String, text: String, images: List<Attachment> = emptyList()) {
        val body = buildJsonObject {
            put("text", text)
            if (images.isNotEmpty()) {
                putJsonArray("files") {
                    images.forEach { image ->
                        addJsonObject {
                            put("uri", image.dataUri)
                            put("name", image.name)
                        }
                    }
                }
            }
        }
        request("POST", "/api/session/$sessionId/prompt", body = body)
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

    /** AI providers the server can talk to, with how each one signs in. */
    suspend fun integrations(): JsonArray = request("GET", "/api/integration").obj().a("data")

    /** Stores an API key for a provider on the server. */
    suspend fun connectKey(integrationId: String, key: String) {
        request("POST", "/api/integration/$integrationId/connect/key", body = buildJsonObject { put("key", key) })
    }

    suspend fun sessionDiff(sessionId: String): JsonArray =
        request("GET", "/api/session/$sessionId/diff").obj().a("data")

    private fun at(directory: String?): Map<String, String?> = mapOf("location[directory]" to directory)

    /** The folder the server was started in. */
    suspend fun baseDirectory(): String? = request("GET", "/api/location").obj().s("directory")

    /** Names of the folders directly inside a directory on the PC. */
    suspend fun folders(directory: String?): List<String> =
        request("GET", "/api/fs/list", at(directory)).obj().a("data")
            .mapNotNull { it.obj() }
            .filter { it.s("type") == "directory" }
            .mapNotNull { it.s("path")?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\') }
            .filter { it.isNotBlank() }

    suspend fun branch(directory: String?): String? =
        request("GET", "/api/vcs", at(directory)).obj().o("data").o("branch").s("current")

    /**
     * Runs one command on the PC and waits for it to finish.
     * Commands are kept to a single program call so they work in PowerShell, cmd and bash alike.
     */
    suspend fun shell(command: String, directory: String?, timeoutSeconds: Int = 180): ShellResult {
        val body = buildJsonObject {
            put("command", command)
            if (!directory.isNullOrBlank()) put("cwd", directory)
        }
        var info = request("POST", "/api/shell", at(directory), body).obj().o("data")
        val id = info.s("id") ?: throw ApiException(0, "The server could not start the command")
        var polls = 0
        while (info.s("status") == "running") {
            if (polls++ >= timeoutSeconds * 2) throw ApiException(0, "Timed out waiting for: $command")
            delay(500)
            info = request("GET", "/api/shell/$id", at(directory)).obj().o("data")
        }
        val output = request("GET", "/api/shell/$id/output", at(directory)).obj().o("data").s("output") ?: ""
        val succeeded = info.s("status") == "exited" && info.s("exit")?.toDoubleOrNull() == 0.0
        return ShellResult(succeeded, output.trim())
    }

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

/**
 * Client for the Lovable public API. It lists projects and publishes them;
 * publishing through this API does not spend AI build credits.
 */
class LovableApi(private val key: String) {
    private val base = "https://api.lovable.dev"
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun request(method: String, path: String, query: List<Pair<String, String>> = emptyList()): JsonElement =
        withContext(Dispatchers.IO) {
            val url = "$base$path".toHttpUrl().newBuilder().apply {
                query.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            val request = Request.Builder()
                .url(url)
                .header("Lovable-API-Key", key)
                .header("Lovable-Version", "2026-09-11")
                .header("Accept", "application/json")
                .method(method, if (method == "GET") null else "{}".toRequestBody(jsonMedia))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val problem = runCatching { json.parseToJsonElement(text).obj() }.getOrNull()
                    val detail = problem.s("detail") ?: problem.s("title")
                    throw ApiException(
                        response.code,
                        when {
                            response.code == 401 -> "Lovable rejected the API key"
                            response.code == 403 -> "Lovable: ${detail ?: "this key or plan is not allowed to do that"}"
                            !detail.isNullOrBlank() -> "Lovable: $detail"
                            else -> "Lovable returned ${response.code}"
                        },
                    )
                }
                if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
            }
        }

    /** Projects across every workspace the key can see (first 100 per request). */
    suspend fun projects(): List<JsonObject> {
        val workspaces = request("GET", "/v1/workspaces").obj().a("data").mapNotNull { it.obj().s("id") }.take(10)
        if (workspaces.isEmpty()) return emptyList()
        val query = workspaces.map { "workspace_id" to it } + ("limit" to "100")
        return request("GET", "/v1/projects", query).obj().a("data").mapNotNull { it.obj() }
    }

    /** Starts a publish and returns the deployment id to poll. */
    suspend fun publish(projectId: String): String =
        request("POST", "/v1/projects/$projectId/publish").obj().let { it.s("id") ?: it.o("data").s("id") }
            ?: throw ApiException(0, "Lovable did not return a deployment id")

    suspend fun publishStatus(projectId: String, deploymentId: String): JsonObject =
        request("GET", "/v1/projects/$projectId/publish/$deploymentId").obj().let { it.o("data") ?: it }
            ?: JsonObject(emptyMap())
}
