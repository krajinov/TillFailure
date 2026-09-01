package com.delminiusapps.tillfailure

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform