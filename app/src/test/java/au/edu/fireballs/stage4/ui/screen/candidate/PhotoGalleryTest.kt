package au.edu.fireballs.stage4.ui.screen.candidate

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PhotoGalleryTest {
    @Test
    fun resolveCaptureResult_acceptsWhenNotCancelled() {
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "cap.jpg")
        file.createNewFile()
        val uri = Uri.fromFile(file)

        val result = resolveCaptureResult(uri, cancelled = false)

        assertEquals(uri, result)
        assert(file.exists())
    }

    @Test
    fun resolveCaptureResult_discardsAndDeletesWhenCancelled() {
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "cap2.jpg")
        file.createNewFile()
        val uri = Uri.fromFile(file)

        val result = resolveCaptureResult(uri, cancelled = true)

        assertNull(result)
        assert(!file.exists())
    }

    @Test
    fun resolveCaptureResult_nullUriReturnsNull() {
        assertNull(resolveCaptureResult(null, cancelled = false))
    }
}
