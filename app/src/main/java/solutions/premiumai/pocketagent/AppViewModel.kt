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

enum class Screen { Connect, Sessions, Projects, Settings, Chat, Diff }

data class SessionItem(val id: String, val title: String, val directory: String)

data class ProjectItem(val name: String, val directory: String)

/** tools is false for models that cannot call tools, which the agent needs to edit files. */
data class ModelItem(val id: String, val providerId: String, val name: String, val tools: Boolean = true) {
    val label: String get() = if (tools) "$name  ($providerId)" else "$name  ($providerId, chat only)"
}

data class RepoItem(val fullName: String, val private: Boolean) {
    val name: String get() = fullName.substringAfterLast('/')
}

data class LovableProject(val id: String, val name: String)

data class ProviderItem(val id: String, val name: String, val connected: Boolean, val acceptsKey: Boolean)

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
    var providers by mutableStateOf(listOf<ProviderItem>())
        private set
    var notice by mutableStateOf<String?>(null)

    /** Folder the server was started in; its subfolders are the projects on the PC. */
    var baseDir by mutableStateOf("")
        private set
    var folders by mutableStateOf(listOf<ProjectItem>())
        private set
    /** GitHub login of the account signed in on the PC, or null when not signed in. */
    var githubUser by mutableStateOf<String?>(null)
        private set
    var githubChecked by mutableStateOf(false)
        private set
    var repos by mutableStateOf(listOf<RepoItem>())
        private set
    var branch by mutableStateOf<String?>(null)
        private set
    /** Short description of a long-running action, shown while it runs. */
    var activity by mutableStateOf<String?>(null)
        private set

    var lovableKey by mutableStateOf(prefs.getString("lovable_key", "") ?: "")
        private set
    var lovableProjects by mutableStateOf(listOf<LovableProject>())
        private set

    /** Model used for new sessions, chosen in Settings. Null means the server decides. */
    var defaultModel by mutableStateOf(
        prefs.getString("model_id", null)?.let { id ->
            ModelItem(id, prefs.getString("model_provider", "") ?: "", prefs.getString("model_name", id) ?: id)
        },
    )
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
        githubChecked = false
        githubUser = null
        repos = emptyList()
        screen = Screen.Connect
    }

    private suspend fun loadHome(client: OpenCodeApi) {
        sessions = client.sessions().mapNotNull { it.obj() }.mapNotNull(::parseSession)
        projects = client.projects().mapNotNull { it.obj() }.mapNotNull { project ->
            val directory = project.s("canonical") ?: return@mapNotNull null
            ProjectItem(project.s("name") ?: directory.substringAfterLast('/').substringAfterLast('\\'), directory)
        }
        loadModels(client)
        loadProviders(client)
        loadFolders(client)
    }

    private fun join(parent: String, child: String): String {
        val separator = if (parent.contains('\\')) "\\" else "/"
        return parent.trimEnd('/', '\\') + separator + child
    }

    private suspend fun loadFolders(client: OpenCodeApi) {
        baseDir = client.baseDirectory() ?: ""
        val skipped = setOf("node_modules", "\$RECYCLE.BIN", "System Volume Information")
        folders = client.folders(null)
            .filter { !it.startsWith(".") && it !in skipped }
            .sortedBy { it.lowercase() }
            .map { ProjectItem(it, join(baseDir, it)) }
    }

    // ---- projects and GitHub ----------------------------------------------

    fun openProjects() {
        screen = Screen.Projects
        run { client ->
            loadFolders(client)
            loadGithub(client)
        }
    }

    fun refreshGithub() = run { loadGithub(it) }

    /** GitHub access comes from the GitHub CLI signed in on the PC, so no token is kept on the phone. */
    private suspend fun loadGithub(client: OpenCodeApi) {
        val who = client.shell("gh api user --jq .login", baseDir.ifBlank { null }, 30)
        githubChecked = true
        if (!who.ok) {
            githubUser = null
            repos = emptyList()
            return
        }
        githubUser = who.output.lines().lastOrNull { it.isNotBlank() }?.trim()
        val listed = client.shell("gh repo list --limit 300 --json nameWithOwner,isPrivate", baseDir.ifBlank { null }, 60)
        if (!listed.ok) return
        val start = listed.output.indexOf('[')
        val end = listed.output.lastIndexOf(']')
        if (start == -1 || end <= start) return
        repos = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(listed.output.substring(start, end + 1)).arr()
        }.getOrNull().orEmpty().mapNotNull { it.obj() }.mapNotNull { repo ->
            RepoItem(repo.s("nameWithOwner") ?: return@mapNotNull null, repo["isPrivate"].str() == "true")
        }.sortedBy { it.fullName.lowercase() }
    }

    fun localFolder(repo: RepoItem): ProjectItem? = folders.firstOrNull { it.name.equals(repo.name, ignoreCase = true) }

    /** Opens a repo, cloning it into the base folder on the PC first when it is not there yet. */
    fun openRepo(repo: RepoItem) {
        localFolder(repo)?.let {
            newSession(it.directory)
            return
        }
        if (activity != null) return
        run { client ->
            activity = "Cloning ${repo.fullName}"
            try {
                val result = client.shell("gh repo clone ${repo.fullName}", baseDir.ifBlank { null }, 600)
                if (!result.ok) {
                    error = "Clone failed: " + result.output.takeLast(300)
                    return@run
                }
                loadFolders(client)
            } finally {
                activity = null
            }
            newSession(join(baseDir, repo.name))
        }
    }

    /** Stages everything, commits with the given message and pushes the current branch. */
    fun commitAndPush(message: String) {
        val directory = current?.directory?.ifBlank { null } ?: return
        if (activity != null) return
        // Double quotes would end the quoted message early in every shell.
        val safe = message.trim().replace('"', '\'').replace("\n", " ").replace("`", "'").replace("$", "").ifBlank { "Update from Pocket Agent" }
        run { client ->
            activity = "Committing and pushing"
            try {
                val added = client.shell("git add -A", directory, 60)
                if (!added.ok) {
                    error = "git add failed: " + added.output.takeLast(300)
                    return@run
                }
                val committed = client.shell("git commit -m \"$safe\"", directory, 60)
                val nothingNew = committed.output.contains("nothing to commit", ignoreCase = true)
                if (!committed.ok && !nothingNew) {
                    error = "Commit failed: " + committed.output.takeLast(300)
                    return@run
                }
                val pushed = client.shell("git push", directory, 180)
                if (!pushed.ok) {
                    error = "Push failed: " + pushed.output.takeLast(300)
                    return@run
                }
                notice = if (nothingNew) "Nothing new to commit. Branch pushed." else "Committed and pushed to ${branch ?: "the current branch"}"
            } finally {
                activity = null
            }
        }
    }

    // ---- Lovable ----------------------------------------------------------

    fun saveLovableKey(key: String) {
        lovableKey = key.trim()
        prefs.edit().putString("lovable_key", lovableKey).apply()
        lovableProjects = emptyList()
        if (lovableKey.isNotBlank()) loadLovableProjects(announce = true)
    }

    fun loadLovableProjects(announce: Boolean = false) {
        if (lovableKey.isBlank()) {
            error = "Add your Lovable API key in Settings first"
            return
        }
        run {
            lovableProjects = LovableApi(lovableKey).projects().mapNotNull { project ->
                val id = project.s("id") ?: return@mapNotNull null
                LovableProject(id, project.s("name") ?: project.s("display_name") ?: project.s("title") ?: id)
            }
            if (announce) notice = "Lovable connected: ${lovableProjects.size} projects"
        }
    }

    /** The Lovable project linked to the open session's folder, if one was chosen before. */
    fun linkedLovableProject(): String? = current?.directory?.let { prefs.getString("lovable_project:$it", null) }

    fun linkLovableProject(project: LovableProject) {
        val directory = current?.directory ?: return
        prefs.edit().putString("lovable_project:$directory", project.id).apply()
    }

    /** Builds and publishes the latest saved version on Lovable. Does not use AI credits. */
    fun publishLovable(projectId: String) {
        if (activity != null) return
        run {
            activity = "Publishing on Lovable"
            try {
                val lovable = LovableApi(lovableKey)
                val deploymentId = lovable.publish(projectId)
                repeat(100) {
                    delay(3000)
                    val status = lovable.publishStatus(projectId, deploymentId)
                    val url = status.s("url")
                    val failure = status.s("error_message") ?: status.s("error")
                    if (!url.isNullOrBlank()) {
                        notice = "Published: $url"
                        return@run
                    }
                    if (!failure.isNullOrBlank() || status.s("status") == "failed") {
                        error = "Lovable publish failed: ${failure ?: "no reason given"}"
                        return@run
                    }
                }
                error = "Lovable is still publishing. Check the project in Lovable."
            } finally {
                activity = null
            }
        }
    }

    private suspend fun loadModels(client: OpenCodeApi) {
        models = client.models().mapNotNull { it.obj() }
            .filter { it["enabled"].str() != "false" }
            .mapNotNull { model ->
                val id = model.s("id") ?: return@mapNotNull null
                val provider = model.s("providerID") ?: return@mapNotNull null
                val tools = model.o("capabilities")?.get("tools").str() != "false"
                ModelItem(id, provider, model.s("name") ?: id, tools)
            }
            .sortedWith(compareBy({ it.providerId }, { it.name.lowercase() }))
    }

    private suspend fun loadProviders(client: OpenCodeApi) {
        providers = client.integrations().mapNotNull { it.obj() }.mapNotNull { integration ->
            val id = integration.s("id") ?: return@mapNotNull null
            ProviderItem(
                id = id,
                name = integration.s("name") ?: id,
                connected = integration.a("connections").isNotEmpty(),
                acceptsKey = integration.a("methods").any { it.obj().s("type") == "key" },
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun openSettings() {
        screen = Screen.Settings
        refreshHome()
        if (!githubChecked) refreshGithub()
    }

    /** Sends an API key to the server, which stores it and unlocks that provider's models. */
    fun saveKey(provider: ProviderItem, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        run { client ->
            client.connectKey(provider.id, trimmed)
            notice = "${provider.name} key saved"
            loadProviders(client)
            // The server refreshes its model list shortly after a key is added.
            delay(1500)
            loadModels(client)
        }
    }

    fun chooseDefaultModel(model: ModelItem?) {
        defaultModel = model
        prefs.edit().apply {
            if (model == null) {
                remove("model_id").remove("model_provider").remove("model_name")
            } else {
                putString("model_id", model.id).putString("model_provider", model.providerId).putString("model_name", model.name)
            }
        }.apply()
    }

    fun refreshHome() = run { loadHome(it) }

    private fun parseSession(session: JsonObject): SessionItem? {
        val id = session.s("id") ?: return null
        val directory = session.o("location").s("directory") ?: ""
        return SessionItem(id, session.s("title") ?: "Untitled session", directory)
    }

    fun newSession(directory: String?) = run { client ->
        val created = client.createSession(directory, defaultModel)?.let(::parseSession) ?: return@run
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
            Screen.Settings, Screen.Projects -> screen = Screen.Sessions
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
        branch = null
        run { client -> branch = client.branch(session.directory.ifBlank { null }) }
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
