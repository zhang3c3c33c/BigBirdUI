package io.bbui.core

import android.view.Surface
import org.json.JSONObject

/** Blocking actions are serialized by the caller; STOP must remain independently callable. */
interface PhoneDevice {
    fun connect(): JSONObject
    fun action(operation: String, params: JSONObject): JSONObject
    fun systemAction(group: String, operation: String, params: JSONObject, actionId: String): JSONObject = throw UnsupportedOperationException("系统工具未实现")
    fun setStopped(stopped: Boolean)
    /** Transport uncertainty blocks new input without changing the user's STOP state. */
    fun quarantineInput(reason: String) { setStopped(true) }
    fun setManual(enabled: Boolean, epoch: Long)
    fun attachPreview(surface: Surface?, width: Int, height: Int)
    fun touch(action: Int, x: Float, y: Float, epoch: Long)
    fun close()
}
