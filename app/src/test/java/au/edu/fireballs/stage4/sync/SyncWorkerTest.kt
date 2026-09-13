package au.edu.fireballs.stage4.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.DefaultWorkerFactory
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result
import androidx.work.ProgressUpdater
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `null selected survey returns success without running orchestrator`() =
        runTest {
            val selectedSurveyRepository = mock(SelectedSurveyRepository::class.java)
            `when`(selectedSurveyRepository.selectedSurveyId).thenReturn(flowOf<Long?>(null))
            val orchestrator = mock(SyncOrchestrator::class.java)

            val worker =
                SyncWorker(
                    context,
                    workerParams(),
                    selectedSurveyRepository,
                    orchestrator,
                )

            assertEquals(Result.success(), worker.doWork())
            verifyNoInteractions(orchestrator)
        }

    @Test
    fun `success outcome returns success`() =
        runTest {
            val selectedSurveyRepository = mock(SelectedSurveyRepository::class.java)
            `when`(selectedSurveyRepository.selectedSurveyId).thenReturn(flowOf(SURVEY_ID))
            val orchestrator = mock(SyncOrchestrator::class.java)
            `when`(orchestrator.run(eq(SURVEY_ID), any())).thenReturn(SyncOutcome.Success)

            val worker =
                SyncWorker(
                    context,
                    workerParams(),
                    selectedSurveyRepository,
                    orchestrator,
                )

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun `auth expired returns success with auth flag`() =
        runTest {
            val selectedSurveyRepository = mock(SelectedSurveyRepository::class.java)
            `when`(selectedSurveyRepository.selectedSurveyId).thenReturn(flowOf(SURVEY_ID))
            val orchestrator = mock(SyncOrchestrator::class.java)
            `when`(orchestrator.run(eq(SURVEY_ID), any())).thenReturn(SyncOutcome.AuthExpired)

            val worker =
                SyncWorker(
                    context,
                    workerParams(),
                    selectedSurveyRepository,
                    orchestrator,
                )

            val result = worker.doWork()
            assertTrue(result is Result.Success)
            val output = (result as Result.Success).outputData
            assertTrue(output.getBoolean(SyncWorker.KEY_AUTH_EXPIRED, false))
        }

    @Test
    fun `retryable failure outcome returns retry`() =
        runTest {
            val selectedSurveyRepository = mock(SelectedSurveyRepository::class.java)
            `when`(selectedSurveyRepository.selectedSurveyId).thenReturn(flowOf(SURVEY_ID))
            val orchestrator = mock(SyncOrchestrator::class.java)
            `when`(orchestrator.run(eq(SURVEY_ID), any()))
                .thenReturn(SyncOutcome.RetryableFailure)

            val worker =
                SyncWorker(
                    context,
                    workerParams(),
                    selectedSurveyRepository,
                    orchestrator,
                )

            assertEquals(Result.retry(), worker.doWork())
        }

    @Test
    fun `failure outcome returns failure`() =
        runTest {
            val selectedSurveyRepository = mock(SelectedSurveyRepository::class.java)
            `when`(selectedSurveyRepository.selectedSurveyId).thenReturn(flowOf(SURVEY_ID))
            val orchestrator = mock(SyncOrchestrator::class.java)
            `when`(orchestrator.run(eq(SURVEY_ID), any()))
                .thenReturn(SyncOutcome.Failure("boom"))

            val worker =
                SyncWorker(
                    context,
                    workerParams(),
                    selectedSurveyRepository,
                    orchestrator,
                )

            assertEquals(Result.failure(), worker.doWork())
        }

    private fun completedVoidFuture(): ListenableFuture<Void> {
        @Suppress("UNCHECKED_CAST")
        return Futures.immediateFuture(null as Void?) as ListenableFuture<Void>
    }

    private fun workerParams(): WorkerParameters {
        val executor = Executors.newSingleThreadExecutor()
        val serialExecutor =
            object : SerialExecutor {
                override fun hasPendingTasks(): Boolean = false

                override fun execute(command: Runnable) {
                    command.run()
                }
            }
        val taskExecutor =
            object : TaskExecutor {
                override fun getMainThreadExecutor(): Executor = executor

                override fun getSerialTaskExecutor(): SerialExecutor = serialExecutor
            }
        val progressUpdater =
            object : ProgressUpdater {
                override fun updateProgress(
                    context: Context,
                    id: UUID,
                    data: Data,
                ): ListenableFuture<Void> = completedVoidFuture()
            }
        val foregroundUpdater =
            object : ForegroundUpdater {
                override fun setForegroundAsync(
                    context: Context,
                    id: UUID,
                    info: ForegroundInfo,
                ): ListenableFuture<Void> = completedVoidFuture()
            }
        return WorkerParameters(
            UUID.randomUUID(),
            Data.EMPTY,
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            executor,
            EmptyCoroutineContext,
            taskExecutor,
            DefaultWorkerFactory,
            progressUpdater,
            foregroundUpdater,
        )
    }

    private companion object {
        const val SURVEY_ID = 7L
    }
}
