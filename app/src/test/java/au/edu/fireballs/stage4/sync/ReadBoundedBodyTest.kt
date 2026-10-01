package au.edu.fireballs.stage4.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBoundedBodyTest {
    @Test
    fun crossingChunkContributesToSampleBeforeStreamingBodyIsRejected() =
        runTest {
            val maximum = PreDownloadOrchestrator.MAX_TILE_BYTES
            val actualBytes = maximum + 1
            val body = controlledBody(maximum.toLong(), ByteArray(actualBytes))
            var accumulatedBytes = 0L
            var sampledBytesPerSecond: Long? = null

            val result =
                readBoundedBody(body, maximum) { bytesRead ->
                    accumulatedBytes += bytesRead
                    if (accumulatedBytes > maximum) {
                        sampledBytesPerSecond =
                            bytesPerSecond(accumulatedBytes, 1_000L)
                    }
                }

            assertNull(result)
            assertEquals(actualBytes.toLong(), accumulatedBytes)
            assertEquals(actualBytes.toLong(), sampledBytesPerSecond)
        }

    @Test
    fun cancellationDuringReadCallbackPropagatesAndStopsFurtherProgress() =
        runTest {
            val enteredProgress = CompletableDeferred<Unit>()
            var progressEmissions = 0
            val body =
                controlledBody(
                    declaredLength = 16_384L,
                    bytes = ByteArray(16_384),
                )
            val read =
                async {
                    readBoundedBody(body, 16_384) {
                        progressEmissions++
                        enteredProgress.complete(Unit)
                        awaitCancellation()
                    }
                }

            enteredProgress.await()
            read.cancel()

            var cancellationPropagated = false
            try {
                read.await()
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }

            assertTrue(cancellationPropagated)
            assertEquals(1, progressEmissions)
        }

    private fun controlledBody(
        declaredLength: Long,
        bytes: ByteArray,
    ): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = declaredLength

            override fun source(): BufferedSource = Buffer().write(bytes)
        }
}
