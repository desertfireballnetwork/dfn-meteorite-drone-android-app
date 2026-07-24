package au.edu.fireballs.stage4.data.remote

import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PersistentCookieJarTest {
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var cookieJar: PersistentCookieJar

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        // Pass dummy Context as null (or unused) since we provide fakePrefs
        cookieJar =
            PersistentCookieJar(
                context = DummyContext(),
                prefs = fakePrefs,
            )
    }

    @Test
    fun `saveFromResponse and loadForRequest persists and retrieves cookies`() {
        val url = "https://find.gfo.rocks/api/test".toHttpUrl()
        val cookie =
            Cookie
                .Builder()
                .name("sessionid")
                .value("xyz123")
                .domain("find.gfo.rocks")
                .path("/")
                .build()

        cookieJar.saveFromResponse(url, listOf(cookie))

        val loaded = cookieJar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("sessionid", loaded[0].name)
        assertEquals("xyz123", loaded[0].value)
    }

    @Test
    fun `loadForRequest filters out expired cookies`() {
        val url = "https://find.gfo.rocks/api/test".toHttpUrl()
        val expiredCookie =
            Cookie
                .Builder()
                .name("old_session")
                .value("expired")
                .domain("find.gfo.rocks")
                .path("/")
                .expiresAt(System.currentTimeMillis() - 10000)
                .build()

        cookieJar.saveFromResponse(url, listOf(expiredCookie))

        val loaded = cookieJar.loadForRequest(url)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `new PersistentCookieJar instance reloads cookies from SharedPreferences`() {
        val url = "https://find.gfo.rocks/api/test".toHttpUrl()
        val cookie =
            Cookie
                .Builder()
                .name("csrftoken")
                .value("secrettoken")
                .domain("find.gfo.rocks")
                .path("/")
                .expiresAt(System.currentTimeMillis() + 3600000)
                .build()

        cookieJar.saveFromResponse(url, listOf(cookie))

        // Create new instance backed by same SharedPreferences
        val reloadedJar =
            PersistentCookieJar(
                context = DummyContext(),
                prefs = fakePrefs,
            )

        val loaded = reloadedJar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("csrftoken", loaded[0].name)
        assertEquals("secrettoken", loaded[0].value)
    }

    private class DummyContext : android.content.ContextWrapper(null)

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = map.toMap()

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = (map[key] as? String) ?: defValue

        override fun getStringSet(
            key: String,
            defValues: Set<String>?,
        ): Set<String>? {
            @Suppress("UNCHECKED_CAST")
            return (map[key] as? Set<String>) ?: defValues
        }

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = (map[key] as? Int) ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = (map[key] as? Long) ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = (map[key] as? Float) ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = (map[key] as? Boolean) ?: defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listener?.let { listeners.add(it) }
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listener?.let { listeners.remove(it) }
        }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?,
            ): SharedPreferences.Editor {
                tempMap[key] = value
                toRemove.remove(key)
                return this
            }

            override fun putStringSet(
                key: String,
                values: Set<String>?,
            ): SharedPreferences.Editor {
                tempMap[key] = values
                toRemove.remove(key)
                return this
            }

            override fun putInt(
                key: String,
                value: Int,
            ): SharedPreferences.Editor {
                tempMap[key] = value
                toRemove.remove(key)
                return this
            }

            override fun putLong(
                key: String,
                value: Long,
            ): SharedPreferences.Editor {
                tempMap[key] = value
                toRemove.remove(key)
                return this
            }

            override fun putFloat(
                key: String,
                value: Float,
            ): SharedPreferences.Editor {
                tempMap[key] = value
                toRemove.remove(key)
                return this
            }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ): SharedPreferences.Editor {
                tempMap[key] = value
                toRemove.remove(key)
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                toRemove.add(key)
                tempMap.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) {
                    map.clear()
                }
                for (key in toRemove) {
                    map.remove(key)
                }
                map.putAll(tempMap)
            }
        }
    }
}
