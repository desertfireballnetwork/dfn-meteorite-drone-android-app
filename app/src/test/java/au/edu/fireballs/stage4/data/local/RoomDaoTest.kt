package au.edu.fireballs.stage4.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomDaoTest {
    private lateinit var db: Stage4Database
    private lateinit var surveyDao: SurveyDao
    private lateinit var candidateDao: CandidateDao
    private lateinit var claimDao: ClaimDao
    private lateinit var localDecisionDao: LocalDecisionDao
    private lateinit var pendingPhotoUploadDao: PendingPhotoUploadDao
    private lateinit var offlineBundleDao: OfflineBundleDao
    private lateinit var tileManifestDao: TileManifestDao

    @Before
    fun createDb() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    Stage4Database::class.java,
                ).allowMainThreadQueries()
                .build()

        surveyDao = db.surveyDao()
        candidateDao = db.candidateDao()
        claimDao = db.claimDao()
        localDecisionDao = db.localDecisionDao()
        pendingPhotoUploadDao = db.pendingPhotoUploadDao()
        offlineBundleDao = db.offlineBundleDao()
        tileManifestDao = db.tileManifestDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun surveyDao_upsertAndRead() =
        runBlocking {
            val survey =
                SurveyEntity(
                    id = 101L,
                    eventId = "EVT_2026_001",
                    description = "Desert Search",
                    created = "2026-07-24T00:00:00Z",
                    hasStage4 = true,
                    activeSurvey = true,
                    tilesetId = "mapbox://styles/test",
                    latestTaskCreated = null,
                    baseLat = -25.2744,
                    baseLon = 133.7751,
                    lastViewed = 1721779200000L,
                    surveyedAreasJson = "[]",
                    detectionTagsJson = "[]",
                    userLocationsJson = "[]",
                    showGeolocationAccuracyCircle = true,
                )

            surveyDao.upsert(survey)

            val retrieved = surveyDao.getById(101L)
            assertNotNull(retrieved)
            assertEquals("EVT_2026_001", retrieved?.eventId)

            val observed = surveyDao.observeAllSurveys().first()
            assertEquals(1, observed.size)
        }

    @Test
    fun candidateDao_insertAndRead() =
        runBlocking {
            val candidates =
                listOf(
                    CandidateEntity(
                        inferenceResultId = 5001L,
                        surveyId = 101L,
                        imageId = 88L,
                        imageFilename = "img_001.jpg",
                        imageWidth = 1920,
                        imageHeight = 1080,
                        geoCentroidLat = -25.27,
                        geoCentroidLon = 133.77,
                        geoAreaJson = "[[133.77,-25.27]]",
                        boxX = 100,
                        boxY = 100,
                        boxW = 50,
                        boxH = 50,
                        confidence = 0.95f,
                        sizeMw = 2.5f,
                        sizeMh = 1.2f,
                        isClaimedByMe = true,
                        claimOwnerUsername = "operator1",
                    ),
                )

            candidateDao.upsertAll(candidates)

            val retrieved = candidateDao.getById(5001L)
            assertNotNull(retrieved)
            assertEquals("img_001.jpg", retrieved?.imageFilename)

            val listForSurvey = candidateDao.observeCandidatesForSurvey(101L).first()
            assertEquals(1, listForSurvey.size)
        }

    @Test
    fun claimDao_filtersActiveClaimsAndReleases() =
        runBlocking {
            val activeClaim =
                ClaimEntity(
                    inferenceResultId = 1L,
                    surveyId = 101L,
                    userId = 42L,
                    username = "user_a",
                    claimedAt = "2026-07-24T10:00:00Z",
                    isMine = true,
                    isActive = true,
                )

            val inactiveClaim =
                ClaimEntity(
                    inferenceResultId = 2L,
                    surveyId = 101L,
                    userId = 42L,
                    username = "user_a",
                    claimedAt = "2026-07-24T10:05:00Z",
                    isMine = true,
                    isActive = false,
                )

            claimDao.upsertAll(listOf(activeClaim, inactiveClaim))

            val allClaims = claimDao.getClaims(101L, onlyActive = false).first()
            assertEquals(2, allClaims.size)

            val activeOnly = claimDao.getClaims(101L, onlyActive = true).first()
            assertEquals(1, activeOnly.size)
            assertEquals(1L, activeOnly[0].inferenceResultId)

            // Bulk Release test
            claimDao.releaseClaimsForUser(42L, listOf(1L))
            val activeAfterRelease = claimDao.getClaims(101L, onlyActive = true).first()
            assertTrue(activeAfterRelease.isEmpty())
        }

    @Test
    fun localDecisionDao_getUnsyncedAndMarkSynced() =
        runBlocking {
            val decision =
                LocalDecisionEntity(
                    inferenceResultId = 701L,
                    surveyId = 101L,
                    verdict = true,
                    detectionTagId = 5L,
                    capturedAt = "2026-07-24T12:00:00Z",
                    evidencePhotoRowId = 12L,
                    synced = false,
                )

            localDecisionDao.saveDecision(decision)

            val unsyncedList = localDecisionDao.getUnsynced()
            assertEquals(1, unsyncedList.size)
            assertEquals(701L, unsyncedList[0].inferenceResultId)

            localDecisionDao.markSynced(701L, "2026-07-24T12:01:00Z")

            val unsyncedAfter = localDecisionDao.getUnsynced()
            assertTrue(unsyncedAfter.isEmpty())
        }

    @Test
    fun pendingPhotoUploadDao_lifecycle() =
        runBlocking {
            val photo =
                PendingPhotoUploadEntity(
                    surveyId = 101L,
                    inferenceResultId = 701L,
                    localFilePath = "/data/photos/p1.jpg",
                    capturedAt = "2026-07-24T12:00:00Z",
                    uploaded = false,
                )

            val rowId = pendingPhotoUploadDao.insert(photo)

            val unuploaded = pendingPhotoUploadDao.getUnuploaded()
            assertEquals(1, unuploaded.size)
            assertEquals(rowId, unuploaded[0].rowId)

            pendingPhotoUploadDao.markUploaded(rowId, serverPhotoId = 9999L)

            val unuploadedAfter = pendingPhotoUploadDao.getUnuploaded()
            assertTrue(unuploadedAfter.isEmpty())
        }

    @Test
    fun offlineBundleAndManifest_roundTrip() =
        runBlocking {
            val bundle =
                OfflineBundleEntity(
                    surveyId = 101L,
                    created = "2026-07-24T12:00:00Z",
                    totalBytes = 1024000L,
                    tileCount = 50,
                    satelliteRegionCount = 1,
                    candidateCount = 10,
                    bufferMeters = 500.0f,
                )

            val bundleId = offlineBundleDao.insert(bundle)
            assertTrue(bundleId > 0)

            val latestBundle = offlineBundleDao.observeLatestBundleForSurvey(101L).first()
            assertNotNull(latestBundle)
            assertEquals(1024000L, latestBundle?.totalBytes)

            val tiles =
                listOf(
                    TileManifestEntity(
                        surveyId = 101L,
                        candidateId = 5001L,
                        zoom = 15,
                        x = 10,
                        y = 20,
                        bytes = 2048L,
                    ),
                )
            tileManifestDao.insertAll(tiles)

            val storedTiles = tileManifestDao.getTilesForSurvey(101L)
            assertEquals(1, storedTiles.size)
            assertEquals(15, storedTiles[0].zoom)
        }

    @Test
    fun pendingPhotoUpload_insertAndQueryByCandidate() =
        runBlocking {
            val photo =
                PendingPhotoUploadEntity(
                    surveyId = 101L,
                    inferenceResultId = 5001L,
                    localFilePath = "/data/evidence/101/5001/1.jpg",
                    capturedAt = "2026-07-24T12:00:00Z",
                    uploaded = false,
                )

            val rowId = pendingPhotoUploadDao.insert(photo)
            assertTrue(rowId > 0)

            val photos = pendingPhotoUploadDao.getLocalPhotosForCandidate(5001L).first()
            assertEquals(1, photos.size)
            assertEquals(rowId, photos.first().rowId)
            assertFalse(photos.first().uploaded)

            val otherCandidate = pendingPhotoUploadDao.getLocalPhotosForCandidate(9999L).first()
            assertTrue(otherCandidate.isEmpty())
        }

    @Test
    fun pendingPhotoUpload_markUploadedUpdatesRow() =
        runBlocking {
            val photo =
                PendingPhotoUploadEntity(
                    surveyId = 101L,
                    inferenceResultId = 5001L,
                    localFilePath = "/data/evidence/101/5001/1.jpg",
                    capturedAt = "2026-07-24T12:00:00Z",
                    uploaded = false,
                )

            val rowId = pendingPhotoUploadDao.insert(photo)

            pendingPhotoUploadDao.markUploaded(rowId, serverPhotoId = 77L)

            val photos = pendingPhotoUploadDao.getLocalPhotosForCandidate(5001L).first()
            assertTrue(photos.first().uploaded)
            assertEquals(77L, photos.first().serverPhotoId)
        }
}
