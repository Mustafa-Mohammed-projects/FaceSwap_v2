package com.faceswap.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.faceswap.app.MainViewModel
import com.faceswap.app.ml.ModelNames

@Composable
fun SetupScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importModels(it) }

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(cs.primary, cs.secondary))),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Icon(Icons.Filled.Face, null, tint = cs.onPrimary, modifier = Modifier.size(34.dp)) }
        Text("Welcome", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Select your model files once. They are copied into the app and everything runs on your phone, offline.",
            color = cs.onSurfaceVariant,
        )

        SectionCard("Model files") {
            ModelRow("Face swapper", ModelNames.SWAPPER, vm.modelStatus[ModelNames.SWAPPER] == true)
            ModelRow("Face detector", ModelNames.DETECTOR, vm.modelStatus[ModelNames.DETECTOR] == true)
            ModelRow("Face recognizer", ModelNames.RECOGNIZER, vm.modelStatus[ModelNames.RECOGNIZER] == true)
            ModelRow("Face enhancer (optional)", "gfpgan*.onnx", vm.modelStatus[ModelNames.ENHANCER] == true)
            Text(
                "Tip: det_10g.onnx and w600k_r50.onnx are inside buffalo_l.zip. You can select the zip directly, " +
                    "together with inswapper_128.onnx, in one go.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            if (vm.importing) {
                Text(vm.importLabel, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { vm.importProgress }, modifier = Modifier.fillMaxWidth())
            }
            GradientButton("Choose model files", Icons.Filled.UploadFile, enabled = !vm.importing, modifier = Modifier.fillMaxWidth()) {
                picker.launch(arrayOf("*/*"))
            }
        }

        vm.message?.let { Text(it, color = cs.error) }

        SectionCard("Before you start") {
            Text(
                "Use this app only with faces you have the right to use, such as your own. " +
                    "You are responsible for what you create and publish.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            val missing = ModelNames.REQUIRED.filter { vm.modelStatus[it] != true }
            if (missing.isNotEmpty()) {
                Text(
                    "Still missing: " + missing.joinToString(", ") + ". Select buffalo_l.zip to add the detector and recognizer.",
                    style = MaterialTheme.typography.bodySmall, color = cs.secondary,
                )
            }
            GradientButton("Continue", Icons.Filled.CheckCircle, enabled = vm.ready && !vm.importing, modifier = Modifier.fillMaxWidth()) {
                vm.acknowledge()
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, file: String, present: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (present) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (present) cs.tertiary else cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontWeight = FontWeight.Medium)
            Text(file, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}
