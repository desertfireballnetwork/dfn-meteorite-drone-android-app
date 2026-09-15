package au.edu.fireballs.stage4.data.remote.fixture

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class MockServerContract(
    private val server: MockWebServer,
) {
    fun enqueue(contract: FixtureContract) {
        val response =
            MockResponse()
                .setResponseCode(contract.expectedStatus)
                .setHeader("Content-Type", contract.expectedContentType)
                .setBody(contract.expectedBody)
        server.enqueue(response)
    }

    fun assertRequest(contract: FixtureContract) {
        val recorded = server.takeRequest()
        assertEquals(contract.method, recorded.method)
        assertEquals(contract.path, recorded.path?.substringBefore("?")?.removePrefix("/"))
        assertQueryParams(contract, recorded)
        assertContentType(contract, recorded)
        assertBody(contract, recorded)
    }

    private fun assertQueryParams(
        contract: FixtureContract,
        recorded: RecordedRequest,
    ) {
        val actual = recorded.requestUrl?.queryParameterNames ?: emptySet()
        assertEquals(contract.queryParams.keys, actual)
        for ((key, value) in contract.queryParams) {
            assertEquals(value, recorded.requestUrl?.queryParameter(key))
        }
    }

    private fun assertContentType(
        contract: FixtureContract,
        recorded: RecordedRequest,
    ) {
        val expected = contract.requestContentType ?: return
        val actual = (recorded.getHeader("Content-Type") ?: "").substringBefore(";")
        if (expected == "multipart/form-data") {
            assertTrue(
                "Expected multipart content type but was $actual",
                actual.startsWith("multipart/form-data"),
            )
        } else {
            assertEquals(expected, actual)
        }
    }

    private fun assertBody(
        contract: FixtureContract,
        recorded: RecordedRequest,
    ) {
        when (contract.requestContentType) {
            "application/json" -> assertJsonBody(contract, recorded)
            "application/x-www-form-urlencoded" -> assertFormBody(contract, recorded)
            "multipart/form-data" -> assertMultipartBody(contract, recorded)
        }
    }

    private fun assertJsonBody(
        contract: FixtureContract,
        recorded: RecordedRequest,
    ) {
        val expected = contract.requestBody as? Map<*, *> ?: return
        val actual = JsonParser.parse(recorded.body.readUtf8())
        assertEquals(expected, actual)
    }

    private fun assertFormBody(
        contract: FixtureContract,
        recorded: RecordedRequest,
    ) {
        val expected = contract.requestBody as? Map<*, *> ?: return
        val expectedMap = expected.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
        val actualMap = parseForm(recorded.body.readUtf8())
        assertEquals(expectedMap, actualMap)
    }

    private fun assertMultipartBody(
        contract: FixtureContract,
        recorded: RecordedRequest,
    ) {
        val body = recorded.body.readUtf8()
        val requestBody = contract.requestBody as? Map<*, *> ?: return
        val file = requestBody["file"] as? Map<*, *>
        if (file != null) {
            assertTrue("Expected file part", body.contains("name=\"file\""))
            assertTrue(
                "Expected filename ${file["filename"]}",
                body.contains(file["filename"].toString()),
            )
        }
        if (requestBody.containsKey("inference_result_id")) {
            assertTrue(
                "Expected inference_result_id part",
                body.contains("name=\"inference_result_id\""),
            )
            assertTrue(
                "Expected inference_result_id value",
                body.contains(requestBody["inference_result_id"].toString()),
            )
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        body.split("&").forEach { pair ->
            if (pair.isNotEmpty()) {
                val parts = pair.split("=", limit = 2)
                val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                val value =
                    if (parts.size >
                        1
                    ) {
                        java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        ""
                    }
                result[key] = value
            }
        }
        return result
    }
}
