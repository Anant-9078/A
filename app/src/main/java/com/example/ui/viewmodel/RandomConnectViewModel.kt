package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.BlockedUser
import com.example.data.model.Match
import com.example.data.model.Message
import com.example.data.model.User
import com.example.data.repository.MatchSearchState
import com.example.data.repository.RandomConnectRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val currentUser: User? = null,
    val currentMatch: Match? = null,
    val messages: List<Message> = emptyList(),
    val searchState: MatchSearchState = MatchSearchState.Idle,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val isSearching: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

class RandomConnectViewModel(
    private val repository: RandomConnectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var profileJob: Job? = null
    private var searchJob: Job? = null
    private var matchJob: Job? = null
    private var messagesJob: Job? = null
    private var blockedJob: Job? = null

    init {
        val uid = Firebase.auth.currentUser?.uid
        if (uid != null) {
            loadUserProfile(uid)
            observeBlockedUsers()
        }
    }

    fun onUserAuthenticated(userId: String) {
        loadUserProfile(userId)
        observeBlockedUsers()
    }

    private fun loadUserProfile(userId: String) {
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            repository.observeUserProfile(userId)
                .catch { e ->
                    _uiState.update { it.copy(errorMessage = "Failed to load profile: ${e.message}") }
                }
                .collect { user ->
                    if (user == null) {
                        // First-time user: initialize default profile
                        val authUser = Firebase.auth.currentUser
                        val initialName = authUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: "Explorer_${userId.take(4)}"
                        val initialUser = User(
                            id = userId,
                            username = initialName,
                            photoUrl = authUser?.photoUrl?.toString() ?: "",
                            age = 21,
                            country = "United States",
                            language = "English",
                            status = "online"
                        )
                        repository.saveUserProfile(initialUser)
                    } else {
                        _uiState.update { it.copy(currentUser = user) }
                    }
                }
        }
    }

    private fun observeBlockedUsers() {
        blockedJob?.cancel()
        blockedJob = viewModelScope.launch {
            repository.observeBlockedUsers()
                .catch { }
                .collect { list ->
                    _uiState.update { it.copy(blockedUsers = list) }
                }
        }
    }

    fun startSearching(onMatchFound: (String) -> Unit) {
        val user = _uiState.value.currentUser ?: return
        searchJob?.cancel()
        _uiState.update { it.copy(isSearching = true, searchState = MatchSearchState.Searching) }

        searchJob = viewModelScope.launch {
            repository.searchForRandomPartner(user)
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchState = MatchSearchState.Error(e.message ?: "Search failed")
                        )
                    }
                }
                .collect { state ->
                    _uiState.update { it.copy(searchState = state) }
                    if (state is MatchSearchState.Found) {
                        _uiState.update { it.copy(isSearching = false) }
                        observeMatch(state.matchId)
                        onMatchFound(state.matchId)
                    }
                }
        }
    }

    fun cancelSearching() {
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false, searchState = MatchSearchState.Idle) }
        viewModelScope.launch {
            repository.cancelSearching()
        }
    }

    fun observeMatch(matchId: String) {
        matchJob?.cancel()
        messagesJob?.cancel()

        matchJob = viewModelScope.launch {
            repository.observeMatch(matchId)
                .catch { e ->
                    _uiState.update { it.copy(errorMessage = "Failed to load match: ${e.message}") }
                }
                .collect { match ->
                    _uiState.update { it.copy(currentMatch = match) }
                }
        }

        messagesJob = viewModelScope.launch {
            repository.observeMessages(matchId)
                .catch { e ->
                    _uiState.update { it.copy(errorMessage = "Failed to load messages: ${e.message}") }
                }
                .collect { msgs ->
                    _uiState.update { it.copy(messages = msgs) }
                }
        }
    }

    fun sendMessage(text: String) {
        val match = _uiState.value.currentMatch ?: return
        viewModelScope.launch {
            val result = repository.sendMessage(match, text)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = "Could not send message") }
            }
        }
    }

    fun setTyping(isTyping: Boolean) {
        val match = _uiState.value.currentMatch ?: return
        viewModelScope.launch {
            repository.setTyping(match, isTyping)
        }
    }

    fun skipOrNextPerson(onSearchAgain: () -> Unit) {
        val match = _uiState.value.currentMatch
        if (match != null) {
            viewModelScope.launch {
                repository.endOrSkipMatch(match.id, "skipped")
            }
        }
        _uiState.update { it.copy(currentMatch = null, messages = emptyList()) }
        onSearchAgain()
    }

    fun endChat(onEnded: () -> Unit) {
        val match = _uiState.value.currentMatch
        if (match != null) {
            viewModelScope.launch {
                repository.endOrSkipMatch(match.id, "ended")
            }
        }
        _uiState.update { it.copy(currentMatch = null, messages = emptyList()) }
        onEnded()
    }

    fun reportUser(reportedUserId: String, reason: String, details: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.reportUser(reportedUserId, reason, details)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _uiState.update { it.copy(statusMessage = "Report submitted. Thank you for keeping RandomConnect safe.") }
                onSuccess()
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to submit report") }
            }
        }
    }

    fun blockUser(blockedUserId: String, blockedUsername: String, onBlocked: () -> Unit) {
        val match = _uiState.value.currentMatch
        viewModelScope.launch {
            repository.blockUser(blockedUserId, blockedUsername)
            if (match != null && match.memberUids.contains(blockedUserId)) {
                repository.endOrSkipMatch(match.id, "ended")
            }
            _uiState.update { it.copy(currentMatch = null, messages = emptyList(), statusMessage = "User blocked") }
            onBlocked()
        }
    }

    fun unblockUser(blockedUserId: String) {
        viewModelScope.launch {
            repository.unblockUser(blockedUserId)
        }
    }

    fun updateProfile(
        username: String,
        age: Long,
        country: String,
        language: String,
        photoUrl: String,
        onSuccess: () -> Unit
    ) {
        val current = _uiState.value.currentUser ?: return
        val updated = current.copy(
            username = username.trim(),
            age = age,
            country = country.trim(),
            language = language.trim(),
            photoUrl = photoUrl
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.saveUserProfile(updated)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _uiState.update { it.copy(currentUser = updated, statusMessage = "Profile updated!") }
                onSuccess()
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to save profile") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repo = RandomConnectRepository(context.applicationContext)
                    return RandomConnectViewModel(repo) as T
                }
            }
    }
}
