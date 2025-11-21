package com.mahmutalperenunal.fling.tv

/** Source of the PIN shown on the TV side. */
enum class PinSource {
    NEARBY,    // Nearby authenticationToken
    LAN_IN,    // LAN: TV receiver (Phone → TV), info-only
    LAN_OUT    // LAN: TV sender (TV → Phone), requires user confirmation
}