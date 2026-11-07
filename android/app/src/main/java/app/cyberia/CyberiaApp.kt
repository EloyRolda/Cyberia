package app.cyberia

import android.app.Application
import android.content.Context
import app.cyberia.sync.Notifications
import app.cyberia.sync.SyncWorker

class CyberiaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.updates.cleanUpAfterUpdate()
        // Revisión periódica cada 15 minutos, solo con Wi-Fi (RF-41).
        SyncWorker.schedulePeriodic(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as CyberiaApp).graph
