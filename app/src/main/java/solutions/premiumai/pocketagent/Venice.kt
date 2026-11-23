package solutions.premiumai.pocketagent

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** An image or video model, with the option lists Venice reports for it (empty when not reported). */
data class MediaModel(
    val id: String,
    val name: String,
    val aspectRatios: List<String>,
    val resolutions: List<String>,
    val durations: List<String>,
    val audioConfig: Boolean,
)

data class VideoJob(val model: String, val queueId: String, val downloadUrl: String?)

/**
 * Client for Venice's media endpoints. The phone calls Venice directly for these,
 * because generated files come straight back to the device.
 */
class VeniceApi(private val key: String) {
    private val base = "https://api.venice.ai/api/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    // Image generation holds the connection open until the picture is done.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun post(path: String, body: JsonObject): Request = Request.Builder()
        .url("$base$path")
        .header("Authorization", "Bearer $key")
        .post(body.toString().toRequestBody(jsonMedia))
        .build()

    private fun failure(code: Int, text: String): ApiException {
        val parsed = runCatching { json.parseToJsonElement(text).obj() }.getOrNull()
        val detail = parsed.s("error") ?: parsed.o("error").s("message") ?: parsed.s("message")
        val message = when (code) {
            401 -> "Venice rejected the API key, or this model needs a higher plan"
            402 -> "Venice balance is empty. Add credits at venice.ai/settings/api"
            422 -> "Venice blocked this request: ${detail ?: "content policy"}"
            else -> "Venice: ${detail ?: "error $code"}"
        }
        return ApiException(code, message)
    }

    private fun strings(source: JsonObject?, vararg keys: String): List<String> =
        keys.firstNotNullOfOrNull { name ->
            (source?.get(name) as? JsonArray)?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }
        } ?: emptyList()

    /** type is "image" or "video". */
    suspend fun models(type: String): List<MediaModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/models?type=$type")
            .header("Authorization", "Bearer $key")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure(response.code, text)
            json.parseToJsonElement(text).obj().a("data").mapNotNull { it.obj() }.mapNotNull { model ->
                val id = model.s("id") ?: return@mapNotNull null
                val spec = model.o("model_spec")
                if (spec?.get("offline").str() == "true") return@mapNotNull null
                val constraints = spec.o("constraints")
                MediaModel(
                    id = id,
                    name = spec.s("name") ?: id,
                    aspectRatios = strings(constraints, "aspectRatios", "aspect_ratios"),
                    resolutions = strings(constraints, "resolutions"),
                    durations = strings(constraints, "durations").map { if (it.all(Char::isDigit)) "${it}s" else it },
                    audioConfig = spec?.get("supportsAudioConfig").str() == "true" ||
                        constraints?.get("supportsAudioConfig").str() == "true",
                )
            }.sortedBy { it.name.lowercase() }
        }
    }

    /** Returns the generated picture as JPEG bytes. */
    suspend fun generateImage(
        model: MediaModel,
        prompt: String,
        negative: String,
        aspect: String?,
        resolution: String?,
        safeMode: Boolean,
    ): ByteArray = withContext(Dispatchers.IO) {
        val full = buildJsonObject {
            put("model", model.id)
            put("prompt", prompt)
            put("format", "jpeg")
            put("safe_mode", safeMode)
            if (negative.isNotBlank()) put("negative_prompt", negative)
            if (model.aspectRatios.isNotEmpty()) {
                if (aspect != null) put("aspect_ratio", aspect)
                if (resolution != null && model.resolutions.isNotEmpty()) put("resolution", resolution)
            } else {
                val (width, height) = when (aspect) {
                    "16:9" -> 1280 to 720
                    "9:16" -> 720 to 1280
                    else -> 1024 to 1024
                }
                put("width", width)
                put("height", height)
            }
        }
        var response = http.newCall(post("/image/generate", full)).execute()
        if (response.code == 400) {
            // Models differ in which size and style fields they accept, so fall back to the bare request.
            response.close()
            val bare = buildJsonObject {
                put("model", model.id)
                put("prompt", prompt)
                put("format", "jpeg")
                put("safe_mode", safeMode)
            }
            response = http.newCall(post("/image/generate", bare)).execute()
        }
        response.use { result ->
            val text = result.body?.string().orEmpty()
            if (!result.isSuccessful) throw failure(result.code, text)
            val image = json.parseToJsonElement(text).obj().a("images").firstOrNull().str()
                ?: throw ApiException(0, "Venice returned no image. The prompt may have been blocked.")
            Base64.decode(image, Base64.DEFAULT)
        }
    }

    private fun videoOptions(model: MediaModel, duration: String, resolution: String?, aspect: String?, audio: Boolean) =
        buildJsonObject {
            put("model", model.id)
            put("duration", duration)
            if (resolution != null) put("resolution", resolution)
            if (aspect != null) put("aspect_ratio", aspect)
            if (model.audioConfig) put("audio", audio)
        }

    /** Price in USD for a video with these settings, or null when Venice gives none. Costs nothing. */
    suspend fun quoteVideo(model: MediaModel, duration: String, resolution: String?, aspect: String?, audio: Boolean): Double? =
        withContext(Dispatchers.IO) {
            http.newCall(post("/video/quote", videoOptions(model, duration, resolution, aspect, audio))).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failure(response.code, text)
                json.parseToJsonElement(text).obj().s("quote")?.toDoubleOrNull()
            }
        }

    /** Starts a paid video render. */
    suspend fun queueVideo(
        model: MediaModel,
        prompt: String,
        duration: String,
        resolution: String?,
        aspect: String?,
        audio: Boolean,
    ): VideoJob = withContext(Dispatchers.IO) {
        val body = JsonObject(videoOptions(model, duration, resolution, aspect, audio) + buildJsonObject { put("prompt", prompt) })
        http.newCall(post("/video/queue", body)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure(response.code, text)
            val parsed = json.parseToJsonElement(text).obj()
            VideoJob(
                model = parsed.s("model") ?: model.id,
                queueId = parsed.s("queue_id") ?: throw ApiException(0, "Venice did not return a job id"),
                downloadUrl = parsed.s("download_url"),
            )
        }
    }

    /** Checks a render. Writes the MP4 to target and returns true once it is finished. */
    suspend fun retrieveVideo(job: VideoJob, target: File): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", job.model)
            put("queue_id", job.queueId)
        }
        val finishedElsewhere = http.newCall(post("/video/retrieve", body)).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (response.isSuccessful && type.startsWith("video/")) {
                response.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } }
                return@withContext true
            }
            val text = response.body?.string().orEmpty()
            // Busy or overloaded answers mean "ask again later", not failure.
            if (response.code == 429 || response.code == 500 || response.code == 503) return@withContext false
            if (!response.isSuccessful) throw failure(response.code, text)
            runCatching { json.parseToJsonElement(text).obj().s("status") }.getOrNull() == "COMPLETED"
        }
        if (!finishedElsewhere || job.downloadUrl == null) return@withContext false
        // Some models hand back a one-time download link instead of the file itself.
        http.newCall(Request.Builder().url(job.downloadUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "The video download link failed (${response.code})")
            response.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        true
    }
}
