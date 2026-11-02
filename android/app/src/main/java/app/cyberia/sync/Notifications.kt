package app.cyberia.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cyberia.R
import app.cyberia.ui.MainActivity

object Notifications {
    const val CHANNEL_STANDBY = "standby"
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_LIBRARY = "library"

    const val ID_STANDBY = 1
    const val ID_SYNC_WORKER = 2
    const val ID_NEW_AUDIOS = 10
    const val ID_NO_SPACE = 11
    const val ID_CONFIRM_DELETE = 12
    const val ID_UNPAIRED = 13

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_STANDBY,
                    context.getString(R.string.channel_standby),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.channel_standby_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_SYNC,
                    context.getString(R.string.channel_sync),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_sync_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_LIBRARY,
                    context.getString(R.string.channel_library),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_library_desc) },
            ),
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notificación fija del modo espera (RF-40). */
    fun standby(context: Context, title: String, text: String, progress: Pair<Int, Int>? = null): Notification =
        NotificationCompat.Builder(context, CHANNEL_STANDBY)
            .setSmallIcon(R.drawable.ic_stat_cyberia)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .apply { if (progress != null) setProgress(progress.second, progress.first, progress.second == 0) }
            .build()

    fun syncing(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_cyberia)
            .setContentTitle("Cyberia")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent(context))
            .build()

    /** Al terminar, la cantidad de audios nuevos (RF-24). */
    fun newAudios(context: Context, count: Int, pcName: String) {
        if (count <= 0) return
        val title = if (count == 1) "1 audio nuevo" else "$count audios nuevos"
        post(context, ID_NEW_AUDIOS, title, "Llegaron desde $pcName. Ya se pueden escuchar sin conexión.")
    }

    /** Sin espacio (RF-26). */
    fun noSpace(context: Context, failed: Int) {
        post(
            context,
            ID_NO_SPACE,
            "No hay espacio en el celular",
            "Faltaron $failed ${if (failed == 1) "audio" else "audios"}. No se borró nada para hacer lugar.",
        )
    }

    /** Borrado grande que espera confirmación (RF-20). */
    fun confirmDeletion(context: Context, count: Int) {
        post(
            context,
            ID_CONFIRM_DELETE,
            "¿Borrar $count audios?",
            "Ya no están en la PC y son más de la mitad de la biblioteca. Abrí Cyberia para confirmar.",
        )
    }

    fun unpaired(context: Context) {
        post(context, ID_UNPAIRED, "Cyberia se desvinculó de la PC", "Los audios siguen en el celular. Para recibir nuevos, volvé a escanear el QR.")
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_LIBRARY)
            .setSmallIcon(R.drawable.ic_stat_cyberia)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
