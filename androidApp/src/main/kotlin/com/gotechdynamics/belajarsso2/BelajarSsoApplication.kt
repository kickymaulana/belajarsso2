package com.gotechdynamics.belajarsso2

import android.app.Application
import com.gotechdynamics.belajarsso2.core.di.initKoin
import com.gotechdynamics.belajarsso2.core.network.OAuthConfig
import com.gotechdynamics.belajarsso2.core.storage.SecureStore
import com.gotechdynamics.belajarsso2.feature.auth.data.PkceGenerator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class BelajarSsoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val platformModule = module {
            single<SecureStore> { AndroidSecureStore(androidContext()) }
            single<PkceGenerator> { AndroidPkceGenerator() }
            single {
                OAuthConfig(
                    issuer = "http://10.0.2.2:8000",
                    clientId = "02609664-7149-432b-8111-f5d7cdb0e922",
                    redirectUri = "com.gotechdynamics.belajarsso2://oauth/callback",
                    allowEmulatorHttp = BuildConfig.DEBUG,
                )
            }
        }
        initKoin(platformModule).androidContext(this)
    }
}
