package com.datanet.share.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.Socket

object CellularNetworkTracker {
    private const val TAG = "CellularTracker"

    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var cellularCaps: NetworkCapabilities? = null
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mgr = cm ?: return
        try {
            val networks = mgr.allNetworks ?: emptyArray()
            for (network in networks) {
                val caps = mgr.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    cellularNetwork = network; cellularCaps = caps; break
                }
            }
        } catch (_: Exception) {}
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellularNetwork = network; try { cellularCaps = cm?.getNetworkCapabilities(network) } catch (_: Exception) {} }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { if (network == cellularNetwork) cellularCaps = caps }
            override fun onLost(network: Network) { if (network == cellularNetwork) { cellularNetwork = null; cellularCaps = null } }
        }
        callback = cb
        try { mgr.registerNetworkCallback(request, cb) } catch (_: Exception) {}
    }

    fun stop() {
        if (!started) return
        started = false
        try { callback?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        callback = null; cellularNetwork = null; cellularCaps = null
    }

    fun getCellularNetwork(): Network? = cellularNetwork

    fun createCellularSocket(): Socket? {
        val net = cellularNetwork ?: return null
        return try { net.socketFactory.createSocket() } catch (_: Exception) { null }
    }

    fun cellularAvailable(): Boolean = cellularNetwork != null

    fun describeNetwork(): String {
        val net = cellularNetwork ?: return "No cellular network"
        val caps = cellularCaps ?: return "Cellular network (no caps)"
        val downlink = caps.linkDownstreamBandwidthKbps
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "Cellular: downlink=${downlink}kbps validated=$validated"
    }

    // ====================================================================
    // VPN DETECTION — added by security audit patch
    //
    // When a VpnService (Google VPN, Mullvad, Tailscale, etc.) is active on
    // the sender phone, it sets up a TRANSPORT_VPN network. If we explicitly
    // pin outbound sockets to the cellular Network via socketFactory / bindSocket,
    // the kernel routes the packet out rmnet0 directly, bypassing the VPN's
    // tun0 interface. This is why the carrier sees raw proxied traffic even
    // when the user has a VPN enabled.
    //
    // The fix: before pinning to cellular, check if a VPN is active. If yes,
    // let the caller use a plain Socket() so the kernel routes through tun0.
    // ====================================================================

    /**
     * Returns the active VPN Network if any, or null.
     *
     * A "VPN Network" is identified by having TRANSPORT_VPN in its capabilities.
     * This is set by Android whenever a VpnService is established. We do NOT
     * check for the legacy VpnBuilder (IKEv2) — that uses TRANSPORT_VPN too.
     *
     * We look across allNetworks (not just activeNetwork) because there may be
     * a brief moment where the active network is cellular during VPN handoff.
     */
    fun findVpnNetwork(context: Context): Network? {
        val mgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            mgr.allNetworks?.firstOrNull { n ->
                try {
                    mgr.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Returns true if any VpnService is currently active on the device.
     *
     * This does NOT verify that the VPN will actually capture our traffic —
     * the VPN app may have configured addDisallowedApplication to exclude
     * com.datanet.share. In that case, even with a VPN active, our sockets
     * will go out the underlying network directly. The caller should fall
     * back to the original cellular-pinning behavior in that case.
     *
     * For most consumer VPNs (Google VPN, Mullvad, ProtonVPN), all apps are
     * captured by default, so this check is sufficient.
     */
    fun isVpnActive(context: Context): Boolean = findVpnNetwork(context) != null

    /**
     * Diagnostic helper — returns a human-readable description of the active
     * VPN network (or "no VPN" if none). Useful for logging during testing.
     */
    fun describeVpn(context: Context): String {
        val vpn = findVpnNetwork(context) ?: return "no VPN"
        val mgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = try { mgr?.getNetworkCapabilities(vpn) } catch (_: Exception) { null }
        return "VPN: $vpn caps=${caps ?: "unknown"}"
    }
}
