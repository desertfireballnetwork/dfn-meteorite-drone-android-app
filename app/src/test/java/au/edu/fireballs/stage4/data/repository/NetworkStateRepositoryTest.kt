package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class NetworkStateRepositoryTest {
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var repository: NetworkStateRepository
    private val network = ShadowNetwork.newInstance(1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        repository = NetworkStateRepository(context)
    }

    @Test
    fun `starts offline when no active network`() {
        assertEquals(NetworkState.Offline, repository.networkState.value)
    }

    @Test
    fun `registers a single network callback`() {
        assertEquals(1, shadowOf(connectivityManager).networkCallbacks.size)
    }

    @Test
    fun `register is idempotent`() {
        repository.register()
        repository.register()

        assertEquals(1, shadowOf(connectivityManager).networkCallbacks.size)
    }

    @Test
    fun `onAvailable moves state online`() {
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)

        assertEquals(NetworkState.Online, repository.networkState.value)
    }

    @Test
    fun `onLost moves state offline`() {
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)
        callback.onLost(network)

        assertEquals(NetworkState.Offline, repository.networkState.value)
    }

    @Test
    fun `onUnavailable moves state offline`() {
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)
        callback.onUnavailable()

        assertEquals(NetworkState.Offline, repository.networkState.value)
    }

    @Test
    fun `onCapabilitiesChanged without internet maps offline`() {
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onCapabilitiesChanged(network, NetworkCapabilities())

        assertEquals(NetworkState.Offline, repository.networkState.value)
    }

    @Test
    fun `networkStateFor maps capabilities to state`() {
        assertEquals(NetworkState.Online, networkStateFor(internetCapabilities()))
        assertEquals(NetworkState.Offline, networkStateFor(NetworkCapabilities()))
        assertEquals(NetworkState.Offline, networkStateFor(null))
    }

    private fun internetCapabilities(): NetworkCapabilities {
        val builderClass = Class.forName("android.net.NetworkCapabilities\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass
            .getMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(builder, NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return builderClass.getMethod("build").invoke(builder) as NetworkCapabilities
    }
}
