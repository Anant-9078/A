package com.example

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.chat.ChatScreen
import com.example.ui.home.HomeScreen
import com.example.ui.match.MatchScreen
import com.example.ui.navigation.Screen
import com.example.ui.profile.ProfileScreen
import com.example.ui.searching.SearchingScreen
import com.example.ui.settings.BlockedUsersScreen
import com.example.ui.settings.GuidelinesScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.RandomConnectTheme
import com.example.ui.viewmodel.RandomConnectViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RandomConnectTheme {
                AppGate()
            }
        }
    }
}

@Composable
fun AppGate() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    if (currentUser == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
            }
        )
    } else {
        AppContent(currentUserId = currentUser!!.uid)
    }
}

@Composable
fun AppContent(currentUserId: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: RandomConnectViewModel = viewModel(
        factory = RandomConnectViewModel.provideFactory(context)
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Handle user sync when authenticated
    LaunchedEffect(currentUserId) {
        viewModel.onUserAuthenticated(currentUserId)
    }

    // Display transient errors or status in snackbar
    LaunchedEffect(uiState.errorMessage) {
        val error = uiState.errorMessage
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    is Screen.Home -> {
                        HomeScreen(
                            user = uiState.currentUser,
                            onFindRandomPerson = {
                                currentScreen = Screen.Searching
                                viewModel.startSearching(
                                    onMatchFound = { matchId ->
                                        currentScreen = Screen.Match(matchId)
                                    }
                                )
                            },
                            onNavigateToProfile = { currentScreen = Screen.Profile },
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                            onNavigateToGuidelines = { currentScreen = Screen.Guidelines }
                        )
                    }

                    is Screen.Searching -> {
                        BackHandler {
                            viewModel.cancelSearching()
                            currentScreen = Screen.Home
                        }
                        SearchingScreen(
                            onCancel = {
                                viewModel.cancelSearching()
                                currentScreen = Screen.Home
                            }
                        )
                    }

                    is Screen.Match -> {
                        BackHandler {
                            currentScreen = Screen.Home
                        }
                        MatchScreen(
                            currentUserId = currentUserId,
                            match = uiState.currentMatch,
                            onStartChat = {
                                currentScreen = Screen.Chat(screen.matchId)
                            },
                            onNextPerson = {
                                currentScreen = Screen.Searching
                                viewModel.skipOrNextPerson(
                                    onSearchAgain = {
                                        viewModel.startSearching(
                                            onMatchFound = { nextMatchId ->
                                                currentScreen = Screen.Match(nextMatchId)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }

                    is Screen.Chat -> {
                        BackHandler {
                            currentScreen = Screen.Home
                        }
                        ChatScreen(
                            currentUserId = currentUserId,
                            match = uiState.currentMatch,
                            messages = uiState.messages,
                            onSendMessage = { text -> viewModel.sendMessage(text) },
                            onSetTyping = { isTyping -> viewModel.setTyping(isTyping) },
                            onSkipNext = {
                                currentScreen = Screen.Searching
                                viewModel.skipOrNextPerson(
                                    onSearchAgain = {
                                        viewModel.startSearching(
                                            onMatchFound = { nextMatchId ->
                                                currentScreen = Screen.Match(nextMatchId)
                                            }
                                        )
                                    }
                                )
                            },
                            onEndChat = {
                                viewModel.endChat {
                                    currentScreen = Screen.Home
                                }
                            },
                            onReportUser = { reason, details ->
                                val reportedUid = uiState.currentMatch?.getOtherUserId(currentUserId)
                                if (reportedUid != null) {
                                    viewModel.reportUser(reportedUid, reason, details) {
                                        // End chat after reporting
                                        viewModel.endChat {
                                            currentScreen = Screen.Home
                                        }
                                    }
                                }
                            },
                            onBlockUser = {
                                val blockedUid = uiState.currentMatch?.getOtherUserId(currentUserId)
                                val blockedName = uiState.currentMatch?.getOtherUsername(currentUserId) ?: "User"
                                if (blockedUid != null) {
                                    viewModel.blockUser(blockedUid, blockedName) {
                                        currentScreen = Screen.Home
                                    }
                                }
                            }
                        )
                    }

                    is Screen.Profile -> {
                        BackHandler { currentScreen = Screen.Home }
                        ProfileScreen(
                            user = uiState.currentUser,
                            isLoading = uiState.isLoading,
                            onBack = { currentScreen = Screen.Home },
                            onSaveProfile = { username, age, country, language, photoUrl ->
                                viewModel.updateProfile(username, age, country, language, photoUrl) {
                                    currentScreen = Screen.Home
                                }
                            }
                        )
                    }

                    is Screen.Settings -> {
                        BackHandler { currentScreen = Screen.Home }
                        SettingsScreen(
                            blockedCount = uiState.blockedUsers.size,
                            onBack = { currentScreen = Screen.Home },
                            onNavigateToBlockedUsers = { currentScreen = Screen.BlockedUsers },
                            onNavigateToGuidelines = { currentScreen = Screen.Guidelines },
                            onNavigateToTerms = { currentScreen = Screen.Terms },
                            onSignOut = {
                                performSignOut(context, credentialManager, coroutineScope)
                            }
                        )
                    }

                    is Screen.BlockedUsers -> {
                        BackHandler { currentScreen = Screen.Settings }
                        BlockedUsersScreen(
                            blockedUsers = uiState.blockedUsers,
                            onUnblock = { blockedId -> viewModel.unblockUser(blockedId) },
                            onBack = { currentScreen = Screen.Settings }
                        )
                    }

                    is Screen.Guidelines -> {
                        BackHandler { currentScreen = Screen.Settings }
                        GuidelinesScreen(
                            isTerms = false,
                            onBack = { currentScreen = Screen.Home }
                        )
                    }

                    is Screen.Terms -> {
                        BackHandler { currentScreen = Screen.Settings }
                        GuidelinesScreen(
                            isTerms = true,
                            onBack = { currentScreen = Screen.Settings }
                        )
                    }
                }
            }
        }
    }
}

private fun performSignOut(
    context: Context,
    credentialManager: CredentialManager,
    scope: CoroutineScope
) {
    Firebase.auth.signOut()
    scope.launch {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.e("Auth", "Failed to clear credential state", e)
        }
    }
}
