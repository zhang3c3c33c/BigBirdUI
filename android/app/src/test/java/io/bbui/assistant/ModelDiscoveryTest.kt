package io.bbui.assistant

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test


class ModelDiscoveryTest {
    private fun server(handler: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response, test: (String) -> Unit) {
        val server = object : NanoHTTPD("127.0.0.1", 0) { override fun serve(session: IHTTPSession): Response = handler(session) }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try { test("http://127.0.0.1:${server.listeningPort}") } finally { server.stop() }
    }
    private fun reply(status: Int = 200, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(status), "application/json", body)
    @Test fun bothOpenAiProtocolsShareModelsEndpointAndDeduplicateIds() {
        val calls = mutableListOf<String>()
        server({ request ->
            calls.add("${request.method} ${request.uri}${request.queryParameterString?.let { "?$it" } ?: ""} ${request.headers["authorization"]}")
            reply(body = """{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}""")
        }) { base ->
            for (api in listOf("openai-completions", "openai-responses")) {
                val models = ModelDiscovery().use { it.fetch(api, "$base/v1/", "fixture") }
                assertEquals(listOf("a", "b"), models.map { it.id })
            }
        }
        assertEquals(listOf("GET /v1/models Bearer fixture", "GET /v1/models Bearer fixture"), calls)
    }
    @Test fun anthropicPaginatesWithItsOwnAuthenticationAndName() {
        val calls = mutableListOf<String>()
        server({ request ->
            calls.add("${request.uri}${request.queryParameterString?.let { "?$it" } ?: ""} ${request.headers["x-api-key"]} ${request.headers["anthropic-version"]}")
            reply(body = if (request.queryParameterString?.contains("after_id") == true) """{"data":[{"id":"b","display_name":"Model B"}],"has_more":false}"""
                else """{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}""")
        }) { base ->
            val models = ModelDiscovery().use { it.fetch("anthropic-messages", base, "fixture") }
            assertEquals(listOf("a", "b"), models.map { it.id }); assertEquals("Model B", models[1].name)
        }
        assertEquals(listOf("/v1/models?limit=1000 fixture 2023-06-01", "/v1/models?limit=1000&after_id=a fixture 2023-06-01"), calls)
    }
    @Test fun noRedirectCredentialsOrRawErrors() {
        var count = 0
        server({ request ->
            count++; reply(302, "credential-echo").apply { addHeader("Location", "/different-host") }
        }) { base ->
            val error = runCatching { ModelDiscovery().use { it.fetch("openai-completions", base, "credential-echo") } }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("credential-echo")); assertEquals(1, count)
        }
    }
    @Test fun invalidRepeatedCursorFailsInsteadOfLooping() {
        var count = 0
        server({ request -> count++; reply(body = """{"data":[],"has_more":true,"last_id":"same"}""") }) { base ->
            assertTrue(runCatching { ModelDiscovery().use { it.fetch("anthropic-messages", base, "fixture") } }.isFailure)
        }
        assertEquals(2, count)
    }
    @Test fun unsupportedListingHasManualFallbackAndNoRawProviderResponse() {
        server({ request -> reply(404, "private-provider-body") }) { base ->
            val message = runCatching { ModelDiscovery().use { it.fetch("openai-responses", base, "fixture") } }.exceptionOrNull()!!.message!!
            assertTrue(message.contains("手动添加")); assertFalse(message.contains("private-provider"))
        }
    }
    @Test fun basePathsArePreservedAndUnsafeUriComponentsRejected() {
        assertEquals("https://api.deepseek.com/models", ModelDiscovery.endpoint("openai-completions", "https://api.deepseek.com"))
        assertEquals("https://host/proxy/v1/models", ModelDiscovery.endpoint("anthropic-messages", "https://host/proxy/v1/"))
        assertTrue(runCatching { ModelDiscovery.endpoint("openai-responses", "https://user:pass@host/v1") }.isFailure)
        assertTrue(runCatching { ModelDiscovery.endpoint("openai-responses", "https://host/v1?key=secret") }.isFailure)
    }
}
