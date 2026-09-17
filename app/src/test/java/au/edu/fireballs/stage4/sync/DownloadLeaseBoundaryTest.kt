package au.edu.fireballs.stage4.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DownloadLeaseBoundaryTest {
    @Test
    fun orchestratorDeclaresOneOuterCoordinatorLease() {
        val source =
            Files.readString(
                projectFile(
                    "app/src/main/java/au/edu/fireballs/stage4/sync/" +
                        "PreDownloadOrchestrator.kt",
                ),
            )

        val leaseStart = source.indexOf("coordinator.withDownloadLease {")
        val delegatedCall = source.indexOf("runDownload(", leaseStart)

        assertTrue(leaseStart >= 0)
        assertTrue(delegatedCall > leaseStart)
        assertEquals(1, source.split("withDownloadLease").size - 1)
    }

    private fun projectFile(relativePath: String) =
        generateSequence(System.getProperty("user.dir")) { current ->
            java.io.File(current).parent
        }.map { java.io.File(it, relativePath).toPath() }
            .first { Files.isRegularFile(it) }
}
