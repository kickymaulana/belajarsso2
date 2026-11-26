package com.gotechdynamics.belajarsso2

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gotechdynamics.belajarsso2.feature.auth.presentation.AuthEffect
import com.gotechdynamics.belajarsso2.feature.auth.presentation.AuthScreen
import com.gotechdynamics.belajarsso2.feature.auth.presentation.AuthUiState
import com.gotechdynamics.belajarsso2.feature.auth.presentation.AuthViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App(onOpenBrowser: (String) -> Unit = {}) {
    val viewModel = koinViewModel<AuthViewModel>()
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> if (effect is AuthEffect.OpenBrowser) onOpenBrowser(effect.url) }
    }
    MaterialTheme { AuthScreen(state, viewModel::startLogin, viewModel::logout, viewModel::cancelLogin) }
}

@Preview
@Composable
private fun AppPreview() { MaterialTheme { AuthScreen(AuthUiState(), {}, {}, {}) } }
