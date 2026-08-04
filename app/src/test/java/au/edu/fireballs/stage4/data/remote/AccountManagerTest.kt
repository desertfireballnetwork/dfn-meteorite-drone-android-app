package au.edu.fireballs.stage4.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.Stage4Database
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountManagerTest {
    private lateinit var database: Stage4Database
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var accountManager: AccountManager

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

        accountManager =
            AccountManager(
                cookieJar = cookieJar,
                baseUrl = testUrl,
                database = database,
                ioDispatcher = testDispatcher,
            )
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
}
