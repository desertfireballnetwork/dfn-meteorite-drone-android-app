package au.edu.fireballs.stage4.data.remote.fixture

import com.squareup.moshi.Moshi

data class FixtureContract(
    val name: String,
    val method: String,
    val path: String,
    val queryParams: Map<String, String>,
    val requestBody: Any?,
    val requestContentType: String?,
    val expectedStatus: Int,
    val expectedContentType: String,
    val expectedBody: String,
)

class FixtureLoader {
    private val moshi = Moshi.Builder().build()
    private val anyAdapter = moshi.adapter(Any::class.java)

    fun load(name: String): FixtureContract {
        val resource =
            javaClass.classLoader.getResource("fixtures/android/$name.json")
                ?: error("Fixture not found: $name")
        val json = resource.openStream().bufferedReader().use { it.readText() }
        val root = JsonParser.parse(json) as Map<*, *>
        val request = root["request"] as Map<*, *>
        val expected = root["expected"] as Map<*, *>
        val urlParams = request["url_params"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val endpoint = root["endpoint"] as String

        val path = buildPath(endpoint, urlParams)
        val queryParams = request["query_params"] as? Map<*, *> ?: emptyMap<Any, Any>()

        val requestBody = resolveValue(null, request["body"])
        val requestContentType = request["content_type"] as? String

        val expectedStatus = (expected["status"] as Number).toInt()
        val expectedContentType = expected["content_type"] as String
        val resolvedExpected = resolveValue(null, expected["body"])
        val expectedBody = renderExpectedBody(resolvedExpected)

        return FixtureContract(
            name = root["name"] as String,
            method = root["method"] as String,
            path = path,
            queryParams =
                queryParams
                    .mapKeys {
                        it.key.toString()
                    }.mapValues { it.value.toString() },
            requestBody = requestBody,
            requestContentType = requestContentType,
            expectedStatus = expectedStatus,
            expectedContentType = expectedContentType,
            expectedBody = expectedBody,
        )
    }

    private fun buildPath(
        endpoint: String,
        urlParams: Map<*, *>,
    ): String {
        val template = PATH_TEMPLATES[endpoint] ?: error("No path template for endpoint $endpoint")
        var path = template
        for ((key, value) in urlParams) {
            path = path.replace("{$key}", value.toString())
        }
        return path
    }

    private fun renderExpectedBody(body: Any?): String =
        when (body) {
            is Map<*, *> ->
                if (body.containsKey("_html_error") || body.containsKey("_stream")) {
                    ""
                } else {
                    anyAdapter.toJson(body)
                }
            is String -> body
            else -> anyAdapter.toJson(body)
        }

    private fun resolveValue(
        key: String?,
        value: Any?,
    ): Any? =
        when (value) {
            is Map<*, *> ->
                value.mapValues { (innerKey, innerValue) ->
                    resolveValue(innerKey as String, innerValue)
                }
            is List<*> -> value.map { resolveValue(key, it) }
            is String -> resolveString(key, value)
            else -> value
        }

    private fun resolveString(
        key: String?,
        value: String,
    ): Any? {
        if (value == "@dynamic") {
            return when {
                key == "detection_tags" -> emptyList<Any>()
                key == "released_claim_episode_ids" -> listOf(1L)
                key == "latest_task_created" || key == "created" || key == "claimed_at" ->
                    "2026-08-20T09:30:00Z"
                key?.endsWith("_id") == true || key == "id" -> 1L
                else -> error("Unhandled @dynamic key: $key")
            }
        }
        if (value.contains("@user:")) {
            val username = value.substringAfter("@user:").substringBefore(" ")
            return value.replace("@user:$username", "1")
        }
        if (value.contains("@survey:")) {
            val surveyId = value.substringAfter("@survey:").substringBefore(" ")
            return value.replace("@survey:$surveyId", "1")
        }
        return value
    }

    companion object {
        private val PATH_TEMPLATES =
            mapOf(
                "surveys_index" to "api/surveys/",
                "candidates" to "api/stage4/surveys/{survey}/candidates/",
                "claim" to "api/stage4/surveys/{survey}/claims/claim/",
                "release" to "api/stage4/surveys/{survey}/claims/release/",
                "claims_list" to "api/stage4/surveys/{survey}/claims/",
                "verdict" to "survey/{survey}/stage4/response/",
                "base" to "survey/{survey}/stage4/set_car_location/",
                "evidence_upload" to "api/stage4/surveys/{survey}/evidence/",
                "evidence_list" to
                    "api/stage4/surveys/{survey}/candidates/{inference_result_id}/evidence/",
                "evidence_view" to "api/stage4/evidence/{photo_id}/",
                "csv" to "survey/{survey}/stage4_metadata/",
                "recovery_release" to "api/stage4/surveys/{survey}/recovery/release/",
                "recovery_release_by_claimant" to
                    "api/stage4/surveys/{survey}/recovery/release-by-claimant/",
                "recovery_release_all" to "api/stage4/surveys/{survey}/recovery/release-all/",
                "recovery_audit" to "api/stage4/surveys/{survey}/recovery/audit/",
            )
    }
}
