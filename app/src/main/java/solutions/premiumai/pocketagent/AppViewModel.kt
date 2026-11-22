package solutions.premiumai.pocketagent

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

enum class Screen { Connect, Sessions, Chat, Diff }

data class SessionItem(val id: String, val title: String, val directory: String)

data class ProjectItem(val name: String, val directory: String)

data class ModelItem(val id: String, val providerId: String, val name: String)

sealed interface Block {
    data class Text(val ordinal: Int, val text: String) : Block
    data class Tool(val id: String, val name: String, val status: String, val detail: String) : Block
}

/** role is "user", "assistant" or "notice". */
data class ChatItem(val id: String, val role: String, val blocks: List<Block>)

data class PermissionItem(
    val id: String,
    val sessionId: String,
    val action: String,
    val resources: List<String>,
    val message: String?,
)

data class DiffFile(val file: String, val status: String, val additions: Int, val deletions: Int, val patch: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("pocket_agent", Context.MODE_PRIVATE)
    private var api: OpenCodeApi? = null
    private var eventJob: Job? = null
    private var refreshJob: Job? = null

    var screen by mutableStateOf(Screen.Connect)
        private set
    var serverUrl by mutableStateOf(prefs.getString("url", "") ?: "")
    var password by mutableStateOf(prefs.getString("password", "") ?: "")
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var online by mutableStateOf(false)
        private set
    var serverVersion by mutableStateOf("")
        private set

    var sessions by mutableStateOf(listOf<SessionItem>())
        private set
    var projects by mutableStateOf(listOf<ProjectItem>())
        private set
    var models by mutableStateOf(listOf<ModelItem>())
        private set

    var current by mutableStateOf<SessionItem?>(null)
        private set
    var messages by mutableStateOf(listOf<ChatItem>())
        private set
    var busy by mutableStateOf(false)
        private set
    var permissions by mutableStateOf(listOf<PermissionItem>())
        private set
    var diff by mutableStateOf(listOf<DiffFile>())
        private set

    init {
        if (serverUrl.isNotBlank()) connect()
    }

    private fun run(block: suspend (OpenCodeApi) -> Unit) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                block(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Something went wrong"
            }
        }
    }

    fun connect() {
        if (working) return
        working = true
        error = null
        viewModelScope.launch {
            try {
                val client = OpenCodeApi(serverUrl, password)
                val info = client.info()
                api = client
                serverVersion = info.s("version") ?: ""
                prefs.edit().putString("url", serverUrl).putString("password", password).apply()
                screen = Screen.Sessions
                startEvents(client)
                loadHome(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = when (failure) {
                    is ApiException -> failure.message
                    is IllegalArgumentException -> "That server address doesn't look right"
                    else -> "Can't reach the server. Check the address and that your PC is on."
                }
            } finally {
                working = false
            }
        }
    }

    fun disconnect() {
        eventJob?.cancel()
        api = null
        online = false
        current = null
        messages = emptyList()
        permissions = emptyList()
        screen = Screen.Connect
    }

    private suspend fun loadHome(client: OpenCodeApi) {
        sessions = client.sessions().mapNotNull { it.obj() }.mapNotNull(::parseSession)
        projects = client.projects().mapNotNull { it.obj() }.mapNotNull { project ->
            val directory = project.s("canonical") ?: return@mapNotNull null
            ProjectItem(project.s("name") ?: directory.substringAfterLast('/').substringAfterLast('\\'), directory)
        }
        models = client.models().mapNotNull { it.obj() }
            .filter { it["enabled"].str() != "false" }
            .mapNotNull { model ->
                val id = model.s("id") ?: return@mapNotNull null
                val provider = model.s("providerID") ?: return@mapNotNull null
                ModelItem(id, provider, model.s("name") ?: id)
            }
    }

    fun refreshHome() = run { loadHome(it) }

    private fun parseSession(session: JsonObject): SessionItem? {
        val id = session.s("id") ?: return null
        val directory = session.o("location").s("directory") ?: ""
        return SessionItem(id, session.s("title") ?: "Untitled session", directory)
    }

    fun newSession(directory: String?) = run { client ->
        val created = client.createSession(directory)?.let(::parseSession) ?: return@run
        sessions = listOf(created) + sessions
        openSession(created)
    }

    fun openSession(session: SessionItem) {
        current = session
        messages = emptyList()
        busy = false
        diff = emptyList()
        screen = Screen.Chat
        run { client ->
            messages = client.messages(session.id).mapNotNull(::parseMessage)
            mergePermissions(client.permissions(session.id).mapNotNull { it.obj() }.mapNotNull(::parsePermission))
        }
    }

    fun back() {
        when (screen) {
            Screen.Diff -> screen = Screen.Chat
            Screen.Chat -> {
                current = null
                screen = Screen.Sessions
                refreshHome()
            }
            else -> Unit
        }
    }

    fun send(text: String) {
        val session = current ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Show the message right away; the server copy replaces it on the next refresh.
        messages = messages + ChatItem("local-${System.nanoTime()}", "user", listOf(Block.Text(0, trimmed)))
        busy = true
        run { it.prompt(session.id, trimmed) }
    }

    fun stop() {
        val session = current ?: return
        run { it.interrupt(session.id) }
    }

    fun pickModel(model: ModelItem) {
        val session = current ?: return
        run { it.switchModel(session.id, model.id, model.providerId) }
    }

    fun reply(permission: PermissionItem, decision: String) {
        permissions = permissions.filterNot { it.id == permission.id }
        run { it.replyPermission(permission.sessionId, permission.id, decision) }
    }

    fun showDiff() {
        val session = current ?: return
        screen = Screen.Diff
        run { client ->
            diff = client.sessionDiff(session.id).mapNotNull { it.obj() }.map { file ->
                DiffFile(
                    file = file.s("file") ?: "",
                    status = file.s("status") ?: "modified",
                    additions = file.s("additions")?.toIntOrNull() ?: 0,
                    deletions = file.s("deletions")?.toIntOrNull() ?: 0,
                    patch = file.s("patch") ?: "",
                )
            }
        }
    }

    // ---- live events -------------------------------------------------------

    private fun startEvents(client: OpenCodeApi) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            while (isActive) {
                try {
                    client.events().collect { event ->
                        online = true
                        handleEvent(event)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    // Fall through and reconnect.
                }
                online = false
                delay(2000)
                // Anything streamed while offline was missed, so reload from the server.
                scheduleRefresh()
            }
        }
    }

    private fun handleEvent(event: JsonObject) {
        val type = event.s("type") ?: return
        val data = event.o("data") ?: return
        val sessionId = data.s("sessionID")

        when (type) {
            "permission.asked" -> {
                parsePermission(data)?.let { mergePermissions(listOf(it)) }
                return
            }
            "permission.replied" -> {
                val requestId = data.s("requestID")
                permissions = permissions.filterNot { it.id == requestId }
                return
            }
            "session.created", "session.renamed", "session.deleted" -> {
                if (screen == Screen.Sessions) refreshHome()
                if (type == "session.renamed" && sessionId == current?.id) {
                    current = current?.copy(title = data.s("title") ?: current?.title ?: "")
                }
                return
            }
        }

        if (sessionId == null || sessionId != current?.id) return

        when (type) {
            "session.text.delta" -> appendDelta(
                messageId = data.s("assistantMessageID") ?: return,
                ordinal = data.s("ordinal")?.toIntOrNull() ?: 0,
                delta = data.s("delta") ?: return,
            )
            "session.execution.started" -> busy = true
            "session.status" -> busy = data.o("status").s("type") != "idle"
            "session.execution.succeeded", "session.execution.interrupted", "session.idle" -> {
                busy = false
                scheduleRefresh()
            }
            "session.execution.failed" -> {
                busy = false
                error = data.o("error").s("message") ?: "The agent run failed"
                scheduleRefresh()
            }
            "session.step.ended", "session.step.failed", "session.text.ended",
            "session.tool.called", "session.tool.success", "session.tool.failed",
            "session.inbox.delivered", "session.compaction.ended" -> scheduleRefresh()
        }
    }

    /** Streams assistant text into the list as it arrives. */
    private fun appendDelta(messageId: String, ordinal: Int, delta: String) {
        busy = true
        val index = messages.indexOfFirst { it.id == messageId }
        if (index == -1) {
            messages = messages + ChatItem(messageId, "assistant", listOf(Block.Text(ordinal, delta)))
            return
        }
        val item = messages[index]
        val blockIndex = item.blocks.indexOfFirst { it is Block.Text && it.ordinal == ordinal }
        val blocks = if (blockIndex == -1) {
            item.blocks + Block.Text(ordinal, delta)
        } else {
            item.blocks.mapIndexed { position, block ->
                if (position == blockIndex && block is Block.Text) block.copy(text = block.text + delta) else block
            }
        }
        messages = messages.toMutableList().also { it[index] = item.copy(blocks = blocks) }
    }

    /** Reloads the open session from the server, batching bursts of events. */
    private fun scheduleRefresh() {
        val session = current ?: return
        val client = api ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(250)
            try {
                val loaded = client.messages(session.id).mapNotNull(::parseMessage)
                if (current?.id == session.id) messages = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // The next event triggers another attempt.
            }
        }
    }

    private fun mergePermissions(incoming: List<PermissionItem>) {
        val known = permissions.map { it.id }.toSet()
        permissions = permissions + incoming.filterNot { it.id in known }
    }

    private fun parsePermission(data: JsonObject): PermissionItem? {
        val id = data.s("id") ?: return null
        val sessionId = data.s("sessionID") ?: return null
        return PermissionItem(
            id = id,
            sessionId = sessionId,
            action = data.s("action") ?: "action",
            resources = data.a("resources").mapNotNull { it.str() },
            message = data.s("message"),
        )
    }

    private fun parseMessage(message: JsonObject): ChatItem? {
        val id = message.s("id") ?: return null
        return when (message.s("type")) {
            "user" -> ChatItem(id, "user", listOf(Block.Text(0, message.s("text") ?: "")))
            "assistant" -> {
                val blocks = message.a("content").mapIndexedNotNull { ordinal, element ->
                    val part = element.obj() ?: return@mapIndexedNotNull null
                    when (part.s("type")) {
                        "text" -> part.s("text")?.takeIf { it.isNotBlank() }?.let { Block.Text(ordinal, it) }
                        "tool" -> {
                            val state = part.o("state")
                            Block.Tool(
                                id = part.s("id") ?: "tool-$ordinal",
                                name = part.s("name") ?: "tool",
                                status = state.s("status") ?: "",
                                detail = toolDetail(state),
                            )
                        }
                        else -> null
                    }
                }
                val failure = message.o("error").s("message")?.let { Block.Tool("error-$id", "error", "error", it) }
                val all = blocks + listOfNotNull(failure)
                if (all.isEmpty()) null else ChatItem(id, "assistant", all)
            }
            "shell" -> ChatItem(id, "notice", listOf(Block.Text(0, "$ " + (message.s("command") ?: ""))))
            "compaction" -> ChatItem(id, "notice", listOf(Block.Text(0, "Conversation summarized to save context")))
            else -> null
        }
    }

    /** One short line describing what a tool call is acting on. */
    private fun toolDetail(state: JsonObject?): String {
        val input = state.o("input") ?: return state.o("error").s("message") ?: ""
        val keys = listOf("filePath", "file_path", "path", "command", "pattern", "query", "url", "description")
        val value = keys.firstNotNullOfOrNull { input.s(it) } ?: input.values.firstNotNullOfOrNull { it.str() } ?: ""
        return value.lineSequence().firstOrNull()?.take(120) ?: ""
    }
}
