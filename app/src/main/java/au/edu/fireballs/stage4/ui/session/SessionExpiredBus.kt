package au.edu.fireballs.stage4.ui.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionExpiredBus
    @Inject
    constructor() {
        private val _events = Channel<Unit>(capacity = Channel.CONFLATED)

        val events: Flow<Unit> = _events.receiveAsFlow()

        suspend fun emit() {
            _events.send(Unit)
        }
    }
