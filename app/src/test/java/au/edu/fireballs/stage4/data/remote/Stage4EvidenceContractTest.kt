package au.edu.fireballs.stage4.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class Stage4EvidenceContractTest : Stage4FixtureContractTest() {
    @Test
    fun `evidence upload returns 201`() =
        runTest(testDispatcher) {
            val contract = loader.load("evidence-upload-201")
            harness.enqueue(contract)
            val response = evidenceService.uploadEvidence("1", filePart(), irId())
            assertEquals(201, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `evidence upload malformed returns 400`() =
        runTest(testDispatcher) {
            val contract = loader.load("evidence-upload-malformed")
            harness.enqueue(contract)
            val response = evidenceService.uploadEvidence("1", filePart(), irId())
            assertEquals(400, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `evidence list`() =
        runTest(testDispatcher) {
            val contract = loader.load("evidence-list")
            harness.enqueue(contract)
            val response = evidenceService.listEvidencePhotos("1", "1")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val body = response.body()
            assertNotNull("Expected parseable EvidenceList for evidence-list", body)
            assertEquals(1, body?.photos?.size)
        }

    @Test
    fun `evidence view`() =
        runTest(testDispatcher) {
            val contract = loader.load("evidence-view")
            harness.enqueue(contract)
            val response = evidenceService.viewEvidencePhoto(1)
            assertEquals(200, response.code())
            harness.assertRequest(contract)
        }

    private fun filePart(): MultipartBody.Part {
        val file = File.createTempFile("evidence", ".jpg")
        val part =
            MultipartBody.Part.createFormData(
                "file",
                "evidence.jpg",
                file.readBytes().toRequestBody("image/jpeg".toMediaType()),
            )
        file.delete()
        return part
    }

    private fun irId(): okhttp3.RequestBody = "1".toRequestBody("text/plain".toMediaType())
}
