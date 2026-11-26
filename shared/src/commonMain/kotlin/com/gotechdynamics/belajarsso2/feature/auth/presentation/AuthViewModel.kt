package com.gotechdynamics.belajarsso2.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gotechdynamics.belajarsso2.core.network.OAuthFailure
import com.gotechdynamics.belajarsso2.feature.auth.data.AuthRepository
import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthCallback
import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isAwaitingLogin: Boolean = false,
    val isAuthenticated: Boolean = false,
    val name: String? = null,
    val email: String? = null,
    val error: String? = null,
)

sealed interface AuthEffect { data class OpenBrowser(val url: String) : AuthEffect }

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState(isLoading = true))
    val state = _state.asStateFlow()
    private val _effects = MutableSharedFlow<AuthEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()
    private var job: Job? = null
    private var initialized = false
    private var generation = 0L

    fun initialize(callback: OAuthCallback? = null) {
        if (callback != null) { initialized = true; work { publish(repository.handleCallback(callback)) } }
        else if (!initialized) {
            initialized = true
            work { try { publish(repository.initialize()) } catch (_: AuthRepository.LoginPending) { _state.value = AuthUiState(isAwaitingLogin = true) } }
        }
    }

    fun startLogin() = work {
        val request = repository.startLogin()
        _state.value = AuthUiState(isAwaitingLogin = true)
        _effects.emit(AuthEffect.OpenBrowser(request.url))
    }

    fun cancelLogin() {
        generation++; job?.cancel(); _state.value = AuthUiState(error = "Masuk dibatalkan.")
        viewModelScope.launch { repository.cancelLogin() }
    }

    fun logout() {
        generation++; job?.cancel(); _state.value = AuthUiState()
        viewModelScope.launch { repository.logout() }
    }

    private fun work(block: suspend () -> Unit) {
        job?.cancel()
        val expected = generation
        _state.value = AuthUiState(isLoading = true)
        job = viewModelScope.launch {
            try { if (expected != generation) return@launch; block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val sessionEnded = failure is OAuthFailure && (failure.invalidGrant || failure.status == 401)
                _state.value = AuthUiState(error = when {
                    failure is AuthRepository.AuthRejected -> failure.message
                    sessionEnded -> "Sesi berakhir. Silakan masuk kembali."
                    else -> "Koneksi SSO gagal. Silakan coba lagi."
                })
            }
        }
    }

    private fun publish(user: OAuthUser?) {
        _state.value = if (user == null) AuthUiState() else AuthUiState(true, false, true, user.name ?: user.id, user.email, null).copy(isLoading = false)
    }

    override fun onCleared() { repository.close() }
}
