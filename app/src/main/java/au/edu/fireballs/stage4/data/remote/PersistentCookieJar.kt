package au.edu.fireballs.stage4.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class PersistentCookieJar(
    context: Context,
    prefs: SharedPreferences? = null,
) : CookieJar {
    private val sharedPreferences: SharedPreferences =
        prefs ?: run {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    private val cookieStore: ConcurrentHashMap<String, Cookie> = ConcurrentHashMap()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val now = System.currentTimeMillis()
        val editor = sharedPreferences.edit()
        var hasRemoved = false

        for ((key, value) in sharedPreferences.all) {
            if (processPrefEntry(key, value, now, editor)) {
                hasRemoved = true
            }
        }
        if (hasRemoved) {
            editor.apply()
        }
    }

    private fun processPrefEntry(
        key: String,
        value: Any?,
        now: Long,
        editor: SharedPreferences.Editor,
    ): Boolean {
        if (!key.startsWith(COOKIE_KEY_PREFIX) || value !is String) return false
        val cookie = deserializeCookie(value) ?: return false
        if (cookie.expiresAt > now) {
            cookieStore[key] = cookie
            return false
        }
        editor.remove(key)
        return true
    }

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val editor = sharedPreferences.edit()
        val now = System.currentTimeMillis()

        for (cookie in cookies) {
            val key = cookieKey(cookie)
            if (cookie.expiresAt <= now) {
                cookieStore.remove(key)
                editor.remove(key)
            } else {
                cookieStore[key] = cookie
                editor.putString(key, serializeCookie(cookie))
            }
        }
        editor.apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val validCookies = mutableListOf<Cookie>()
        val expiredKeys = mutableListOf<String>()

        for ((key, cookie) in cookieStore) {
            if (cookie.expiresAt <= now) {
                expiredKeys.add(key)
            } else if (cookie.matches(url)) {
                validCookies.add(cookie)
            }
        }

        if (expiredKeys.isNotEmpty()) {
            val editor = sharedPreferences.edit()
            for (key in expiredKeys) {
                cookieStore.remove(key)
                editor.remove(key)
            }
            editor.apply()
        }

        return validCookies
    }

    private fun cookieKey(cookie: Cookie): String =
        "$COOKIE_KEY_PREFIX${cookie.domain}:${cookie.path}:${cookie.name}"

    private fun serializeCookie(cookie: Cookie): String =
        listOf(
            cookie.name,
            cookie.value,
            cookie.expiresAt.toString(),
            cookie.domain,
            cookie.path,
            cookie.secure.toString(),
            cookie.httpOnly.toString(),
            cookie.hostOnly.toString(),
        ).joinToString(DELIMITER)

    private fun deserializeCookie(serialized: String): Cookie? {
        val parts = serialized.split(DELIMITER)
        if (parts.size < 8) return null
        return try {
            val builder =
                Cookie
                    .Builder()
                    .name(parts[0])
                    .value(parts[1])
                    .expiresAt(parts[2].toLong())

            val domain = parts[3]
            val hostOnly = parts[7].toBoolean()
            if (hostOnly) {
                builder.hostOnlyDomain(domain)
            } else {
                builder.domain(domain)
            }

            builder.path(parts[4])
            if (parts[5].toBoolean()) builder.secure()
            if (parts[6].toBoolean()) builder.httpOnly()
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "stage4_secure_cookies"
        private const val COOKIE_KEY_PREFIX = "cookie:"
        private const val DELIMITER = "|"
    }
}
