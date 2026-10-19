package tr.h2open.driver.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether the phone can reach the internet. The app has no offline mode:
 * while this is false it says so and every action that changes something
 * is disabled.
 */
class Connectivity(context: Context, scope: CoroutineScope) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val raw = MutableStateFlow(isOnlineNow())

    /** Goes offline only after [OFFLINE_GRACE_MILLIS], so a short switch between networks does not flash the bar. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val online: StateFlow<Boolean> = raw
        .transformLatest { online ->
            if (!online) delay(OFFLINE_GRACE_MILLIS)
            emit(online)
        }
        .stateIn(scope, SharingStarted.Eagerly, raw.value)

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                raw.value = capabilities.reachesInternet()
            }

            override fun onLost(network: Network) {
                raw.value = false
            }
        })
    }

    private fun isOnlineNow(): Boolean =
        manager.getNetworkCapabilities(manager.activeNetwork)?.reachesInternet() == true

    private fun NetworkCapabilities.reachesInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val OFFLINE_GRACE_MILLIS = 2_000L
    }
}
