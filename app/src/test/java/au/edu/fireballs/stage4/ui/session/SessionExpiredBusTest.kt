package au.edu.fireballs.stage4.ui.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExpiredBusTest {
    @Test
    fun `emission is collected by subscriber`() =
        runTest {
            val bus = SessionExpiredBus()
            val emitted = mutableListOf<Unit>()
            val collectJob =
                launch {
                    bus.events.collect { emitted.add(it) }
                }
            advanceUntilIdle()

            bus.emit()
            bus.emit()
            advanceUntilIdle()

            assertEquals(2, emitted.size)
            collectJob.cancel()
        }
}
