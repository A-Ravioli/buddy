package buddy.android.surface.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import buddy.android.BuddyApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One network buddy can offer, strongest first, deduplicated by name. */
data class Network(val ssid: String, val secured: Boolean, val level: Int)

/** Where the join got to. The step reads this and nothing else. */
sealed interface WifiState {
    data object Off : WifiState
    data object Scanning : WifiState
    data class Joining(val ssid: String) : WifiState
    data class Joined(val ssid: String) : WifiState
    data class Failed(val ssid: String) : WifiState
}

/**
 * Getting the phone online during the walk-through, without a Settings screen.
 *
 * buddy is the setup wizard here, so it configures networks directly rather than handing
 * off: as a platform-signed privileged app holding NETWORK_SETUP_WIZARD it may add and
 * enable a configuration, which an ordinary app has not been allowed to do since
 * Android 10. VERIFY at the pinned tag that this path is still the one the tree's own
 * wizard uses; the alternative is WifiNetworkSuggestion, which prompts.
 */
class WifiJoiner(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _networks = MutableStateFlow<List<Network>>(emptyList())
    val networks: StateFlow<List<Network>> = _networks

    private val _state = MutableStateFlow<WifiState>(WifiState.Off)
    val state: StateFlow<WifiState> = _state

    private var receiver: BroadcastReceiver? = null

    /** True when something already reaches the internet, so the step can be skipped. */
    fun online(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun start() {
        val w = wifi ?: return
        if (!w.isWifiEnabled) {
            @Suppress("DEPRECATION")
            runCatching { w.setWifiEnabled(true) }
        }
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = publish()
            }
            runCatching {
                app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
            }
        }
        _state.value = WifiState.Scanning
        @Suppress("DEPRECATION")
        runCatching { w.startScan() }
        publish()
    }

    fun stop() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }

    /** Joins [ssid]. [password] is null for an open network. */
    fun join(ssid: String, password: String?) {
        val w = wifi ?: return
        _state.value = WifiState.Joining(ssid)
        val ok = runCatching {
            @Suppress("DEPRECATION")
            val config = WifiConfiguration().apply {
                SSID = "\"" + ssid + "\""
                if (password.isNullOrEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"" + password + "\""
                }
            }
            @Suppress("DEPRECATION")
            val id = w.addNetwork(config)
            if (id == -1) false else {
                @Suppress("DEPRECATION")
                w.enableNetwork(id, true)
            }
        }.getOrElse {
            Log.w(BuddyApp.TAG, "join failed for $ssid", it)
            false
        }
        _state.value = if (ok) WifiState.Joined(ssid) else WifiState.Failed(ssid)
    }

    private fun publish() {
        val w = wifi ?: return
        val results: List<ScanResult> = runCatching { w.scanResults }.getOrDefault(emptyList())
        _networks.value = results
            .mapNotNull { r ->
                val name = ssidOf(r)
                if (name.isNullOrBlank()) null else Network(name, secured(r), r.level)
            }
            .groupBy { it.ssid }
            .map { (_, all) -> all.maxByOrNull { it.level }!! }
            .sortedByDescending { it.level }
            .take(8)
    }

    private fun ssidOf(r: ScanResult): String? {
        // wifiSsid arrived in Android 13; SSID is the older field and still populated.
        @Suppress("DEPRECATION")
        return r.SSID?.trim()?.removeSurrounding("\"")
    }

    private fun secured(r: ScanResult): Boolean {
        val caps = r.capabilities ?: return true
        return listOf("WEP", "PSK", "EAP", "SAE", "OWE").any { it in caps }
    }
}
