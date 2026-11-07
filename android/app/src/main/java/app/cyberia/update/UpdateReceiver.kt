package app.cyberia.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import app.cyberia.R
import app.cyberia.graph
import app.cyberia.sync.Notifications

/** Resultado del instalador de Android para una actualización. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = context.applicationContext.graph.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    // Si la app ya no está a la vista, Android no deja abrir la confirmación: se avisa.
                    Log.w(TAG, "no se pudo abrir la confirmación", e)
                    notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates.onInstallCancelled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.onInstallCancelled()
            else -> {
                Log.w(TAG, "falló la actualización ($status)")
                updates.onInstallFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyConfirm(context: Context, confirm: Intent) {
        val pending = PendingIntent.getActivity(
            context,
            0,
            confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_LIBRARY)
            .setSmallIcon(R.drawable.ic_stat_cyberia)
            .setContentTitle("Actualización lista")
            .setContentText("Tocá para instalar la versión nueva de Cyberia.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(Notifications.ID_UPDATE, notification) }
    }

    companion object {
        private const val TAG = "CyberiaUpdate"
    }
}
