package au.edu.fireballs.stage4.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimDtosTest {
    private val moshi =
        Moshi
            .Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    @Test
    fun claimRequestSerializesToBatchBody() {
        val json =
            moshi
                .adapter(ClaimRequestDto::class.java)
                .toJson(ClaimRequestDto(listOf(1, 2, 3)))
        assertEquals("""{"inference_result_ids":[1,2,3]}""", json)
    }

    @Test
    fun releaseRequestSerializesToBatchBody() {
        val json =
            moshi
                .adapter(ReleaseRequestDto::class.java)
                .toJson(ReleaseRequestDto(listOf(5)))
        assertEquals("""{"inference_result_ids":[5]}""", json)
    }

    @Test
    fun claimResponseDeserializesFromBatchShape() {
        val dto =
            moshi
                .adapter(ClaimResponseDto::class.java)
                .fromJson("""{"claimed":[1],"already_claimed":[2]}""")
        assertEquals(listOf(1), dto?.claimed)
        assertEquals(listOf(2), dto?.alreadyClaimed)
    }

    @Test
    fun releaseResponseDeserializesFromBatchShape() {
        val dto =
            moshi
                .adapter(ReleaseResponseDto::class.java)
                .fromJson("""{"released":[3]}""")
        assertEquals(listOf(3), dto?.released)
    }

    @Test
    fun listClaimsResponseDeserializesFromWebappShape() {
        val dto =
            moshi
                .adapter(ListClaimsResponseDto::class.java)
                .fromJson(
                    """{"claims":[{"inference_result_id":1,"user_id":2,"username":"u","full_name":"U","claimed_at":"2026-01-01T00:00:00Z","is_me":true}]}""",
                )
        val claim = dto?.claims?.single()
        assertEquals(1L, claim?.inferenceResultId)
        assertEquals(2L, claim?.userId)
        assertEquals("u", claim?.username)
        assertEquals("U", claim?.fullName)
        assertEquals(true, claim?.isMe)
    }
}
