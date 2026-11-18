package net.pekmen.one

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.pekmen.one.core.platform.FocusTriggerHandler
import net.pekmen.one.core.platform.FocusTriggerProvider

class PekmenApplication : Application(), FocusTriggerProvider {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            container.profileRepository.ensureSeeded()
            container.sessionRepository.reconcileActiveSessionAfterBootOrWake()
        }
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = container.focusTriggerHandler
}
