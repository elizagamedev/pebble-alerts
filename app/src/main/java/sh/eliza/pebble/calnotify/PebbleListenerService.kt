package sh.eliza.pebble.calnotify

import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private const val MSG_POLL = 0u

class PebbleListenerService : BasePebbleListenerService() {
    @PublishedApi
    internal data class Transaction(
        val watchId: WatchIdentifier?,
        val onAppOpened: CompletableDeferred<Unit>,
        val onAppClosed: CompletableDeferred<Unit>,
    )

    companion object {
        @PublishedApi
        internal val transaction = AtomicReference<Transaction?>(null)

        suspend inline fun <T> withTransaction(
            watch: WatchIdentifier? = null,
            crossinline body: suspend (CompletableDeferred<Unit>, CompletableDeferred<Unit>) -> T?,
        ): T? {
            val newTransaction =
                Transaction(watch, CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            return if (transaction.compareAndExchange(null, newTransaction) == null) {
                try {
                    body(newTransaction.onAppOpened, newTransaction.onAppClosed)
                } finally {
                    transaction.compareAndExchange(newTransaction, null)
                }
            } else {
                null
            }
        }
    }

    private lateinit var pebbleManager: PebbleManager

    override fun onCreate() {
        super.onCreate()
        pebbleManager = PebbleManager(applicationContext)
    }

    override fun onDestroy() {
        pebbleManager.close()
        super.onDestroy()
    }

    override fun onAppOpened(
        watchappUUID: UUID,
        watch: WatchIdentifier,
    ) {
        if (watchappUUID != PebbleManager.APP_UUID) {
            return
        }

        val transaction = transaction.get()
        if (transaction != null && (transaction.watchId == null || transaction.watchId == watch)) {
            transaction.onAppOpened.complete(Unit)
            return
        }

        coroutineScope.launch {
            val settingsRepository = SettingsRepository(dataStore, coroutineScope)
            val settings = settingsRepository.appSettingsFlow.filterNotNull().first()
            val alerts = Alert.getUpcomingAlerts(applicationContext, settings)
            pebbleManager.send(settings.generalSettings, alerts, watch)
            settingsRepository.updateGeneralSettings {
                it.copy(
                    lastSynced = System.currentTimeMillis(),
                )
            }
        }
    }

    override fun onAppClosed(
        watchappUUID: UUID,
        watch: WatchIdentifier,
    ) {
        if (watchappUUID != PebbleManager.APP_UUID) {
            return
        }
        val currentTransaction = transaction.get()
        if (currentTransaction != null &&
            (currentTransaction.watchId == null || currentTransaction.watchId == watch)
        ) {
            transaction.compareAndExchange(currentTransaction, null)
            currentTransaction.onAppClosed.complete(Unit)
        }
    }
}
