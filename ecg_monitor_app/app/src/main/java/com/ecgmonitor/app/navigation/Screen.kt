package com.ecgmonitor.app.navigation

import kotlinx.serialization.Serializable

/**
 * Navigation routes using type-safe navigation
 */
sealed class Screen {

    @Serializable
    object LiveMonitor : Screen()

    @Serializable
    object History : Screen()

    @Serializable
    data class SessionDetail(val sessionId: String) : Screen()

    @Serializable
    object Settings : Screen()
}

/**
 * Bottom navigation items
 */
