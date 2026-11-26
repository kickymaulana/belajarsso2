package com.gotechdynamics.belajarsso2

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform