package au.edu.fireballs.stage4.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object ActiveEvidenceCapture {
    private val active = MutableStateFlow<Set<String>>(emptySet())

    fun mark(path: String) {
        active.update { it + path }
    }

    fun clear(path: String) {
        active.update { it - path }
    }

    fun isActive(path: String): Boolean = path in active.value

    fun reset() {
        active.update { emptySet() }
    }
}
