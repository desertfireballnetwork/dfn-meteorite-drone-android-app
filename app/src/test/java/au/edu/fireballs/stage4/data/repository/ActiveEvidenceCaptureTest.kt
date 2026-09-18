package au.edu.fireballs.stage4.data.repository

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveEvidenceCaptureTest {
    @Before
    fun setUp() {
        ActiveEvidenceCapture.reset()
    }

    @After
    fun tearDown() {
        ActiveEvidenceCapture.reset()
    }

    @Test
    fun `mark replaces the active path`() {
        ActiveEvidenceCapture.mark("/evidence/first.jpg")
        assertTrue(ActiveEvidenceCapture.isActive("/evidence/first.jpg"))

        ActiveEvidenceCapture.mark("/evidence/second.jpg")

        assertFalse(ActiveEvidenceCapture.isActive("/evidence/first.jpg"))
        assertTrue(ActiveEvidenceCapture.isActive("/evidence/second.jpg"))
    }

    @Test
    fun `clear removes only a matching active path`() {
        ActiveEvidenceCapture.mark("/evidence/active.jpg")

        ActiveEvidenceCapture.clear("/evidence/other.jpg")

        assertTrue(ActiveEvidenceCapture.isActive("/evidence/active.jpg"))

        ActiveEvidenceCapture.clear("/evidence/active.jpg")

        assertFalse(ActiveEvidenceCapture.isActive("/evidence/active.jpg"))
    }

    @Test
    fun `reset removes the active path`() {
        ActiveEvidenceCapture.mark("/evidence/active.jpg")

        ActiveEvidenceCapture.reset()

        assertFalse(ActiveEvidenceCapture.isActive("/evidence/active.jpg"))
    }
}
