package sh.eliza.pebble.calnotify

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.time.Instant

private const val TAG = "SyncWorker"

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        fun scheduleNextUriTrigger(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                    .addContentUriTrigger(ContactsContract.Contacts.CONTENT_URI, true)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .build()

            WorkManager
                .getInstance(context)
                .enqueueUniqueWork("uri_trigger_sync", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result =
        coroutineScope {
            try {
                val settingsRepository = SettingsRepository(applicationContext.dataStore, this)
                val settings = settingsRepository.appSettingsFlow.filterNotNull().first()

                // Calculate upcoming alerts
                val alerts = Alert.getUpcomingAlerts(applicationContext, settings)
                val lastSentAlert = settingsRepository.lastSentAlertFlow.first()

                if (alerts.firstOrNull() == lastSentAlert) {
                    Log.d(TAG, "Next alert unchanged. Skipping sync.")
                    scheduleNextUriTrigger(applicationContext)
                    return@coroutineScope Result.success()
                }

                Log.d(TAG, "Next alert changed. Syncing to Pebble.")

                PebbleManager(applicationContext).use { pebbleManager ->
                    pebbleManager.withOpenAppOnWatch {
                        pebbleManager.send(settings, alerts)
                        settingsRepository.updateGeneralSettings {
                            it.copy(lastSynced = Instant.now())
                        }
                        settingsRepository.updateLastSentAlert(alerts.firstOrNull())
                    }
                }

                scheduleNextUriTrigger(applicationContext)
                return@coroutineScope Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Sync worker failed", e)
                return@coroutineScope Result.retry()
            }
        }
}
