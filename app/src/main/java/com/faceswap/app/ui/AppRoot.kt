package com.faceswap.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.faceswap.app.MainViewModel

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        if (vm.ready && vm.acknowledged) HomeScreen(vm) else SetupScreen(vm)
    }
}
