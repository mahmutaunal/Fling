package com.mahmutalperenunal.fling.core

object LanProtocol {
    // mDNS service type for TV-side discovery
    const val SERVICE_TYPE = "_tvdrop._tcp."
    // Prefix used when broadcasting TV device name
    const val SERVICE_NAME_PREFIX = "TVDrop-"

    // mDNS service type for phone-side discovery
    const val SERVICE_TYPE_PHONE = "_tvdrop_phone._tcp."
    // Prefix used when broadcasting phone device name
    const val SERVICE_NAME_PREFIX_PHONE = "TVDropPhone-"

    // Protocol message keys exchanged during LAN transfers
    const val HEADER = "HEADER"
    const val FILE_META = "FILE_META"
    const val ACK = "ACK"
    const val HELLO = "HELLO"
    const val CONFIRM = "CONFIRM"

    // Chunk size for TCP socket reads/writes (64 KB)
    const val BUFFER = 64 * 1024
}