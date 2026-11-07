package app.cyberia.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.cyberia.graph
import java.util.concurrent.TimeUnit

/**
 * Revisión periódica: cada 15 minutos (el mínimo de Android), solo con una red sin costo (RF-41).
 * Si una descarga larga se corta porque Android detiene la tarea, se retoma en la próxima.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = if (tags.contains(TAG_CHARGING)) Trigger.Charging else Trigger.Periodic
        applicationContext.graph.syncManager.syncNow(trigger)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "cyberia-periodic"
        private const val CHARGING = "cyberia-charging"
        private const val TAG_CHARGING = "charging"

        private val unmetered = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(unmetered)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Una revisión apenas haya Wi-Fi y el celular esté cargando. */
        fun scheduleWhenCharging(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .addTag(TAG_CHARGING)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHARGING, ExistingWorkPolicy.KEEP, request)
        }
    }
}
