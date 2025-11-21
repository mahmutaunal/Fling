package com.mahmutalperenunal.fling.core

object NearbyProtocol {
    // Nearby Connections service identifier shared between phone and TV
    const val SERVICE_ID = "com.mahmutalperenunal.fling.service"

    // Heartbeat request to verify active connection
    const val PING = "PING"
    // Heartbeat response confirming connection is alive
    const val PONG = "PONG"
}