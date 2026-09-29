package io.bbui.core

import org.json.JSONObject

/** One runtime per process. Callbacks may arrive on background threads. */
interface AgentRuntime {
    fun start(config: JSONObject, onEvent: (JSONObject) -> Unit, onFailure: (String) -> Unit)
    fun send(command: JSONObject)
    fun close()
}
