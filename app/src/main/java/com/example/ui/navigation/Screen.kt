package com.example.ui.navigation

sealed class Screen {
    data object Home : Screen()
    data object Searching : Screen()
    data class Match(val matchId: String) : Screen()
    data class Chat(val matchId: String) : Screen()
    data object Profile : Screen()
    data object Settings : Screen()
    data object BlockedUsers : Screen()
    data object Guidelines : Screen()
    data object Terms : Screen()
}
