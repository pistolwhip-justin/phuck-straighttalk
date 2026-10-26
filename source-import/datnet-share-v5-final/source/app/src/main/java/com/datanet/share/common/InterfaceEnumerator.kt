package com.datanet.share.common

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object InterfaceEnumerator {
    private const val TAG = "InterfaceEnumerator"
    data class InterfaceInfo(val name: String, val displayName: String, val ip: String, val type: InterfaceType)
    enum class InterfaceType { WIFI_DIRECT, USB_TETHERING, WIFI, CELLULAR, OTHER }

    fun getAllInterfaces(): List<InterfaceInfo> {
        val result = mutableListOf<InterfaceInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name ?: continue
                val type = classifyInterface(name)
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add(InterfaceInfo(name, iface.displayName ?: name, addr.hostAddress ?: continue, type))
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to enumerate interfaces: ${e.message}") }
        return result.sortedBy { it.type.ordinal }
    }

    private fun classifyInterface(name: String): InterfaceType {
        val n = name.lowercase()
        return when {
            n.startsWith("p2p") -> InterfaceType.WIFI_DIRECT
            n.contains("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> InterfaceType.USB_TETHERING
            n.startsWith("wlan") -> InterfaceType.WIFI
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("eth") -> InterfaceType.CELLULAR
            else -> InterfaceType.OTHER
        }
    }

    fun getSummary(): String {
        val ifaces = getAllInterfaces()
        if (ifaces.isEmpty()) return "No network interfaces available"
        val sb = StringBuilder()
        for (iface in ifaces) {
            val typeLabel = when (iface.type) {
                InterfaceType.WIFI_DIRECT -> "WiFi Direct"
                InterfaceType.USB_TETHERING -> "USB Tethering"
                InterfaceType.WIFI -> "WiFi"
                InterfaceType.CELLULAR -> "Cellular"
                InterfaceType.OTHER -> "Other"
            }
            sb.append("$typeLabel (${iface.name}): ${iface.ip}\n")
        }
        return sb.toString().trimEnd('\n')
    }
}
