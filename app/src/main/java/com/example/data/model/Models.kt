package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class User(
    @DocumentId val id: String = "",
    val username: String = "",
    val photoUrl: String = "",
    val age: Long = 18L,
    val country: String = "United States",
    val language: String = "English",
    val status: String = "online", // online, offline, searching, matched
    val currentMatchId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    @ServerTimestamp val lastActive: Timestamp? = null
)

@IgnoreExtraProperties
data class QueueEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val username: String = "",
    val country: String = "",
    val language: String = "",
    val photoUrl: String = "",
    val status: String = "waiting", // waiting, matched, cancelled
    val matchedWith: String = "",
    val matchId: String = "",
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Match(
    @DocumentId val id: String = "",
    val user1Id: String = "",
    val user2Id: String = "",
    val user1Username: String = "",
    val user2Username: String = "",
    val user1Country: String = "",
    val user2Country: String = "",
    val user1Language: String = "",
    val user2Language: String = "",
    val user1PhotoUrl: String = "",
    val user2PhotoUrl: String = "",
    val memberUids: List<String> = emptyList(),
    val status: String = "active", // active, ended, skipped
    val endedBy: String = "",
    val lastMessage: String = "",
    val user1Typing: Boolean = false,
    val user2Typing: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun getOtherUserId(currentUserId: String): String =
        if (currentUserId == user1Id) user2Id else user1Id

    fun getOtherUsername(currentUserId: String): String =
        if (currentUserId == user1Id) user2Username else user1Username

    fun getOtherCountry(currentUserId: String): String =
        if (currentUserId == user1Id) user2Country else user1Country

    fun getOtherLanguage(currentUserId: String): String =
        if (currentUserId == user1Id) user2Language else user1Language

    fun getOtherPhotoUrl(currentUserId: String): String =
        if (currentUserId == user1Id) user2PhotoUrl else user1PhotoUrl

    fun isOtherTyping(currentUserId: String): Boolean =
        if (currentUserId == user1Id) user2Typing else user1Typing
}

@IgnoreExtraProperties
data class Message(
    @DocumentId val id: String = "",
    val matchId: String = "",
    val senderId: String = "",
    val text: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Report(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val reportedUserId: String = "",
    val reason: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BlockedUser(
    @DocumentId val id: String = "",
    val userId: String = "",
    val blockedUsername: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
