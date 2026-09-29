package io.bbui.assistant

import io.bbui.device.DeviceUnavailable

/** A task generation is not a device lease: one task may release and recreate its display. */
internal object EnvironmentFailurePolicy {
    fun accepts(event: DeviceUnavailable, currentConnectionEpoch: Long, resourceState: String,
        releasing: Boolean, closed: Boolean): Boolean {
        if (closed || releasing || resourceState == "releasing" || event.connectionEpoch != currentConnectionEpoch) return false
        // System bindings are also used by tools that never create a virtual display.
        return event.source == DeviceUnavailable.Source.SYSTEM || resourceState in setOf("creating", "ready")
    }
}
