package au.edu.fireballs.stage4.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageModuleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun rootsUseOwnedApplicationPrivateLocationsWithoutCreatingDirectories() {
        val cropRoot = StorageModule.provideCandidateCropRoot(context)
        val evidenceRoot = StorageModule.provideEvidenceRoot(context)
        val captureRoot = StorageModule.provideEvidenceCaptureCacheRoot(context)
        val volumeRoot = StorageModule.provideAppVolumeProbeRoot(context)

        cropRoot.deleteRecursively()
        evidenceRoot.deleteRecursively()
        captureRoot.deleteRecursively()

        assertEquals(File(context.filesDir, "crops"), cropRoot)
        assertEquals(File(context.filesDir, "evidence"), evidenceRoot)
        assertEquals(File(context.cacheDir, "evidence"), captureRoot)
        assertEquals(context.filesDir, volumeRoot)
        assertFalse(cropRoot.exists())
        assertFalse(evidenceRoot.exists())
        assertFalse(captureRoot.exists())
        assertEquals(
            listOf(captureRoot),
            StorageModule.provideOwnedTempCacheRoots(captureRoot),
        )
    }
}
