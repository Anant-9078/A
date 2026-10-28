package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.Match
import com.example.data.model.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomConnectRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun saveUserProfile_authenticatedUser_succeeds() = runBlocking {
        val uid = signInTestUser("alice_${System.currentTimeMillis()}@test.com")
        val repo = RandomConnectRepository(firestore)

        val user = User(
            id = uid,
            username = "AliceRules",
            age = 22,
            country = "Canada",
            language = "English",
            status = "online"
        )

        val result = withTimeout(10000L) {
            repo.saveUserProfile(user)
        }
        if (result.isFailure) {
            result.exceptionOrNull()?.printStackTrace()
        }
        assertTrue("Profile save should succeed: ${result.exceptionOrNull()}", result.isSuccess)

        val retrieved = withTimeout(10000L) {
            repo.getUserProfile(uid)
        }
        assertNotNull(retrieved)
        assertEquals("AliceRules", retrieved?.username)
        assertEquals("Canada", retrieved?.country)
    }

    @Test
    fun unauthenticated_access_throwsException() = runBlocking {
        auth.signOut()
        val repo = RandomConnectRepository(firestore)

        val user = User(
            id = "unauth",
            username = "Intruder"
        )

        val result = repo.saveUserProfile(user)
        assertTrue("Unauthenticated save must fail", result.isFailure)
    }

    @Test
    fun reportUser_authenticated_succeeds() = runBlocking {
        signInTestUser("reporter@test.com")
        val repo = RandomConnectRepository(firestore)

        val result = withTimeout(10000L) {
            repo.reportUser("bad_actor_uid", "Harassment", "Sent bad texts")
        }
        assertTrue("Report must succeed", result.isSuccess)
    }

    @Test
    fun blockAndObserveBlockedUsers_succeeds() = runBlocking {
        signInTestUser("blocker@test.com")
        val repo = RandomConnectRepository(firestore)

        val blockResult = withTimeout(10000L) {
            repo.blockUser("blocked_uid_99", "TrollAccount")
        }
        assertTrue("Block must succeed", blockResult.isSuccess)

        val list = withTimeout(10000L) {
            repo.observeBlockedUsers().first()
        }
        assertTrue("Blocked list should contain TrollAccount", list.any { it.id == "blocked_uid_99" })
    }

    @Test
    fun sendMessage_asMatchMember_succeeds() = runBlocking {
        val aliceUid = signInTestUser("match_alice@test.com")
        val bobUid = "mock_bob_uid"
        val matchId = "match_test_${System.currentTimeMillis()}"

        // Seed match doc directly
        firestore.collection("matches").document(matchId).set(
            mapOf(
                "user1Id" to aliceUid,
                "user2Id" to bobUid,
                "memberUids" to listOf(aliceUid, bobUid),
                "status" to "active"
            )
        ).await()

        val repo = RandomConnectRepository(firestore)
        val match = Match(
            id = matchId,
            user1Id = aliceUid,
            user2Id = bobUid,
            memberUids = listOf(aliceUid, bobUid),
            status = "active"
        )

        val sendResult = withTimeout(10000L) {
            repo.sendMessage(match, "Hey Bob!")
        }
        assertTrue("Sending message as match member must succeed", sendResult.isSuccess)

        val messages = withTimeout(10000L) {
            repo.observeMessages(matchId).first()
        }
        assertTrue("Messages must have our message", messages.any { it.text == "Hey Bob!" })
    }
}
