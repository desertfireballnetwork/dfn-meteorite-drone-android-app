package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.remote.dto.ClaimRequestDto
import au.edu.fireballs.stage4.data.remote.dto.ReleaseRequestDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class Stage4ClaimReleaseContractTest : Stage4FixtureContractTest() {
    @Test
    fun `claim ok`() =
        runTest(testDispatcher) {
            val contract = loader.load("claim-ok")
            harness.enqueue(contract)
            val result = stage4Service.claimCandidate("1", ClaimRequestDto(listOf(1)))
            assertEquals(listOf(1L), result.claimed)
            harness.assertRequest(contract)
        }

    @Test
    fun `claim already claimed`() =
        runTest(testDispatcher) {
            val contract = loader.load("claim-already-claimed")
            harness.enqueue(contract)
            val result = stage4Service.claimCandidate("1", ClaimRequestDto(listOf(1)))
            assertEquals(listOf(1L), result.alreadyClaimed)
            harness.assertRequest(contract)
        }

    @Test
    fun `claim ineligible returns 403`() =
        runTest(testDispatcher) {
            val contract = loader.load("claim-ineligible")
            harness.enqueue(contract)
            val error =
                runCatching { stage4Service.claimCandidate("1", ClaimRequestDto(listOf(1))) }
                    .exceptionOrNull()
            assertTrue("Expected HttpException but got $error", error is HttpException)
            assertEquals(403, (error as HttpException).code())
            harness.assertRequest(contract)
        }

    @Test
    fun `claim cross campaign returns 403`() =
        runTest(testDispatcher) {
            val contract = loader.load("claim-cross-campaign")
            harness.enqueue(contract)
            val error =
                runCatching { stage4Service.claimCandidate("1", ClaimRequestDto(listOf(6))) }
                    .exceptionOrNull()
            assertTrue("Expected HttpException but got $error", error is HttpException)
            assertEquals(403, (error as HttpException).code())
            harness.assertRequest(contract)
        }

    @Test
    fun `release ok`() =
        runTest(testDispatcher) {
            val contract = loader.load("release-ok")
            harness.enqueue(contract)
            val result = stage4Service.releaseCandidate("1", ReleaseRequestDto(listOf(1)))
            assertEquals(listOf(1L), result.released)
            harness.assertRequest(contract)
        }

    @Test
    fun `release old active claim`() =
        runTest(testDispatcher) {
            val contract = loader.load("release-old-active-claim")
            harness.enqueue(contract)
            val result = stage4Service.releaseCandidate("1", ReleaseRequestDto(listOf(1)))
            assertEquals(listOf(1L), result.released)
            harness.assertRequest(contract)
        }

    @Test
    fun `claims list`() =
        runTest(testDispatcher) {
            val contract = loader.load("claims-list")
            harness.enqueue(contract)
            val response = stage4Service.getClaims("1")
            assertNotNull(response.claims)
            assertEquals(2, response.claims.size)
            assertEquals(1L, response.claims[0].inferenceResultId)
            assertEquals(2L, response.claims[1].inferenceResultId)
            harness.assertRequest(contract)
        }
}
