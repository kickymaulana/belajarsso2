package com.gotechdynamics.belajarsso2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthCallback
import com.gotechdynamics.belajarsso2.feature.auth.presentation.AuthViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App { url -> CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) } }
        authViewModel.initialize(intent.consumeOAuthCallback())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        authViewModel.initialize(intent.consumeOAuthCallback())
    }

    private fun Intent.consumeOAuthCallback(): OAuthCallback? {
        if (action != Intent.ACTION_VIEW) return null
        val uri = data ?: return null
        data = null
        clipData = null
        val valid = uri.isHierarchical && uri.scheme == CallbackValidator.SCHEME &&
            uri.encodedAuthority == CallbackValidator.HOST && uri.encodedPath == CallbackValidator.PATH &&
            uri.port == -1 && uri.fragment == null &&
            listOf("state", "code", "error").none { uri.getQueryParameters(it).size > 1 }
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        return OAuthCallback(uri.getQueryParameter("state"), code, error, valid && (code == null || error == null))
    }
}
