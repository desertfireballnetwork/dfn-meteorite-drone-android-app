package au.edu.fireballs.stage4.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class Stage4DatabaseMigrationsTest {
    @Test
    fun `registered migrations form an unbroken chain to the schema version`() {
        val migrationsByStart = Stage4Database.ALL_MIGRATIONS.associateBy { it.startVersion }

        (1 until STAGE4_DATABASE_VERSION).forEach { startVersion ->
            val migration =
                migrationsByStart[startVersion]
                    ?: error("No migration registered from version $startVersion")
            assertEquals(
                "Migration from $startVersion must advance exactly one version",
                startVersion + 1,
                migration.endVersion,
            )
        }

        assertEquals(
            "Highest registered migration must reach the schema version",
            STAGE4_DATABASE_VERSION,
            Stage4Database.ALL_MIGRATIONS.maxOf { it.endVersion },
        )
    }
}
