package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.error.OperationType
import com.example.data.error.handleFirestoreError
import com.example.data.model.BlockedUser
import com.example.data.model.Match
import com.example.data.model.Message
import com.example.data.model.QueueEntry
import com.example.data.model.Report
import com.example.data.model.User
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

sealed interface MatchSearchState {
    data object Idle : MatchSearchState
    data object Searching : MatchSearchState
    data class Found(val matchId: String) : MatchSearchState
    data class Error(val message: String) : MatchSearchState
}

class RandomConnectRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    // --- User Profile Operations ---

    fun observeUserProfile(userId: String): Flow<User?> = flow {
        val path = "users/$userId"
        val docRef = db.collection("users").document(userId)
        val snapshotFlow = docRef.snapshots().map { snapshot ->
            if (snapshot.exists()) snapshot.toObject(User::class.java) else null
        }.catch { error ->
            if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
            throw error
        }
        snapshotFlow.collect { emit(it) }
    }

    suspend fun getUserProfile(userId: String): User? {
        val path = "users/$userId"
        val docRef = db.collection("users").document(userId)
        return try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) snapshot.toObject(User::class.java) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            handleFirestoreError(e, OperationType.GET, path)
            throw e
        }
    }

    suspend fun saveUserProfile(user: User): Result<Unit> {
        return try {
            val uid = requireUserId()
            val path = "users/$uid"
            val docRef = db.collection("users").document(uid)
            val payload = hashMapOf<String, Any>(
                "username" to user.username.ifBlank { "User_${uid.take(5)}" },
                "photoUrl" to user.photoUrl,
                "age" to user.age.coerceIn(18L, 120L),
                "country" to user.country,
                "language" to user.language,
                "status" to user.status,
                "currentMatchId" to user.currentMatchId,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastActive" to FieldValue.serverTimestamp()
            )
            if (user.createdAt == null) {
                payload["createdAt"] = FieldValue.serverTimestamp()
            }
            docRef.set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/${auth.currentUser?.uid}")
            Result.failure(e)
        }
    }

    suspend fun updatePresence(status: String): Result<Unit> {
        val uid = try { requireUserId() } catch (_: Exception) { return Result.success(Unit) }
        val path = "users/$uid"
        val docRef = db.collection("users").document(uid)
        return try {
            docRef.update(
                mapOf(
                    "status" to status,
                    "lastActive" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Profile may not exist yet during cold start
            Result.failure(e)
        }
    }

    // --- Matchmaking & Queue Operations ---

    fun searchForRandomPartner(currentUser: User): Flow<MatchSearchState> = callbackFlow {
        val uid = requireUserId()
        trySend(MatchSearchState.Searching)

        val queueCollection = db.collection("matchingQueue")
        val myQueueDoc = queueCollection.document(uid)

        // 1. Get blocked users list to avoid matching with blocked users
        val blockedIds = mutableSetOf<String>()
        try {
            val blockedSnap = db.collection("users").document(uid).collection("blockedUsers").get().await()
            for (doc in blockedSnap.documents) {
                blockedIds.add(doc.id)
            }
        } catch (_: Exception) {}

        // 2. Look for another waiting user
        var paired = false
        try {
            val waitingSnapshot = queueCollection
                .whereEqualTo("status", "waiting")
                .limit(10)
                .get()
                .await()

            for (doc in waitingSnapshot.documents) {
                val candidateId = doc.getString("userId") ?: doc.id
                if (candidateId != uid && !blockedIds.contains(candidateId)) {
                    // We found an available online peer!
                    val candidateName = doc.getString("username") ?: "Explorer"
                    val candidateCountry = doc.getString("country") ?: "Global"
                    val candidateLanguage = doc.getString("language") ?: "English"
                    val candidatePhoto = doc.getString("photoUrl") ?: ""

                    val matchId = "match_${System.currentTimeMillis()}_${uid.take(4)}"
                    val matchDoc = db.collection("matches").document(matchId)

                    val matchPayload = hashMapOf<String, Any>(
                        "user1Id" to candidateId,
                        "user2Id" to uid,
                        "user1Username" to candidateName,
                        "user2Username" to currentUser.username,
                        "user1Country" to candidateCountry,
                        "user2Country" to currentUser.country,
                        "user1Language" to candidateLanguage,
                        "user2Language" to currentUser.language,
                        "user1PhotoUrl" to candidatePhoto,
                        "user2PhotoUrl" to currentUser.photoUrl,
                        "memberUids" to listOf(candidateId, uid),
                        "status" to "active",
                        "endedBy" to "",
                        "lastMessage" to "",
                        "user1Typing" to false,
                        "user2Typing" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )

                    // Write match room first
                    matchDoc.set(matchPayload).await()

                    // Update candidate's queue entry so their listener sees the match
                    doc.reference.update(
                        mapOf(
                            "status" to "matched",
                            "matchedWith" to uid,
                            "matchId" to matchId,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()

                    // Update our own queue entry
                    myQueueDoc.set(
                        mapOf(
                            "userId" to uid,
                            "username" to currentUser.username,
                            "country" to currentUser.country,
                            "language" to currentUser.language,
                            "photoUrl" to currentUser.photoUrl,
                            "status" to "matched",
                            "matchedWith" to candidateId,
                            "matchId" to matchId,
                            "joinedAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()

                    // Update user profile
                    db.collection("users").document(uid).update(
                        mapOf(
                            "status" to "matched",
                            "currentMatchId" to matchId,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )

                    paired = true
                    trySend(MatchSearchState.Found(matchId))
                    break
                }
            }
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, queueCollection.path)
        }

        if (paired) {
            awaitClose { }
            return@callbackFlow
        }

        // 3. If no candidate was available, register in queue and listen for an incoming peer
        try {
            val myEntry = hashMapOf<String, Any>(
                "userId" to uid,
                "username" to currentUser.username,
                "country" to currentUser.country,
                "language" to currentUser.language,
                "photoUrl" to currentUser.photoUrl,
                "status" to "waiting",
                "matchedWith" to "",
                "matchId" to "",
                "joinedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            myQueueDoc.set(myEntry).await()

            db.collection("users").document(uid).update(
                mapOf("status" to "searching", "updatedAt" to FieldValue.serverTimestamp())
            )
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, myQueueDoc.path)
            trySend(MatchSearchState.Error(e.localizedMessage ?: "Failed to enter matching queue"))
            close(e)
            return@callbackFlow
        }

        // Listen to own queue doc
        val registration = myQueueDoc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.GET, myQueueDoc.path)
                trySend(MatchSearchState.Error(error.localizedMessage ?: "Queue connection lost"))
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val status = snapshot.getString("status")
                val matchId = snapshot.getString("matchId")
                if (status == "matched" && !matchId.isNullOrBlank()) {
                    db.collection("users").document(uid).update(
                        mapOf(
                            "status" to "matched",
                            "currentMatchId" to matchId,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    trySend(MatchSearchState.Found(matchId))
                }
            }
        }

        awaitClose {
            registration.remove()
        }
    }

    suspend fun cancelSearching(): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val docRef = db.collection("matchingQueue").document(uid)
        return try {
            docRef.delete().await()
            db.collection("users").document(uid).update(
                mapOf(
                    "status" to "online",
                    "currentMatchId" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    // --- Matches & Messaging Operations ---

    fun observeMatch(matchId: String): Flow<Match?> = flow {
        val path = "matches/$matchId"
        val docRef = db.collection("matches").document(matchId)
        val snapshotFlow = docRef.snapshots().map { snapshot ->
            if (snapshot.exists()) snapshot.toObject(Match::class.java) else null
        }.catch { error ->
            if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
            throw error
        }
        snapshotFlow.collect { emit(it) }
    }

    fun observeMessages(matchId: String): Flow<List<Message>> = flow {
        val path = "matches/$matchId/messages"
        val collectionRef = db.collection("matches").document(matchId).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)

        val snapshotFlow = collectionRef.snapshots().map { snapshot ->
            snapshot.toObjects(Message::class.java)
        }.catch { error ->
            if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
            throw error
        }
        snapshotFlow.collect { emit(it) }
    }

    suspend fun sendMessage(match: Match, text: String): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        return try {
            val uid = requireUserId()
            val matchId = match.id
            val messagesRef = db.collection("matches").document(matchId).collection("messages")
            val matchRef = db.collection("matches").document(matchId)
            val payload = hashMapOf<String, Any>(
                "matchId" to matchId,
                "senderId" to uid,
                "text" to text.trim(),
                "memberUids" to match.memberUids,
                "createdAt" to FieldValue.serverTimestamp()
            )
            messagesRef.add(payload).await()

            // Update parent match with last message snippet
            val isUser1 = uid == match.user1Id
            val updatePayload = hashMapOf<String, Any>(
                "lastMessage" to text.trim().take(100),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isUser1) {
                updatePayload["user1Typing"] = false
            } else {
                updatePayload["user2Typing"] = false
            }
            matchRef.update(updatePayload)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "matches/${match.id}/messages")
            Result.failure(e)
        }
    }

    suspend fun setTyping(match: Match, isTyping: Boolean) {
        val uid = try { requireUserId() } catch (_: Exception) { return }
        val isUser1 = uid == match.user1Id
        val matchRef = db.collection("matches").document(match.id)
        try {
            val field = if (isUser1) "user1Typing" else "user2Typing"
            matchRef.update(field, isTyping)
        } catch (_: Exception) {}
    }

    suspend fun endOrSkipMatch(matchId: String, status: String = "skipped"): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val matchRef = db.collection("matches").document(matchId)
        return try {
            matchRef.update(
                mapOf(
                    "status" to status,
                    "endedBy" to uid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("users").document(uid).update(
                mapOf(
                    "status" to "online",
                    "currentMatchId" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            // Clean own queue doc
            db.collection("matchingQueue").document(uid).delete()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, matchRef.path)
            Result.failure(e)
        }
    }

    // --- Safety & Moderation Operations ---

    suspend fun reportUser(reportedUserId: String, reason: String, details: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val reportsRef = db.collection("reports")
            val payload = hashMapOf<String, Any>(
                "reporterId" to uid,
                "reportedUserId" to reportedUserId,
                "reason" to reason,
                "details" to details,
                "createdAt" to FieldValue.serverTimestamp()
            )
            reportsRef.add(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "reports")
            Result.failure(e)
        }
    }

    suspend fun blockUser(blockedUserId: String, blockedUsername: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("blockedUsers").document(blockedUserId)
            val payload = hashMapOf<String, Any>(
                "userId" to uid,
                "blockedUsername" to blockedUsername,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unblockUser(blockedUserId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("blockedUsers").document(blockedUserId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeBlockedUsers(): Flow<List<BlockedUser>> = flow {
        val uid = requireUserId()
        val collectionRef = db.collection("users").document(uid).collection("blockedUsers")
        val snapshotFlow = collectionRef.snapshots().map { snapshot ->
            snapshot.toObjects(BlockedUser::class.java)
        }.catch { error ->
            if (error is Exception) handleFirestoreError(error, OperationType.LIST, collectionRef.path)
            throw error
        }
        snapshotFlow.collect { emit(it) }
    }
}
