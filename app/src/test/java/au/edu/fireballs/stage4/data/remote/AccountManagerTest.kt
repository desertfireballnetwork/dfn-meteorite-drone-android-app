package au.edu.fireballs.stage4.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AccountManagerTest {
    private lateinit var database: Stage4Database
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var offlineRegionWrapper: OfflineRegionWrapper
    private lateinit var accountManager: AccountManager
    private lateinit var selectedSurveyRepository: SelectedSurveyRepository

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testUrl: HttpUrl = "https://example.com/".toHttpUrl()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        database =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    Stage4Database::class.java,
                ).allowMainThreadQueries()
                .build()

        // Use ordinary SharedPreferences instead of encrypted ones.
        val prefs =
            context.getSharedPreferences(
                "test_cookies",
                Context.MODE_PRIVATE,
            )
        prefs.edit().clear().commit()

        cookieJar =
            PersistentCookieJar(
                context = context,
                prefs = prefs,
            )

        offlineRegionWrapper = mock(OfflineRegionWrapper::class.java)
        answerPurgeWith(Result.success(Unit))

        selectedSurveyRepository = mockk()
        coEvery { selectedSurveyRepository.clear() } returns Unit

        accountManager =
            AccountManager(
                cookieJar = cookieJar,
                baseUrl = testUrl,
                database = database,
                tileStore = TileStore(File.createTempFile("am-tiles", "").parentFile),
                offlineRegionWrapper = offlineRegionWrapper,
                selectedSurveyRepository = selectedSurveyRepository,
                ioDispatcher = testDispatcher,
            )
    }

    private fun answerPurgeWith(result: Result<Unit>) {
        doAnswer { invocation ->
            val callback = invocation.getArgument<(Result<Unit>) -> Unit>(0)
            callback(result)
            null
        }.`when`(offlineRegionWrapper).purgeAllRegions(any())
    }

    @After
    fun tearDown() {
        cookieJar.clear()
        database.close()
    }

    @Test
    fun `isSignedIn returns false when sessionid cookie is missing`() {
        val result = accountManager.isSignedIn()

        assertFalse(result)
    }

    @Test
    fun `isSignedIn returns false when sessionid cookie value is blank`() {
        val blankCookie =
            Cookie
                .Builder()
                .domain("example.com")
                .path("/")
                .name("sessionid")
                .value("")
                .build()

        cookieJar.saveFromResponse(testUrl, listOf(blankCookie))

        assertFalse(accountManager.isSignedIn())
    }

    @Test
    fun `isSignedIn returns true when valid sessionid cookie exists`() {
        val validCookie =
            Cookie
                .Builder()
                .domain("example.com")
                .path("/")
                .name("sessionid")
                .value("valid_session_token_123")
                .build()

        cookieJar.saveFromResponse(testUrl, listOf(validCookie))

        assertTrue(accountManager.isSignedIn())
    }

    @Test
    fun `logout clears cookies and clears database tables`() =
        runTest {
            val validCookie =
                Cookie
                    .Builder()
                    .domain("example.com")
                    .path("/")
                    .name("sessionid")
                    .value("valid_session_token_123")
                    .build()

            cookieJar.saveFromResponse(testUrl, listOf(validCookie))

            assertTrue(accountManager.isSignedIn())

            accountManager.logout()

            assertFalse(accountManager.isSignedIn())
            assertTrue(cookieJar.loadForRequest(testUrl).isEmpty())
        }

    @Test
    fun `logout waits for region purge before returning`() =
        runTest {
            var purgeCallback: ((Result<Unit>) -> Unit)? = null
            doAnswer { invocation ->
                purgeCallback = invocation.getArgument<(Result<Unit>) -> Unit>(0)
                null
            }.`when`(offlineRegionWrapper).purgeAllRegions(any())

            val logoutJob = launch { accountManager.logout() }
            runCurrent()

            assertFalse(logoutJob.isCompleted)

            purgeCallback?.invoke(Result.success(Unit))
            runCurrent()

            assertTrue(logoutJob.isCompleted)
            verify(offlineRegionWrapper).purgeAllRegions(any())
        }

    @Test
    fun `logout propagates region purge failure`() =
        runTest {
            val validCookie =
                Cookie
                    .Builder()
                    .domain("example.com")
                    .path("/")
                    .name("sessionid")
                    .value("valid_session_token_123")
                    .build()
            cookieJar.saveFromResponse(testUrl, listOf(validCookie))
            answerPurgeWith(Result.failure(IllegalStateException("purge failed")))

            var thrown: Throwable? = null
            try {
                accountManager.logout()
            } catch (error: IllegalStateException) {
                thrown = error
            }

            assertTrue(thrown is IllegalStateException)
            assertFalse(accountManager.isSignedIn())
            assertTrue(cookieJar.loadForRequest(testUrl).isEmpty())
        }
}
