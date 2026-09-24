package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetKey
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.bindgen.None
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionError
import com.mapbox.maps.OfflineRegionErrorType
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class OfflineRegionWrapperTest {
    private val region = mock(OfflineRegionHandle::class.java)
    private val source = FakeSource()
    private val wrapper =
        OfflineRegionWrapper(
            source = source,
            mainHandler = Handler(Looper.getMainLooper()),
            definitionFactory = { _, _, _ -> mock(OfflineRegionTilePyramidDefinition::class.java) },
        )

    @Test
    fun cancelPurgesActiveRegionBeforeCompleting() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        idleMain()

        wrapper.cancelDownload()
        verify(region).setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)

        val purgeCaptor = argumentCaptor<AsyncOperationResultCallback>()
        verify(region).purge(purgeCaptor.capture())
        purgeCaptor.firstValue.run(ExpectedFactory.createNone())
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().exceptionOrNull() is CancellationException)
    }

    @Test
    fun cancelBeforeCreatePurgesLateRegion() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )

        wrapper.cancelDownload()
        idleMain()
        assertEquals(1, completions.size)
        assertTrue(completions.single().exceptionOrNull() is CancellationException)

        source.createCallback(Result.success(region))
        idleMain()

        verify(region, never()).setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
        verify(region).purge(any())
        assertEquals(1, completions.size)
    }

    @Test
    fun completionThenErrorEmitsExactlyOneTerminalResult() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        observer.statusChanged(completeStatus())
        val error = mock(OfflineRegionError::class.java)
        `when`(error.message).thenReturn("boom")
        observer.errorOccurred(error)
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
    }

    @Test
    fun errorThenCompletionEmitsExactlyOneTerminalResult() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        val error = mock(OfflineRegionError::class.java)
        `when`(error.message).thenReturn("boom")
        observer.errorOccurred(error)
        observer.statusChanged(completeStatus())
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
    }

    @Test
    fun errorOccurredSurfacesTypeAndMessage() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        val error = mock(OfflineRegionError::class.java)
        `when`(error.type).thenReturn(OfflineRegionErrorType.CONNECTION)
        `when`(error.message).thenReturn("boom")
        observer.errorOccurred(error)
        idleMain()

        val message = completions.single().exceptionOrNull()?.message
        assertTrue(
            "Expected type in message but got $message",
            message?.contains("CONNECTION") == true,
        )
        assertTrue(
            "Expected cause in message but got $message",
            message?.contains("boom") == true,
        )
    }

    @Test
    fun reportsActualResourceCountOnce() {
        val counts = mutableListOf<Long>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = { counts.add(it) },
            progressCb = {},
            completionCb = {},
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        observer.statusChanged(completeStatus())
        observer.statusChanged(completeStatus())
        idleMain()

        assertEquals(listOf(10L), counts)
    }

    @Test
    fun inventoryListsRegionsOnceAndReturnsUnsupportedBytesAsNull() {
        source.regions = listOf(region, mock(OfflineRegionHandle::class.java))
        val inventories = mutableListOf<Result<OfflineRegionInventory>>()

        wrapper.inventory { inventories.add(it) }
        idleMain()

        assertEquals(1, source.getRegionsCalls)
        assertEquals(2, inventories.single().getOrThrow().regionCount)
        assertEquals(null, inventories.single().getOrThrow().measuredBytes)
    }

    @Test
    fun retainExactRetainsOnlyExactStage4Target() {
        val retained = target(1L, "v1", "sat:alpha")
        val missing = target(1L, "v1", "sat:beta")
        source.regions = listOf(FakeRegionHandle(metadata = encodeOfflineRegionTarget(retained)))
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(retained, missing)) { results.add(it) }
        idleMain()

        assertEquals(
            listOf(
                OfflineRegionRetention.Retained(retained),
                OfflineRegionRetention.Missing(missing),
            ),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun retainExactTreatsEveryIdentityMismatchAsMissing() {
        val reference = target(1L, "v1", "sat:alpha")
        source.regions = listOf(FakeRegionHandle(metadata = encodeOfflineRegionTarget(reference)))
        val mismatches =
            listOf(
                target(2L, "v1", "sat:alpha"),
                target(1L, "v2", "sat:alpha"),
                target(1L, "v1", "sat:gamma"),
            )
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(mismatches) { results.add(it) }
        idleMain()

        assertTrue(results.single().getOrThrow().all { it is OfflineRegionRetention.Missing })
    }

    @Test
    fun retainExactIgnoresRegionsWithoutStage4Metadata() {
        val expected = target(1L, "v1", "sat:alpha")
        source.regions = listOf(FakeRegionHandle(metadata = null))
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()

        assertEquals(
            listOf(OfflineRegionRetention.Missing(expected)),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun retainExactEmptyTargetsCompletesOnceWithEmptyResult() {
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(emptyList()) { results.add(it) }
        idleMain()

        assertEquals(1, results.size)
        assertEquals(emptyList<OfflineRegionRetention>(), results.single().getOrThrow())
        assertEquals(0, source.getRegionsCalls)
    }

    @Test
    fun retainExactDeduplicatesRepeatedCallbackFromOneHandle() {
        val expected = target(1L, "v1", "sat:duplicate-callback")
        val repeated =
            FakeRegionHandle(
                metadata = encodeOfflineRegionTarget(expected),
                statusResult = Result.success(status(completed = 0)),
                immediateCallbackCount = 2,
            )
        val complete =
            FakeRegionHandle(
                metadata = encodeOfflineRegionTarget(expected),
                statusResult = Result.success(status()),
            )
        source.regions = listOf(repeated, complete)
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()

        assertEquals(1, results.size)
        assertEquals(
            listOf(OfflineRegionRetention.Retained(expected)),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun retainExactRetainsCompletePrecisePositiveStatus() {
        val expected = target(1L, "v1", "sat:complete")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status()),
                ),
            )
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()

        assertEquals(
            listOf(OfflineRegionRetention.Retained(expected)),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun retainExactTreatsIncompleteStatusAsMissing() {
        assertStatusIsMissing(status(completed = 9, required = 10))
    }

    @Test
    fun retainExactTreatsImpreciseStatusAsMissing() {
        assertStatusIsMissing(status(precise = false))
    }

    @Test
    fun retainExactTreatsStatusFailureAsMissing() {
        val expected = target(1L, "v1", "sat:failure")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.failure(IllegalStateException("status failed")),
                ),
            )

        assertRetentionIsMissing(expected)
    }

    @Test
    fun retainExactTreatsAbsentStatusAsMissing() {
        val expected = target(1L, "v1", "sat:absent")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult =
                        Result.failure(
                            IllegalStateException("Mapbox returned no offline region status"),
                        ),
                ),
            )

        assertRetentionIsMissing(expected)
    }

    @Test
    fun retainExactTreatsZeroRequiredResourcesAsMissing() {
        assertStatusIsMissing(status(completed = 0, required = 0))
    }

    @Test
    fun retainExactTreatsNoOwnedRegionAsMissing() {
        val expected = target(1L, "v1", "sat:none")
        source.regions = emptyList()

        assertRetentionIsMissing(expected)
    }

    @Test
    fun retainExactRetainsWhenOneDuplicateIsComplete() {
        val expected = target(1L, "v1", "sat:duplicate-complete")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status(completed = 9)),
                ),
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status()),
                ),
            )
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()

        assertEquals(
            listOf(OfflineRegionRetention.Retained(expected)),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun retainExactTreatsIncompleteDuplicatesAsMissing() {
        val expected = target(1L, "v1", "sat:duplicate-missing")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status(completed = 8)),
                ),
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status(completed = 9)),
                ),
            )

        assertRetentionIsMissing(expected)
    }

    @Test
    fun retainExactSynchronousCallbacksCompleteOuterCallbackOnce() {
        val expected = target(1L, "v1", "sat:synchronous")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status()),
                ),
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status()),
                ),
            )
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()

        assertEquals(1, results.size)
        assertEquals(
            listOf(OfflineRegionRetention.Retained(expected)),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun retainExactIgnoresOutstandingCallbacksAfterShortCircuit() {
        val expected = target(1L, "v1", "sat:delayed")
        val complete =
            FakeRegionHandle(
                metadata = encodeOfflineRegionTarget(expected),
                statusResult = Result.success(status()),
            )
        val delayed =
            FakeRegionHandle(
                metadata = encodeOfflineRegionTarget(expected),
                statusResult = Result.success(status(completed = 0)),
                captureStatusCallback = true,
            )
        source.regions = listOf(complete, delayed)
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()
        delayed.dispatchStatus()
        delayed.dispatchStatus()
        idleMain()

        assertEquals(1, results.size)
        assertEquals(
            listOf(OfflineRegionRetention.Retained(expected)),
            results.single().getOrThrow(),
        )
    }

    private fun assertStatusIsMissing(status: OfflineRegionStatus) {
        val expected = target(1L, "v1", "sat:status")
        source.regions =
            listOf(
                FakeRegionHandle(
                    metadata = encodeOfflineRegionTarget(expected),
                    statusResult = Result.success(status),
                ),
            )

        assertRetentionIsMissing(expected)
    }

    private fun assertRetentionIsMissing(expected: PreDownloadTargetKey.Satellite) {
        val results = mutableListOf<Result<List<OfflineRegionRetention>>>()

        wrapper.retainExact(listOf(expected)) { results.add(it) }
        idleMain()

        assertEquals(
            listOf(OfflineRegionRetention.Missing(expected)),
            results.single().getOrThrow(),
        )
    }

    @Test
    fun purgeExactPurgesOwnedExactRegion() {
        val expected = target(1L, "v1", "sat:alpha")
        val handle = FakeRegionHandle(metadata = encodeOfflineRegionTarget(expected))
        source.regions = listOf(handle)
        val results = mutableListOf<OfflineRegionPurgeResult>()

        wrapper.purgeExact(expected) { results.add(it) }
        idleMain()

        assertEquals(OfflineRegionPurgeResult.Purged(expected), results.single())
        assertEquals(1, handle.purgeCalls)
    }

    @Test
    fun purgeExactConfirmsAbsenceAndNeverPurgesMismatchedRegion() {
        val expected = target(1L, "v1", "sat:alpha")
        val otherTarget = target(2L, "v9", "sat:x")
        val other = FakeRegionHandle(metadata = encodeOfflineRegionTarget(otherTarget))
        source.regions = listOf(other)
        val results = mutableListOf<OfflineRegionPurgeResult>()

        wrapper.purgeExact(expected) { results.add(it) }
        idleMain()

        assertEquals(OfflineRegionPurgeResult.ConfirmedAbsent(expected), results.single())
        assertEquals(0, other.purgeCalls)
    }

    @Test
    fun purgeExactReportsTypedRetryableFailureWithoutRawSdkError() {
        val expected = target(1L, "v1", "sat:alpha")
        val handle =
            FakeRegionHandle(
                metadata = encodeOfflineRegionTarget(expected),
                purgeResult = ExpectedFactory.createError("raw-sdk-secret"),
            )
        source.regions = listOf(handle)
        val results = mutableListOf<OfflineRegionPurgeResult>()

        wrapper.purgeExact(expected) { results.add(it) }
        idleMain()

        val failure = results.single() as OfflineRegionPurgeResult.RetryableFailure
        assertEquals(expected, failure.target)
        assertEquals(OfflineRegionFailureCategory.PURGE_REJECTED, failure.category)
        assertTrue(failure.attemptTimeMillis > 0L)
        assertFalse(failure.toString().contains("raw-sdk-secret"))
    }

    @Test
    fun purgeExactReportsRetryableFailureWhenRegionsUnavailable() {
        val expected = target(1L, "v1", "sat:alpha")
        source.failure = IllegalStateException("raw-sdk-secret")
        val results = mutableListOf<OfflineRegionPurgeResult>()

        wrapper.purgeExact(expected) { results.add(it) }
        idleMain()

        val failure = results.single() as OfflineRegionPurgeResult.RetryableFailure
        assertEquals(OfflineRegionFailureCategory.REGION_LIST_FAILED, failure.category)
        assertTrue(failure.attemptTimeMillis > 0L)
        assertFalse(failure.toString().contains("raw-sdk-secret"))
    }

    @Test
    fun purgeExactRetrySucceedsAfterTransientFailure() {
        val expected = target(1L, "v1", "sat:alpha")
        val handle =
            FakeRegionHandle(
                metadata = encodeOfflineRegionTarget(expected),
                purgeResult = ExpectedFactory.createError("transient"),
            )
        source.regions = listOf(handle)
        val first = mutableListOf<OfflineRegionPurgeResult>()

        wrapper.purgeExact(expected) { first.add(it) }
        idleMain()
        assertTrue(first.single() is OfflineRegionPurgeResult.RetryableFailure)

        handle.purgeResult = ExpectedFactory.createNone()
        val second = mutableListOf<OfflineRegionPurgeResult>()
        wrapper.purgeExact(expected) { second.add(it) }
        idleMain()

        assertEquals(OfflineRegionPurgeResult.Purged(expected), second.single())
        assertEquals(2, handle.purgeCalls)
    }

    @Test
    fun downloadWithTargetStoresStage4OwnershipMetadata() {
        val expected = target(3L, "v2", "sat:cluster")
        val handle = FakeRegionHandle()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = {},
            target = expected,
        )

        source.createCallback(Result.success(handle))
        idleMain()

        assertEquals(expected, decodeOfflineRegionTarget(handle.metadata))
        assertTrue(handle.observer != null)
    }

    @Test
    fun downloadWithTargetPurgesWhenOwnershipMetadataCannotBeStored() {
        val expected = target(3L, "v2", "sat:cluster")
        val handle =
            FakeRegionHandle(metadataResult = ExpectedFactory.createError("raw-sdk-secret"))
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
            target = expected,
        )

        source.createCallback(Result.success(handle))
        idleMain()

        assertTrue(completions.single().isFailure)
        assertEquals(1, handle.purgeCalls)
    }

    private fun bbox(): Bbox = Bbox(-1.0, -1.0, 1.0, 1.0)

    private fun target(
        surveyId: Long,
        sourceVersion: String,
        signature: String,
    ): PreDownloadTargetKey.Satellite =
        PreDownloadTargetKey.Satellite(
            surveyId = surveyId,
            sourceVersion = sourceVersion,
            signature = signature,
        )

    private fun status(
        completed: Long = 10,
        required: Long = 10,
        precise: Boolean = true,
    ): OfflineRegionStatus =
        OfflineRegionStatus(
            OfflineRegionDownloadState.ACTIVE,
            completed,
            0,
            completed,
            0,
            required,
            required,
            precise,
        )

    private fun completeStatus(): OfflineRegionStatus = status()

    private fun captureObserver(): OfflineRegionObserver {
        val captor = argumentCaptor<OfflineRegionObserver>()
        verify(region).setOfflineRegionObserver(captor.capture())
        return captor.firstValue
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun definitionFactoryFailureCompletesAndAllowsRetry() {
        val throwing =
            OfflineRegionWrapper(
                source = source,
                mainHandler = Handler(Looper.getMainLooper()),
                definitionFactory = { _, _, _ -> throw IllegalStateException("factory boom") },
            )
        val first = mutableListOf<Result<Unit>>()
        throwing.downloadSatelliteRegion(bbox(), 0, 10, {}, {}, { first.add(it) })
        idleMain()
        assertEquals(1, first.size)
        assertTrue(first.single().isFailure)

        val second = mutableListOf<Result<Unit>>()
        throwing.downloadSatelliteRegion(bbox(), 0, 10, {}, {}, { second.add(it) })
        idleMain()
        assertEquals(1, second.size)
        assertTrue(second.single().isFailure)
    }

    private class FakeRegionHandle(
        override var metadata: ByteArray? = null,
        var purgeResult: Expected<String, None> = ExpectedFactory.createNone(),
        var metadataResult: Expected<String, None> = ExpectedFactory.createNone(),
        var statusResult: Result<OfflineRegionStatus> = Result.success(defaultStatus()),
        var captureStatusCallback: Boolean = false,
        var immediateCallbackCount: Int = 1,
    ) : OfflineRegionHandle {
        var purgeCalls = 0
        var observer: OfflineRegionObserver? = null
        val statusCallbacks =
            mutableListOf<(Result<OfflineRegionStatus>) -> Unit>()

        override val identifier: Long = 1L

        override fun setOfflineRegionObserver(observer: OfflineRegionObserver) {
            this.observer = observer
        }

        override fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState) = Unit

        override fun purge(callback: AsyncOperationResultCallback) {
            purgeCalls++
            callback.run(purgeResult)
        }

        override fun getStatus(callback: (Result<OfflineRegionStatus>) -> Unit) {
            if (captureStatusCallback) {
                statusCallbacks.add(callback)
            } else {
                repeat(immediateCallbackCount) {
                    callback(statusResult)
                }
            }
        }

        fun dispatchStatus() {
            statusCallbacks.toList().forEach { it(statusResult) }
        }

        override fun setMetadata(
            metadata: ByteArray,
            callback: AsyncOperationResultCallback,
        ) {
            if (!metadataResult.isError) {
                this.metadata = metadata
            }
            callback.run(metadataResult)
        }

        companion object {
            private fun defaultStatus(): OfflineRegionStatus =
                OfflineRegionStatus(
                    OfflineRegionDownloadState.ACTIVE,
                    10,
                    0,
                    10,
                    0,
                    10,
                    10,
                    true,
                )
        }
    }

    private class FakeSource : OfflineRegionSource {
        lateinit var createCallback: (Result<OfflineRegionHandle>) -> Unit
        var regions: List<OfflineRegionHandle> = emptyList()
        var failure: Throwable? = null
        var getRegionsCalls = 0

        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) {
            createCallback = callback
        }

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            getRegionsCalls++
            val error = failure
            callback(
                if (error == null) {
                    Result.success(regions)
                } else {
                    Result.failure(error)
                },
            )
        }
    }
}
