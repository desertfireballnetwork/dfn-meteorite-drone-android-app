package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.remote.dto.Stage4StateDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Stage4CandidatesContractTest : Stage4FixtureContractTest() {
    @Test
    fun `candidates root`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-root")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("1")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val state =
                moshi
                    .adapter(Stage4StateDto::class.java)
                    .fromJson(response.body()?.string())
            assertNotNull("Expected parseable Stage4State for candidates-root", state)
            assertEquals(3, state?.unprocessedCandidates?.size)
        }

    @Test
    fun `candidates child`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-child")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("2")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val state =
                moshi
                    .adapter(Stage4StateDto::class.java)
                    .fromJson(response.body()?.string())
            assertNotNull("Expected parseable Stage4State for candidates-child", state)
            assertEquals(3, state?.unprocessedCandidates?.size)
        }

    @Test
    fun `candidates grandchild`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-grandchild")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("3")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val state =
                moshi
                    .adapter(Stage4StateDto::class.java)
                    .fromJson(response.body()?.string())
            assertNotNull("Expected parseable Stage4State for candidates-grandchild", state)
            assertEquals(3, state?.unprocessedCandidates?.size)
        }

    @Test
    fun `candidates parent permission`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-parent-permission")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("1")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val state =
                moshi
                    .adapter(Stage4StateDto::class.java)
                    .fromJson(response.body()?.string())
            assertNotNull("Expected parseable Stage4State for candidates-parent-permission", state)
            assertEquals(1, state?.unprocessedCandidates?.size)
        }

    @Test
    fun `candidates child permission returns 403`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-child-permission")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("2")
            assertEquals(403, response.code())
            harness.assertRequest(contract)
        }

    @Test
    fun `candidates ineligible`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-ineligible")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("1")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val state =
                moshi
                    .adapter(Stage4StateDto::class.java)
                    .fromJson(response.body()?.string())
            assertNotNull("Expected parseable Stage4State for candidates-ineligible", state)
            assertEquals(1, state?.unprocessedCandidates?.size)
        }

    @Test
    fun `candidates stale task`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-stale-task")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("1")
            assertEquals(200, response.code())
            harness.assertRequest(contract)
            val state =
                moshi
                    .adapter(Stage4StateDto::class.java)
                    .fromJson(response.body()?.string())
            assertNotNull("Expected parseable Stage4State for candidates-stale-task", state)
            assertEquals(1, state?.unprocessedCandidates?.size)
        }

    @Test
    fun `candidates unrelated returns 403`() =
        runTest(testDispatcher) {
            val contract = loader.load("candidates-unrelated")
            harness.enqueue(contract)
            val response = stage4Service.getCandidates("5")
            assertEquals(403, response.code())
            harness.assertRequest(contract)
        }
}
