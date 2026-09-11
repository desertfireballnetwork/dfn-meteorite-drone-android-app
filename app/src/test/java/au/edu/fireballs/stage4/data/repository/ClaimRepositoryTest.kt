package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.remote.Stage4Service
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ClaimRepositoryTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var repository: ClaimRepository
    private lateinit var claimDao: FakeClaimDao
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val moshi =
            Moshi
                .Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(mockWebServer.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        val service = retrofit.create(Stage4Service::class.java)
        claimDao = FakeClaimDao()
        repository =
            ClaimRepository(
                stage4Service = service,
                claimDao = claimDao,
                ioDispatcher = testDispatcher,
            )
        repository.setSurveyId(7L)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `claim maps claimed and alreadyClaimed from response`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"claimed": [1, 2], "already_claimed": [3]}"""),
            )

            val result = repository.claim(listOf(1L, 2L, 3L))

            assertTrue("Expected Claimed but got $result", result is ClaimResult.Claimed)
            val claimed = result as ClaimResult.Claimed
            assertEquals(listOf(1L, 2L), claimed.claimed)
            assertEquals(listOf(3L), claimed.alreadyClaimed)
        }

    @Test
    fun `claim sends batch body with canonical survey id`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"claimed": [1], "already_claimed": []}"""),
            )

            repository.claim(listOf(1L, 2L))

            val request = mockWebServer.takeRequest()
            assertEquals("/api/stage4/surveys/7/claims/claim/", request.path)
            assertEquals(
                """{"inference_result_ids":[1,2]}""",
                request.body.readUtf8(),
            )
        }

    @Test
    fun `claim maps HTTP 401 to AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            assertEquals(ClaimResult.AuthExpired, repository.claim(listOf(1L)))
        }

    @Test
    fun `claim maps HTTP 500 to Error`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))

            val result = repository.claim(listOf(1L))

            assertTrue("Expected Error but got $result", result is ClaimResult.Error)
        }

    @Test
    fun `claim maps connection failure to NetworkError`() =
        runTest(testDispatcher) {
            val deadServer = MockWebServer()
            val deadUrl = deadServer.url("/")
            deadServer.shutdown()

            val moshi =
                Moshi
                    .Builder()
                    .addLast(KotlinJsonAdapterFactory())
                    .build()
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(deadUrl)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
            val deadRepository =
                ClaimRepository(
                    stage4Service = retrofit.create(Stage4Service::class.java),
                    claimDao = FakeClaimDao(),
                    ioDispatcher = testDispatcher,
                )
            deadRepository.setSurveyId(7L)

            assertEquals(ClaimResult.NetworkError, deadRepository.claim(listOf(1L)))
        }

    @Test
    fun `claim returns Error when no survey selected`() =
        runTest(testDispatcher) {
            val unselected =
                ClaimRepository(
                    stage4Service = retrofitService(),
                    claimDao = FakeClaimDao(),
                    ioDispatcher = testDispatcher,
                )

            val result = unselected.claim(listOf(1L))

            assertTrue("Expected Error but got $result", result is ClaimResult.Error)
        }

    @Test
    fun `claim retry after ambiguous network failure is safe`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            )
            assertEquals(ClaimResult.NetworkError, repository.claim(listOf(1L)))

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"claimed": [1], "already_claimed": []}"""),
            )
            val retry = repository.claim(listOf(1L))
            assertTrue("Expected Claimed but got $retry", retry is ClaimResult.Claimed)
        }

    @Test
    fun `release maps released from response`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"released": [1, 2]}"""),
            )

            val result = repository.release(listOf(1L, 2L))

            assertTrue("Expected Released but got $result", result is ClaimResult.Released)
            assertEquals(listOf(1L, 2L), (result as ClaimResult.Released).released)
        }

    @Test
    fun `release sends batch body with canonical survey id`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"released": [1]}"""),
            )

            repository.release(listOf(1L))

            val request = mockWebServer.takeRequest()
            assertEquals("/api/stage4/surveys/7/claims/release/", request.path)
            assertEquals(
                """{"inference_result_ids":[1]}""",
                request.body.readUtf8(),
            )
        }

    @Test
    fun `release maps HTTP 401 to AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            assertEquals(ClaimResult.AuthExpired, repository.release(listOf(1L)))
        }

    @Test
    fun `listClaims maps claims to domain model`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "claims": [
                            {
                              "inference_result_id": 1,
                              "user_id": 2,
                              "username": "jdoe",
                              "full_name": "Jane Doe",
                              "claimed_at": "2026-01-01T00:00:00Z",
                              "is_me": true
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            val result = repository.listClaims()

            assertTrue("Expected Listed but got $result", result is ClaimResult.Listed)
            val claim = (result as ClaimResult.Listed).claims.single()
            assertEquals(1L, claim.inferenceResultId)
            assertEquals(2L, claim.userId)
            assertEquals("jdoe", claim.username)
            assertEquals("Jane Doe", claim.fullName)
            assertEquals("2026-01-01T00:00:00Z", claim.claimedAt)
            assertTrue(claim.isMe)
        }

    @Test
    fun `listClaims passes mine query and uses canonical survey id`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"claims": []}"""),
            )

            repository.listClaims(mine = true)

            val request = mockWebServer.takeRequest()
            assertEquals("/api/stage4/surveys/7/claims/", request.requestUrl?.encodedPath)
            assertEquals("mine=true", request.requestUrl?.query)
        }

    @Test
    fun `listClaims maps HTTP 401 to AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            assertEquals(ClaimResult.AuthExpired, repository.listClaims())
        }

    @Test
    fun `refreshClaimsToRoom persists mapped claim entities`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "claims": [
                            {
                              "inference_result_id": 1,
                              "user_id": 2,
                              "username": "jdoe",
                              "claimed_at": "2026-01-01T00:00:00Z",
                              "is_me": true
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            val result = repository.refreshClaimsToRoom(7L)

            assertTrue("Expected Refreshed but got $result", result is ClaimResult.Refreshed)
            assertEquals(1, (result as ClaimResult.Refreshed).count)
            val entity = claimDao.upserted.single()
            assertEquals(1L, entity.inferenceResultId)
            assertEquals(7L, entity.surveyId)
            assertEquals(2L, entity.userId)
            assertEquals("jdoe", entity.username)
            assertEquals("2026-01-01T00:00:00Z", entity.claimedAt)
            assertTrue(entity.isMine)
            assertTrue(entity.isActive)
        }

    @Test
    fun `refreshClaimsToRoom sends mine query with canonical survey id`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"claims": []}"""),
            )

            repository.refreshClaimsToRoom(7L)

            val request = mockWebServer.takeRequest()
            assertEquals("/api/stage4/surveys/7/claims/", request.requestUrl?.encodedPath)
            assertEquals("mine=true", request.requestUrl?.query)
        }

    @Test
    fun `refreshClaimsToRoom maps HTTP 401 to AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            assertEquals(ClaimResult.AuthExpired, repository.refreshClaimsToRoom(7L))
        }

    @Test
    fun `refreshClaimsToRoom maps connection failure to NetworkError`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            )

            assertEquals(ClaimResult.NetworkError, repository.refreshClaimsToRoom(7L))
        }

    private fun retrofitService(): Stage4Service {
        val moshi =
            Moshi
                .Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(mockWebServer.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
        return retrofit.create(Stage4Service::class.java)
    }
}

private class FakeClaimDao : ClaimDao {
    val upserted = mutableListOf<ClaimEntity>()

    override suspend fun upsert(claim: ClaimEntity) {
        upserted.add(claim)
    }

    override suspend fun upsertAll(claims: List<ClaimEntity>) {
        upserted.addAll(claims)
    }

    override fun getClaims(
        surveyId: Long,
        onlyActive: Boolean,
    ): Flow<List<ClaimEntity>> = flowOf(emptyList())

    override suspend fun getByCandidateId(inferenceResultId: Long): ClaimEntity? = null

    override suspend fun releaseClaimsForUser(
        userId: Long,
        candidateIds: List<Long>,
    ) = Unit

    override suspend fun deleteForSurvey(surveyId: Long) {
        upserted.removeAll { it.surveyId == surveyId }
    }

    override suspend fun deleteAll() {
        upserted.clear()
    }
}
