package solutions.premiumai.pocketagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.Locale

/** Image and video generation through Venice, with the results kept in the app and savable to the phone. */
@Composable
internal fun MediaScreen(vm: AppViewModel) {
    var video by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var negative by rememberSaveable { mutableStateOf("") }
    var safeMode by rememberSaveable { mutableStateOf(true) }
    var aspect by rememberSaveable { mutableStateOf("") }
    var resolution by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf("") }
    var audio by rememberSaveable { mutableStateOf(true) }
    var keyDraft by remember { mutableStateOf("") }
    // Which option list is open: "model", "aspect", "resolution", "duration" or "" for none.
    var picking by remember { mutableStateOf("") }

    val models = if (video) vm.videoModels else vm.imageModels
    val selectedId = if (video) vm.videoModelId else vm.imageModelId
    val model = models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()

    // Fall back to common choices when Venice does not report a list for the model.
    val aspects = model?.aspectRatios?.takeIf { it.isNotEmpty() }
        ?: if (video) emptyList() else listOf("1:1", "16:9", "9:16")
    val resolutions = model?.resolutions ?: emptyList()
    val durations = model?.durations?.takeIf { it.isNotEmpty() } ?: listOf("5s")
    val preferredAspect = if (video) "16:9" else "1:1"
    val chosenAspect = aspect.takeIf { it in aspects } ?: aspects.firstOrNull { it == preferredAspect } ?: aspects.firstOrNull()
    val chosenResolution = resolution.takeIf { it in resolutions } ?: resolutions.firstOrNull()
    val chosenDuration = duration.takeIf { it in durations } ?: durations.first()

    Header(title = "Create", onBack = { vm.back() })

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (vm.veniceKey.isBlank()) {
            item {
                SectionTitle("Venice API key")
                Text(
                    "Images and video are requested from the phone directly, so the key is stored on this phone. " +
                        "Paste the same Venice key you use for chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        placeholder = { Text("Paste Venice API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.saveVeniceKey(keyDraft)
                            keyDraft = ""
                        },
                        enabled = keyDraft.isNotBlank(),
                    ) { Text("Save") }
                }
            }
        } else {
            item {
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    if (video) {
                        OutlinedButton(onClick = { video = false }) { Text("Image") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {}) { Text("Video") }
                    } else {
                        Button(onClick = {}) { Text("Image") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { video = true }) { Text("Video") }
                    }
                }

                OptionRow("Model", model?.name ?: if (models.isEmpty()) "Loading models" else "Choose") { picking = "model" }
                if (aspects.isNotEmpty()) OptionRow("Shape", chosenAspect ?: "Default") { picking = "aspect" }
                if (resolutions.isNotEmpty()) OptionRow("Resolution", chosenResolution ?: "Default") { picking = "resolution" }
                if (video) OptionRow("Length", chosenDuration) { picking = "duration" }

                if (video && model?.audioConfig == true) {
                    ToggleRow("Sound", audio) { audio = it }
                }
                if (!video) {
                    ToggleRow("Safe mode (blurs adult content)", safeMode) { safeMode = it }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text(if (video) "Describe the video" else "Describe the image") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!video) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = negative,
                        onValueChange = { negative = it },
                        placeholder = { Text("Things to avoid (optional)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (model != null && video) {
                            vm.requestVideo(model, prompt, chosenDuration, chosenResolution, chosenAspect, audio)
                        } else if (model != null) {
                            vm.generateImage(model, prompt, negative, chosenAspect, chosenResolution, safeMode)
                        }
                    },
                    enabled = model != null && prompt.isNotBlank() && vm.activity == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (video) "Get price and generate" else "Generate image") }
                if (video) {
                    Text(
                        "Video is billed per clip. You see Venice's price and confirm before anything is charged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        if (vm.media.isNotEmpty()) item { SectionTitle("Your creations") }
        items(vm.media, key = { it.name }) { file -> MediaCard(vm, file) }
        item { Spacer(Modifier.height(24.dp)) }
    }

    when (picking) {
        "model" -> PickerDialog(
            title = if (video) "Video model" else "Image model",
            options = models.map { it.name to it.id },
            onPick = { id ->
                picking = ""
                models.firstOrNull { it.id == id }?.let { vm.chooseMediaModel(video, it) }
            },
            onDismiss = { picking = "" },
            searchable = true,
        )
        "aspect" -> PickerDialog(
            title = "Shape",
            options = aspects.map { it to it },
            onPick = {
                aspect = it
                picking = ""
            },
            onDismiss = { picking = "" },
        )
        "resolution" -> PickerDialog(
            title = "Resolution",
            options = resolutions.map { it to it },
            onPick = {
                resolution = it
                picking = ""
            },
            onDismiss = { picking = "" },
        )
        "duration" -> PickerDialog(
            title = "Length",
            options = durations.map { it to it },
            onPick = {
                duration = it
                picking = ""
            },
            onDismiss = { picking = "" },
        )
    }

    vm.pendingVideo?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cancelVideo() },
            title = { Text("Generate this video?") },
            text = {
                Text(
                    if (pending.quote != null) {
                        "Venice quotes about $" + String.format(Locale.US, "%.2f", pending.quote) +
                            " for ${pending.duration} on ${pending.model.name}. This is charged to your Venice balance."
                    } else {
                        "Venice did not return a price for ${pending.model.name}. Generating will charge your Venice balance."
                    },
                )
            },
            confirmButton = { Button(onClick = { vm.confirmVideo() }) { Text("Generate") } },
            dismissButton = { TextButton(onClick = { vm.cancelVideo() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptionRow(label: String, value: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Loads a picture scaled down enough to display safely, whatever size Venice returned. */
private fun loadPreview(file: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
private fun MediaCard(vm: AppViewModel, file: File) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        if (file.extension == "mp4") {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoPath(file.path)
                        val controls = MediaController(context)
                        controls.setAnchorView(this)
                        setMediaController(controls)
                        // Show the first frame instead of a black box until the user presses play.
                        setOnPreparedListener { seekTo(1) }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            val preview = remember(file.path) { loadPreview(file) }
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Generated image",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Text("This file could not be shown.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row {
            TextButton(onClick = { vm.saveToPhone(file) }) { Text("Save to phone") }
            TextButton(onClick = { vm.deleteMedia(file) }) { Text("Delete") }
        }
    }
}
