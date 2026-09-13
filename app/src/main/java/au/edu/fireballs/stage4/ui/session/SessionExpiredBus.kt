package au.edu.fireballs.stage4.ui.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionExpiredBus
    @Inject
    constructor() {
        private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        val events: SharedFlow<Unit> = _events.asSharedFlow()

        suspend fun emit() {
            _events.emit(Unit)
        }
    }
