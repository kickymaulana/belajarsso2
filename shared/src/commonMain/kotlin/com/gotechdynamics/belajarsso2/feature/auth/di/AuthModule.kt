package com.gotechdynamics.belajarsso2.feature.auth.di

import com.gotechdynamics.belajarsso2.core.network.KtorOAuthApi
import com.gotechdynamics.belajarsso2.core.network.OAuthApi
import com.gotechdynamics.belajarsso2.feature.auth.data.AuthRepository
import com.gotechdynamics.belajarsso2.feature.auth.presentation.AuthViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val authModule = module {
    single<OAuthApi> { KtorOAuthApi(get()) }
    single { AuthRepository(get(), get(), get(), get(), get()) }
    viewModel { AuthViewModel(get()) }
}
