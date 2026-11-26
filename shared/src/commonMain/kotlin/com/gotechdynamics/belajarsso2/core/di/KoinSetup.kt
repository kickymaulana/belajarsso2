package com.gotechdynamics.belajarsso2.core.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

fun initKoin(platformModule: Module): KoinApplication = startKoin {
    modules(sharedModules(platformModule))
}
