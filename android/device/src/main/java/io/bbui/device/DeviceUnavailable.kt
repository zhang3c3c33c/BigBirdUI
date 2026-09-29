package io.bbui.device

/** Ownership is captured by the failed connection, never when a UI callback is consumed. */
data class DeviceUnavailable(val reason: String, val connectionEpoch: Long, val source: Source) {
    enum class Source { SYSTEM, DISPLAY }
}
