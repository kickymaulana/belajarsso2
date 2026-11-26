package com.gotechdynamics.belajarsso2.core.di

import com.gotechdynamics.belajarsso2.feature.auth.di.authModule
import org.koin.core.module.Module
import org.koin.dsl.module

val appModule = module { single<() -> Long> { { kotlin.time.Clock.System.now().toEpochMilliseconds() } } }

fun sharedModules(platformModule: Module): List<Module> = listOf(appModule, authModule, platformModule)
