package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
class NetworkStateRepositoryTest {
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var repository: NetworkStateRepository
    private val network = ShadowNetwork.newInstance(1)
    private val network2 = ShadowNetwork.newInstance(2)

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
    fun `onAvailable with internet capabilities moves state online`() {
        shadowOf(connectivityManager).setNetworkCapabilities(network, internetCapabilities())
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)

        assertEquals(NetworkState.Online, repository.networkState.value)
    }

    @Test
    fun `onAvailable for local-only network stays offline`() {
        shadowOf(connectivityManager).setNetworkCapabilities(network, NetworkCapabilities())
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)

        assertEquals(NetworkState.Offline, repository.networkState.value)
    }

    @Test
    fun `onLost moves state offline when no other network active`() {
        shadowOf(connectivityManager).setNetworkCapabilities(network, internetCapabilities())
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)
        callback.onLost(network)

        assertEquals(NetworkState.Offline, repository.networkState.value)
    }

    @Test
    fun `onLost keeps state online while another network remains active`() {
        shadowOf(connectivityManager).setNetworkCapabilities(network, internetCapabilities())
        shadowOf(connectivityManager).setNetworkCapabilities(network2, internetCapabilities())
        setActiveNetwork(network2)
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onAvailable(network)
        callback.onAvailable(network2)
        callback.onLost(network)

        assertEquals(NetworkState.Online, repository.networkState.value)
    }

    @Test
    fun `onUnavailable moves state offline`() {
        shadowOf(connectivityManager).setNetworkCapabilities(network, internetCapabilities())
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

    @Test
    fun `networkStateFor requires validated internet to be online`() {
        assertEquals(NetworkState.Offline, networkStateFor(internetCapabilitiesWithoutValidation()))
        assertEquals(NetworkState.Online, networkStateFor(internetCapabilities()))
    }

    private fun setActiveNetwork(network: Network) {
        val info =
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true,
            )
        shadowOf(connectivityManager).setActiveNetworkInfo(info)
        shadowOf(connectivityManager).addNetwork(network, info)
    }

    private fun internetCapabilities(): NetworkCapabilities =
        capabilitiesWith(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        )

    private fun internetCapabilitiesWithoutValidation(): NetworkCapabilities =
        capabilitiesWith(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun capabilitiesWith(vararg capabilities: Int): NetworkCapabilities {
        val builderClass = Class.forName("android.net.NetworkCapabilities\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        capabilities.forEach { capability ->
            builderClass
                .getMethod("addCapability", Int::class.javaPrimitiveType)
                .invoke(builder, capability)
        }
        return builderClass.getMethod("build").invoke(builder) as NetworkCapabilities
    }
}
