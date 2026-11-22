package solutions.premiumai.pocketagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val Accent = Color(0xFF7CC4FF)
private val Added = Color(0xFF7BD88F)
private val Removed = Color(0xFFFF8A80)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00233D),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C232B),
    onSurfaceVariant = Color(0xFFB6C2CF),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.screen == Screen.Chat || vm.screen == Screen.Diff) { vm.back() }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        vm.error?.let { message ->
            Text(
                text = "$message  (tap to dismiss)",
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF8C2F2F))
                    .clickable { vm.error = null }
                    .padding(12.dp),
            )
        }
        when (vm.screen) {
            Screen.Connect -> ConnectScreen(vm)
            Screen.Sessions -> SessionsScreen(vm)
            Screen.Chat -> ChatScreen(vm)
            Screen.Diff -> DiffScreen(vm)
        }
    }

    vm.permissions.firstOrNull()?.let { PermissionDialog(vm, it) }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ConnectScreen(vm: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pocket Agent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Connect to the agent server running on your PC.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = vm.serverUrl,
            onValueChange = { vm.serverUrl = it },
            label = { Text("Server address") },
            placeholder = { Text("192.168.1.20:4096") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vm.password,
            onValueChange = { vm.password = it },
            label = { Text("Server password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { vm.connect() },
            enabled = !vm.working && vm.serverUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun SessionsScreen(vm: AppViewModel) {
    var choosing by remember { mutableStateOf(false) }

    Header(title = "Sessions") {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (vm.online) Added else Removed),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
        Button(onClick = { choosing = true }) { Text("New") }
    }

    if (vm.sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No sessions yet. Tap New, pick a project, and tell the agent what to change.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(vm.sessions, key = { it.id }) { session ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openSession(session) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (session.directory.isNotBlank()) {
                        Text(
                            session.directory,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    if (choosing) {
        PickerDialog(
            title = "Start a session in",
            options = listOf("Server's default folder" to "") + vm.projects.map { it.name to it.directory },
            onPick = { directory ->
                choosing = false
                vm.newSession(directory.ifBlank { null })
            },
            onDismiss = { choosing = false },
        )
    }
}

/** A simple list dialog. Each option is a label plus the value handed back when picked. */
@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(options) { (label, value) ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 10.dp),
                    ) {
                        Text(label)
                        if (value.isNotBlank() && value != label) {
                            Text(
                                value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColumnScope.ChatScreen(vm: AppViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    var pickingModel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep the newest text in view while the agent is writing.
    val lastSize = vm.messages.lastOrNull()?.blocks?.fold(0) { total, block ->
        total + when (block) {
            is Block.Text -> block.text.length
            is Block.Tool -> 1
        }
    } ?: 0
    LaunchedEffect(vm.messages.size, lastSize) {
        // Index size + 1 is the spacer after the last message.
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size + 1)
    }

    Header(title = vm.current?.title ?: "Session", onBack = { vm.back() }) {
        TextButton(onClick = { pickingModel = true }) { Text("Model") }
        TextButton(onClick = { vm.showDiff() }) { Text("Changes") }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        items(vm.messages, key = { it.id }) { message -> MessageView(message) }
        item { Spacer(Modifier.height(4.dp)) }
    }

    if (vm.busy) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Working", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("What should change?") },
            maxLines = 6,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (vm.busy && draft.isBlank()) {
            OutlinedButton(onClick = { vm.stop() }) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    vm.send(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Send") }
        }
    }

    if (pickingModel) {
        PickerDialog(
            title = "Model for this session",
            options = vm.models.map { "${it.name}  (${it.providerId})" to "${it.providerId}\n${it.id}" },
            onPick = { value ->
                pickingModel = false
                val providerId = value.substringBefore('\n')
                val modelId = value.substringAfter('\n')
                vm.models.firstOrNull { it.id == modelId && it.providerId == providerId }?.let(vm::pickModel)
            },
            onDismiss = { pickingModel = false },
        )
    }
}

@Composable
private fun MessageView(message: ChatItem) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isUser) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .padding(if (isUser) 12.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            message.blocks.forEach { block ->
                when (block) {
                    is Block.Text -> SelectionContainer {
                        Text(
                            text = block.text,
                            fontFamily = if (message.role == "notice") FontFamily.Monospace else FontFamily.Default,
                            color = if (message.role == "notice") {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    is Block.Tool -> ToolRow(block)
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: Block.Tool) {
    val color = when (tool.status) {
        "completed" -> Added
        "error" -> Removed
        else -> Accent
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(tool.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (tool.detail.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                tool.detail,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermissionDialog(vm: AppViewModel, permission: PermissionItem) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Allow ${permission.action}?") },
        text = {
            Column {
                permission.message?.let {
                    Text(it)
                    Spacer(Modifier.height(8.dp))
                }
                permission.resources.take(6).forEach { resource ->
                    Text(resource, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { vm.reply(permission, "once") }) { Text("Allow once") }
                TextButton(onClick = { vm.reply(permission, "always") }) { Text("Always allow") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.reply(permission, "reject") }) { Text("Reject") } },
    )
}

@Composable
private fun DiffScreen(vm: AppViewModel) {
    Header(title = "Changes", onBack = { vm.back() })

    if (vm.diff.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No file changes in this session yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(vm.diff, key = { it.file }) { file ->
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.file,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("+${file.additions}", color = Added, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text("-${file.deletions}", color = Removed, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    file.patch.lineSequence().take(400).forEach { line ->
                        Text(
                            text = line.ifEmpty { " " },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            color = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Added
                                line.startsWith("-") && !line.startsWith("---") -> Removed
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
