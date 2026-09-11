package au.edu.fireballs.stage4

import androidx.work.Configuration
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage4AppTest {
    @Test
    fun stage4AppIsConfigurationProvider() {
        assertTrue(
            "Stage4App must implement Configuration.Provider so HiltWorkerFactory can be used",
            Configuration.Provider::class.java.isAssignableFrom(Stage4App::class.java),
        )
    }
}
