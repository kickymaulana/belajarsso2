package com.gotechdynamics.belajarsso2.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(state: AuthUiState, onLogin: () -> Unit, onLogout: () -> Unit, onCancelLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("Belajar SSO 2", style = MaterialTheme.typography.headlineMedium)
        if (state.isLoading) { CircularProgressIndicator(Modifier.padding(top = 24.dp)); Text("Memuat…", Modifier.padding(top = 12.dp)) }
        if (state.isLoading || state.isAwaitingLogin) OutlinedButton(onCancelLogin, Modifier.padding(top = 24.dp)) { Text("Batalkan masuk") }
        else if (state.isAuthenticated) {
            Text(state.name ?: "Pengguna", Modifier.padding(top = 24.dp))
            state.email?.let { Text(it, Modifier.padding(top = 8.dp)) }
            OutlinedButton(onLogout, Modifier.padding(top = 24.dp)) { Text("Keluar") }
        } else Button(onLogin, Modifier.padding(top = 24.dp)) { Text("Masuk dengan SSO") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
