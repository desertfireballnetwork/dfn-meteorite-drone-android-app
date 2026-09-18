package au.edu.fireballs.stage4.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object ActiveEvidenceCapture {
    private val active = MutableStateFlow<String?>(null)

    fun mark(path: String) {
        active.update { path }
    }

    fun clear(path: String) {
        active.update { current -> if (current == path) null else current }
    }

    fun isActive(path: String): Boolean = active.value == path

    fun reset() {
        active.update { null }
    }
}
