package com.datanet.share.common

object Constants {
    const val CHANNEL_ID = "datanet_share_service"
    const val SENDER_NOTIFICATION_ID = 1001
    const val RECEIVER_NOTIFICATION_ID = 1002
    const val SOCKS5_PORT = 8282
    // Cloudflare DNS — privacy-respecting, supports DoH/DoT.
    // Replaces Google's 8.8.8.8 to avoid leaking DNS metadata to Google
    // when the user is also using Google's VPN.
    const val TUNNELED_DNS = "1.1.1.1"
    const val ACTION_SENDER_STATE = "com.datanet.share.SENDER_STATE"
    const val ACTION_RECEIVER_STATE = "com.datanet.share.RECEIVER_STATE"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DETAILS = "details"
    const val EXTRA_PROXY_IP = "proxy_ip"
    const val EXTRA_PROXY_PORT = "proxy_port"
    const val EXTRA_SSID = "ssid"
    const val EXTRA_PASSPHRASE = "passphrase"
    const val EXTRA_INTERFACES = "interfaces"
    const val VPN_ADDRESS = "10.10.10.1"
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_ROUTE_PREFIX = 0
}
