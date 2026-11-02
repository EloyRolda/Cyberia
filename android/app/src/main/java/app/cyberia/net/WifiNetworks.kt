package app.cyberia.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * La red Wi-Fi (o Ethernet) de casa. Todo el tráfico con la PC se ata a esta red, así la app
 * nunca usa datos móviles aunque el Wi-Fi no tenga internet (RF-42).
 */
object WifiNetworks {
    fun current(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    fun isConnected(context: Context): Boolean = current(context) != null
}
