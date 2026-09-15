package au.edu.fireballs.stage4.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceDtosTest {
    private val moshi =
        Moshi
            .Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    @Test
    fun emptyListDeserializesFromEmptyPhotos() {
        val dto =
            moshi
                .adapter(EvidenceListResponseDto::class.java)
                .fromJson("""{"photos":[]}""")
        assertEquals(emptyList<EvidencePhotoDto>(), dto?.photos)
    }

    @Test
    fun singlePhotoDeserializesFromWebappShape() {
        val dto =
            moshi
                .adapter(EvidenceListResponseDto::class.java)
                .fromJson(
                    """{"photos":[{"id":123,"captured_at":"2026-08-27T09:00:00Z",""" +
                        """"created":"2026-08-27T09:01:00Z","user_id":42,""" +
                        """"username":"operator"}]}""",
                )
        val photo = dto?.photos?.single()
        assertEquals(123L, photo?.id)
        assertEquals("2026-08-27T09:00:00Z", photo?.capturedAt)
        assertEquals("2026-08-27T09:01:00Z", photo?.created)
        assertEquals(42L, photo?.userId)
        assertEquals("operator", photo?.username)
    }

    @Test
    fun multiplePhotosDeserializePreservingOrder() {
        val dto =
            moshi
                .adapter(EvidenceListResponseDto::class.java)
                .fromJson(
                    """{"photos":[{"id":1,"created":"2026-08-27T09:00:00Z",""" +
                        """"user_id":1,"username":"a"},{"id":2,"created":""" +
                        """"2026-08-27T09:01:00Z","user_id":2,"username":"b"}]}""",
                )
        assertEquals(listOf(1L, 2L), dto?.photos?.map { it.id })
        assertEquals(listOf("a", "b"), dto?.photos?.map { it.username })
    }

    @Test
    fun nullCapturedAtDeserializesAsNull() {
        val dto =
            moshi
                .adapter(EvidencePhotoDto::class.java)
                .fromJson(
                    """{"id":7,"captured_at":null,"created":"2026-08-27T09:01:00Z",""" +
                        """"user_id":9,"username":"u"}""",
                )
        assertNull(dto?.capturedAt)
        assertEquals(7L, dto?.id)
    }
}
