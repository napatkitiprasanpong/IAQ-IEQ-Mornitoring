package com.example.iaqieqmornitoring.bridge

import kotlinx.coroutines.flow.MutableSharedFlow

/** Lets non-UI code (the bridge) ask AppNavigation to switch screens. */
object BridgeNav {
    val requests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    fun request(route: String) { requests.tryEmit(route) }
}
