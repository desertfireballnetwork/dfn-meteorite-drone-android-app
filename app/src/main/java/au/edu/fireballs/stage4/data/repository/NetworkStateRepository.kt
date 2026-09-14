package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NetworkState {
    data object Online : NetworkState

    data object Offline : NetworkState
}

@Singleton
class NetworkStateRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val _networkState = MutableStateFlow(initialNetworkState())
        val networkState: StateFlow<NetworkState> = _networkState

        private var registered = false

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _networkState.value = NetworkState.Online
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    _networkState.value = networkStateFor(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    _networkState.value = NetworkState.Offline
                }

                override fun onUnavailable() {
                    _networkState.value = NetworkState.Offline
                }
            }

        private val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

        init {
            register()
        }

        internal fun register() {
            if (registered) return
            registered = true
            connectivityManager.registerNetworkCallback(request, callback)
        }

        private fun initialNetworkState(): NetworkState {
            val network = connectivityManager.activeNetwork
            val capabilities =
                network?.let { connectivityManager.getNetworkCapabilities(it) }
            return networkStateFor(capabilities)
        }
    }

internal fun networkStateFor(capabilities: NetworkCapabilities?): NetworkState =
    if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
        NetworkState.Online
    } else {
        NetworkState.Offline
    }
