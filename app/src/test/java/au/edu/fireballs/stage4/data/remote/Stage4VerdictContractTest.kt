package au.edu.fireballs.stage4.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage4VerdictContractTest : Stage4FixtureContractTest() {
    @Test
    fun `verdict first returns 200`() =
        runTest(testDispatcher) {
            val contract = loader.load("verdict-first-200")
            harness.enqueue(contract)
            val response = stage4Service.postStage4Response("1", "1", "true", null)
            assertEquals(200, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `verdict claim required returns 403`() =
        runTest(testDispatcher) {
            val contract = loader.load("verdict-claim-required-403")
            harness.enqueue(contract)
            val response = stage4Service.postStage4Response("1", "1", "true", null)
            assertEquals(403, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `verdict already completed returns 409`() =
        runTest(testDispatcher) {
            val contract = loader.load("verdict-already-completed-409")
            harness.enqueue(contract)
            val response = stage4Service.postStage4Response("1", "1", "true", null)
            assertEquals(409, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `verdict idempotent retry returns 409`() =
        runTest(testDispatcher) {
            val contract = loader.load("verdict-idempotent-retry")
            harness.enqueue(contract)
            val response = stage4Service.postStage4Response("1", "1", "true", null)
            assertEquals(409, response.code())
            harness.assertRequest(contract)
        }
}
