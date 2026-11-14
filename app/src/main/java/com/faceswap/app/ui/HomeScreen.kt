package com.faceswap.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.faceswap.app.MainViewModel

@Composable
private fun FacePicker(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { vm.cancelPicker() },
        title = { Text("Select faces to replace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tap the faces to swap. People are matched by identity, so this also applies to the other photos or video frames.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(vm.choices) { i, c ->
                        val on = i in vm.picked
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .border(if (on) 3.dp else 1.dp, if (on) cs.primary else cs.outline, RoundedCornerShape(14.dp))
                                .clickable { vm.togglePick(i) },
                        ) {
                            Image(c.thumb.asImageBitmap(), "Face ${i + 1}", Modifier.size(96.dp), contentScale = ContentScale.Crop)
                            if (on) Icon(
                                Icons.Filled.CheckCircle, null, tint = cs.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmPicker() }, enabled = vm.picked.isNotEmpty()) { Text("Swap ${vm.picked.size}") } },
        dismissButton = { TextButton(onClick = { vm.cancelPicker() }) { Text("Cancel") } },
    )
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    if (vm.pickerOpen) FacePicker(vm)
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val pickSources = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addSources(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.onPhotosPicked(it) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> vm.onVideoPicked(u) }
    val images = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    val videos = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("FaceSwap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard("My face", "Photos of the face you want to use. Several clear, front-facing photos work best.") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.sources, key = { it.absolutePath }) { f ->
                    Box {
                        Thumb(f.toUri(), 84.dp)
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(RoundedCornerShape(12.dp))
                                .background(cs.background.copy(alpha = 0.7f)).clickable { vm.removeSource(f) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Close, "Remove", Modifier.size(14.dp)) }
                    }
                }
                item {
                    Box(
                        Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant)
                            .clickable { pickSources.launch(images) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, "Add", tint = cs.primary) }
                }
            }
        }

        SectionCard("Options") {
            Text("Faces to replace", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.faceMode == 0, { vm.faceMode = 0 }, label = { Text("Largest") })
                FilterChip(vm.faceMode == 1, { vm.faceMode = 1 }, label = { Text("All") })
                FilterChip(vm.faceMode == 2, { vm.faceMode = 2 }, label = { Text("Choose faces") })
            }
            Text("Edge blending", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Slider(vm.softness, { vm.softness = it }, valueRange = 0f..1f)
            Text("Color and seam match", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Slider(vm.colorMatch, { vm.colorMatch = it }, valueRange = 0f..1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enhance face", fontWeight = FontWeight.Medium)
                    Text(
                        if (vm.hasEnhancer) "Sharper face, slower" else "Add a gfpgan .onnx model to enable",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                Switch(vm.enhance && vm.hasEnhancer, { vm.enhance = it }, enabled = vm.hasEnhancer)
            }
            if (vm.enhance && vm.hasEnhancer) Slider(vm.enhanceStrength, { vm.enhanceStrength = it }, valueRange = 0.2f..1f)
            Text("Video size", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.videoMaxSide == 0, { vm.videoMaxSide = 0 }, label = { Text("Original") })
                FilterChip(vm.videoMaxSide == 1920, { vm.videoMaxSide = 1920 }, label = { Text("1080p") })
                FilterChip(vm.videoMaxSide == 1280, { vm.videoMaxSide = 1280 }, label = { Text("720p") })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientButton("Photos", Icons.Filled.AddPhotoAlternate, enabled = !vm.busy && !vm.analyzing, modifier = Modifier.weight(1f)) { pickPhotos.launch(images) }
            GradientButton("Video", Icons.Filled.Videocam, enabled = !vm.busy && !vm.analyzing, modifier = Modifier.weight(1f)) { pickVideo.launch(videos) }
        }

        if (vm.analyzing) Text("Finding faces...", color = cs.tertiary)

        if (vm.busy) {
            SectionCard("Working") {
                Text(vm.status)
                LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.cancel() }) { Text("Cancel", color = cs.error) }
            }
        } else if (vm.status.isNotEmpty()) {
            Text(vm.status, color = cs.tertiary)
        }
        vm.message?.let { Text(it, color = cs.error) }

        if (vm.results.isNotEmpty()) {
            SectionCard("Results", "Saved in Pictures/FaceSwap and Movies/FaceSwap") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vm.results) { r ->
                        val open = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, if (r.video) "video/mp4" else "image/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                            Unit
                        }
                        if (r.video) {
                            Box(
                                Modifier.size(110.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).clickable { open() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Videocam, "Video", tint = cs.primary, modifier = Modifier.size(36.dp)) }
                        } else {
                            Thumb(r.uri, 110.dp, Modifier.clickable { open() })
                        }
                    }
                }
            }
        }

        TextButton(onClick = { vm.resetModels() }, enabled = !vm.busy) { Text("Change model files", color = cs.onSurfaceVariant) }
    }
}
