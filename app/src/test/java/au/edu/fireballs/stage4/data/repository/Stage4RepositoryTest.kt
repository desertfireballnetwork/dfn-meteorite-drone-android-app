package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.SurveyEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class Stage4RepositoryTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var repository: Stage4Repository
    private lateinit var moshi: Moshi
    private val surveyDao: SurveyDao = mock()
    private val candidateDao: CandidateDao = mock()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        moshi =
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
        repository =
            Stage4Repository(
                stage4Service = service,
                moshi = moshi,
                surveyDao = surveyDao,
                candidateDao = candidateDao,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `getCandidatesState parses full payload with nullables correctly`() =
        runTest(testDispatcher) {
            val jsonPayload =
                """
                {
                  "survey": {"id": 7, "event_id": "DN240703-02", "tileset_id": null},
                  "base": null,
                  "surveyed_areas": [[[137.1, -32.5], [137.2, -32.6]]],
                  "unprocessed_candidates": [],
                  "yes_meteorites": [
                    {
                      "inference_result_id": 101,
                      "image_id": 55,
                      "image_filename": "frame_001.png",
                      "image_dims": {"w": 1920, "h": 1080},
                      "geo_centroid": null,
                      "geo_area": null,
                      "box": {"x": 10, "y": 20, "w": 300, "h": 400},
                      "confidence": 0.87,
                      "size_m": null,
                      "claimed_by_me": true
                    }
                  ],
                  "no_meteorites": [],
                  "detection_tags": [{"id": 3, "name": "chondrule", "category": "texture"}],
                  "user_locations": [
                    {
                      "username": "jdoe",
                      "full_name": "Jane Doe",
                      "user_id": 9,
                      "lat": -31.9,
                      "lon": 115.8,
                      "processed": "2026-08-01T00:00:00Z"
                    }
                  ],
                  "settings": {"show_geolocation_accuracy_circle": false},
                  "latest_task_created": "2026-08-20T09:30:00Z"
                }
                """.trimIndent()

            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonPayload))

            val result = repository.getCandidatesState(7L)

            assertTrue("Expected Success but got $result", result is Stage4FetchResult.Success)

            val success = result as Stage4FetchResult.Success
            val state = success.state
            assertTrue("Expected online success", !success.isOffline)
            assertEquals(7L, state.survey.id)
            assertEquals("DN240703-02", state.survey.eventId)
            assertNull(state.survey.tilesetId)
            assertNull(state.base)
            val expectedAreas = listOf(listOf(listOf(137.1, -32.5), listOf(137.2, -32.6)))
            assertEquals(expectedAreas, state.surveyedAreas)
            assertTrue(state.unprocessedCandidates.isEmpty())
            assertTrue(state.noMeteorites.isEmpty())

            val candidate = state.yesMeteorites.single()
            assertEquals(101L, candidate.inferenceResultId)
            assertEquals(55L, candidate.imageId)
            assertEquals("frame_001.png", candidate.imageFilename)
            assertEquals(1920, candidate.imageDims.w)
            assertEquals(1080, candidate.imageDims.h)
            assertNull(candidate.geoCentroid)
            assertNull(candidate.geoArea)
            assertEquals(10, candidate.box.x)
            assertEquals(20, candidate.box.y)
            assertEquals(300, candidate.box.w)
            assertEquals(400, candidate.box.h)
            assertEquals(0.87, candidate.confidence, 0.0)
            assertNull(candidate.sizeM)
            assertTrue(candidate.claimedByMe)

            val tag = state.detectionTags.single()
            assertEquals(3L, tag.id)
            assertEquals("chondrule", tag.name)
            assertEquals("texture", tag.category)

            val location = state.userLocations.single()
            assertEquals("jdoe", location.username)
            assertEquals("Jane Doe", location.fullName)
            assertEquals(9L, location.userId)
            assertEquals(GeoCoordinate(latitude = -31.9, longitude = 115.8), location.coordinate)
            assertEquals("2026-08-01T00:00:00Z", location.processedAt)

            assertTrue(!state.showGeolocationAccuracyCircle)
            assertEquals("2026-08-20T09:30:00Z", state.latestTaskCreated)

            assertEquals(
                MapCameraTarget(latitude = -32.5, longitude = 137.1, zoom = 13.0),
                resolveInitialCamera(state),
            )
        }

    @Test
    fun `getCandidatesState handles HTTP 302 redirect to login page as AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/login"),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Login Page</body></html>"),
            )

            val result = repository.getCandidatesState(7L)

            assertEquals(Stage4FetchResult.AuthExpired, result)
        }

    @Test
    fun `getCandidatesState detects login redirect buried in multi-hop chain`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", mockWebServer.url("/login")),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", mockWebServer.url("/api/auth-gateway")),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Login Page</body></html>"),
            )

            val result = repository.getCandidatesState(7L)

            assertEquals(Stage4FetchResult.AuthExpired, result)
        }

    @Test
    fun `getCandidatesState treats uppercase LOGIN redirect as AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", mockWebServer.url("/LOGIN")),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Login Page</body></html>"),
            )

            val result = repository.getCandidatesState(7L)

            assertEquals(Stage4FetchResult.AuthExpired, result)
        }

    @Test
    fun `getCandidatesState ignores login keyword in query string`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "?next=/login"),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Not the login page</body></html>"),
            )

            val result = repository.getCandidatesState(7L)

            assertTrue(
                "Expected non-auth result but got $result",
                result != Stage4FetchResult.AuthExpired,
            )
        }

    @Test
    fun `getCandidatesState follows child survey redirect and parses valid payload`() =
        runTest(testDispatcher) {
            val jsonPayload =
                """
                {
                  "survey": {"id": 7, "event_id": "DN240703-02", "tileset_id": null},
                  "base": null,
                  "surveyed_areas": [],
                  "unprocessed_candidates": [],
                  "yes_meteorites": [],
                  "no_meteorites": [],
                  "detection_tags": [{"id": 3, "name": "chondrule", "category": "texture"}],
                  "user_locations": [],
                  "settings": {"show_geolocation_accuracy_circle": true},
                  "latest_task_created": "2026-08-20T09:30:00Z"
                }
                """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", mockWebServer.url("/api/stage4/surveys/3/candidates/")),
            )
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonPayload))

            val result = repository.getCandidatesState(7L)

            assertTrue("Expected Success but got $result", result is Stage4FetchResult.Success)
            val state = (result as Stage4FetchResult.Success).state
            assertEquals(7L, state.survey.id)
            assertEquals("DN240703-02", state.survey.eventId)
            assertNull(state.survey.tilesetId)
            assertTrue(state.surveyedAreas.isEmpty())
            assertTrue(state.showGeolocationAccuracyCircle)
            val tag = state.detectionTags.single()
            assertEquals(3L, tag.id)
            assertEquals("chondrule", tag.name)
            assertEquals("texture", tag.category)
        }

    @Test
    fun `getCandidatesState handles HTTP 401 as AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            assertEquals(Stage4FetchResult.AuthExpired, repository.getCandidatesState(7L))
        }

    @Test
    fun `getCandidatesState follows root to child to grandchild redirect chain`() =
        runTest(testDispatcher) {
            val jsonPayload =
                """
                {
                  "survey": {"id": 7, "event_id": "DN240703-02", "tileset_id": null},
                  "base": null,
                  "surveyed_areas": [],
                  "unprocessed_candidates": [],
                  "yes_meteorites": [],
                  "no_meteorites": [],
                  "detection_tags": [],
                  "user_locations": [],
                  "settings": {"show_geolocation_accuracy_circle": true},
                  "latest_task_created": "2026-08-20T09:30:00Z"
                }
                """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", mockWebServer.url("/api/stage4/surveys/3/candidates/")),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", mockWebServer.url("/api/stage4/surveys/9/candidates/")),
            )
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonPayload))

            val result = repository.getCandidatesState(7L)

            assertTrue("Expected Success but got $result", result is Stage4FetchResult.Success)
            val state = (result as Stage4FetchResult.Success).state
            assertEquals(7L, state.survey.id)
            assertEquals("DN240703-02", state.survey.eventId)
            assertTrue(state.showGeolocationAccuracyCircle)
        }

    @Test
    fun `getCandidatesState handles HTTP 403 as access denied Error`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(403))

            val result = repository.getCandidatesState(7L)

            val error = result as? Stage4FetchResult.Error
            assertTrue("Expected Error but got $result", error != null)
            assertTrue(
                "Expected access denied message but got ${error?.message}",
                error?.message?.contains("don't have access") == true,
            )
        }

    @Test
    fun `getCandidatesState handles HTTP 404 as survey task unavailable Error`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(404))

            val result = repository.getCandidatesState(7L)

            val error = result as? Stage4FetchResult.Error
            assertTrue("Expected Error but got $result", error != null)
            assertTrue(
                "Expected availability message but got ${error?.message}",
                error?.message?.contains("available for this survey") == true,
            )
        }

    @Test
    fun `getCandidatesState maps connection failure to NetworkError`() =
        runTest(testDispatcher) {
            val deadServer = MockWebServer()
            val deadUrl = deadServer.url("/")
            deadServer.shutdown()

            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(deadUrl)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()

            val deadService = retrofit.create(Stage4Service::class.java)

            org.mockito.kotlin
                .whenever(surveyDao.getById(7L))
                .thenReturn(null)

            val offlineRepository =
                Stage4Repository(
                    stage4Service = deadService,
                    moshi = moshi,
                    surveyDao = surveyDao,
                    candidateDao = candidateDao,
                    ioDispatcher = testDispatcher,
                )

            val result = offlineRepository.getCandidatesState(7L)

            assertEquals(Stage4FetchResult.NetworkError, result)
        }

    @Test
    fun `getCandidatesState returns cached data with isOffline flag on network failure`() =
        runTest(testDispatcher) {
            val deadServer = MockWebServer()
            val deadUrl = deadServer.url("/")
            deadServer.shutdown()

            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(deadUrl)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()

            val deadService = retrofit.create(Stage4Service::class.java)

            val surveyEntity =
                SurveyEntity(
                    id = 7L,
                    eventId = "EVT_7",
                    description = null,
                    created = "2026-09-08T00:00:00Z",
                    hasStage4 = true,
                    activeSurvey = true,
                    tilesetId = null,
                    latestTaskCreated = "2026-08-20T09:30:00Z",
                    baseLat = -29.0,
                    baseLon = 115.0,
                    surveyedAreasJson = "[]",
                    detectionTagsJson = "[]",
                    userLocationsJson = "[]",
                    showGeolocationAccuracyCircle = true,
                )

            org.mockito.kotlin
                .whenever(surveyDao.getById(7L))
                .thenReturn(surveyEntity)

            org.mockito.kotlin
                .whenever(candidateDao.getCandidatesForSurvey(7L))
                .thenReturn(emptyList())

            val offlineRepository =
                Stage4Repository(
                    stage4Service = deadService,
                    moshi = moshi,
                    surveyDao = surveyDao,
                    candidateDao = candidateDao,
                    ioDispatcher = testDispatcher,
                )

            val result = offlineRepository.getCandidatesState(7L)

            assertTrue("Expected Success but got $result", result is Stage4FetchResult.Success)
            val success = result as Stage4FetchResult.Success
            assertTrue("Expected offline success", success.isOffline)
            assertEquals(7L, success.state.survey.id)
            assertEquals("2026-08-20T09:30:00Z", success.state.latestTaskCreated)
        }

    @Test
    fun `getCandidatesState propagates CancellationException`() =
        runTest {
            val deferred =
                async(testDispatcher) {
                    repository.getCandidatesState(7L)
                }
            deferred.cancel(CancellationException("Test cancelled"))

            runCatching { deferred.await() }
                .onSuccess { error("Should have thrown CancellationException") }
                .onFailure { assertTrue(it is CancellationException) }
        }

    @Test
    fun `resolveInitialCamera centers on base when present`() {
        val state =
            stage4StateForCamera(
                base = GeoCoordinate(latitude = -30.1, longitude = 140.5),
                surveyedAreas = listOf(listOf(listOf(137.1, -32.5))),
            )

        assertEquals(
            MapCameraTarget(latitude = -30.1, longitude = 140.5, zoom = 13.0),
            resolveInitialCamera(state),
        )
    }

    @Test
    fun `resolveInitialCamera falls back to first surveyed area vertex`() {
        val state =
            stage4StateForCamera(
                base = null,
                surveyedAreas =
                    listOf(
                        listOf(listOf(137.1, -32.5), listOf(137.2, -32.6)),
                        listOf(listOf(138.0, -33.0)),
                    ),
            )

        assertEquals(
            MapCameraTarget(latitude = -32.5, longitude = 137.1, zoom = 13.0),
            resolveInitialCamera(state),
        )
    }

    @Test
    fun `resolveInitialCamera returns null without base or surveyed areas`() {
        val state = stage4StateForCamera(base = null, surveyedAreas = emptyList())

        assertNull(resolveInitialCamera(state))
    }

    private fun stage4StateForCamera(
        base: GeoCoordinate?,
        surveyedAreas: List<List<List<Double>>>,
    ): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = 1L, eventId = "DN240703-02", tilesetId = null),
            base = base,
            surveyedAreas = surveyedAreas,
            unprocessedCandidates = emptyList(),
            yesMeteorites = emptyList(),
            noMeteorites = emptyList(),
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "2026-08-20T09:30:00Z",
        )
}
