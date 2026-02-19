package com.ecgmonitor.app.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation route definitions used by [NavigationHost]. */
sealed class Screen {

    @Serializable
    data object LiveMonitor : Screen()

    @Serializable
    data object History : Screen()

    @Serializable
    data class SessionDetail(val sessionId: String) : Screen()

    @Serializable
    data object Settings : Screen()
}