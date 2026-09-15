package au.edu.fireballs.stage4.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Stage4MiscContractTest : Stage4FixtureContractTest() {
    @Test
    fun `surveys index`() =
        runTest(testDispatcher) {
            val contract = loader.load("surveys-index")
            harness.enqueue(contract)
            val response = stage4Service.getSurveys()
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val body = response.body()
            assertNotNull("Expected parseable SurveyList for surveys-index", body)
            val survey = body?.surveys?.single()
            assertEquals(1L, survey?.id)
            assertEquals(true, survey?.hasStage4)
        }

    @Test
    fun `base set`() =
        runTest(testDispatcher) {
            val contract = loader.load("base-set")
            harness.enqueue(contract)
            val response = stage4Service.setCarLocation("1", "-31.5", "116.0")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `base get returns 404`() =
        runTest(testDispatcher) {
            val contract = loader.load("base-get")
            val response = executeRaw(contract)
            assertEquals(404, response.code)
            harness.assertRequest(contract)
        }

    @Test
    fun `csv export`() =
        runTest(testDispatcher) {
            val contract = loader.load("csv")
            val response = executeRaw(contract)
            assertEquals(200, response.code)
            harness.assertRequest(contract)
        }

    @Test
    fun `recovery release`() =
        runTest(testDispatcher) {
            val contract = loader.load("recovery-release")
            val response = executeRaw(contract)
            assertEquals(200, response.code)
            harness.assertRequest(contract)
        }

    @Test
    fun `recovery release by claimant`() =
        runTest(testDispatcher) {
            val contract = loader.load("recovery-release-by-claimant")
            val response = executeRaw(contract)
            assertEquals(200, response.code)
            harness.assertRequest(contract)
        }

    @Test
    fun `recovery release all`() =
        runTest(testDispatcher) {
            val contract = loader.load("recovery-release-all")
            val response = executeRaw(contract)
            assertEquals(200, response.code)
            harness.assertRequest(contract)
        }

    @Test
    fun `recovery audit`() =
        runTest(testDispatcher) {
            val contract = loader.load("recovery-audit")
            val response = executeRaw(contract)
            assertEquals(200, response.code)
            harness.assertRequest(contract)
        }
}
