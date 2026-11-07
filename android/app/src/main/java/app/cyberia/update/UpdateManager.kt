package app.cyberia.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.cyberia.BuildConfig
import app.cyberia.core.AndroidAppInfo
import app.cyberia.core.CyberiaClient
import app.cyberia.core.CyberiaException
import app.cyberia.core.Hashing
import app.cyberia.data.AppPrefs
import app.cyberia.net.PcLocator
import app.cyberia.net.WifiNetworks
import app.cyberia.sync.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Qué muestra la app sobre la actualización. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data class Downloading(val fraction: Float) : UpdateUi
    data object Installing : UpdateUi

    /** Android pide permitir que Cyberia instale apps (una sola vez). */
    data object NeedsPermission : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * Actualiza la app desde la PC, sin pasar archivos al celular: la PC trae el APK de su misma
 * versión, el celular lo ve en `GET /v1/info`, lo baja por el Wi-Fi, verifica su SHA-256 y se lo
 * pasa al instalador de Android. Como todas las versiones se firman con la misma clave, se
 * conservan la vinculación y los audios.
 */
class UpdateManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val locator: PcLocator,
) {
    private val running = Mutex()
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    private val updatesDir = File(context.cacheDir, "updates")

    /** Versión instalada. */
    val currentVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    /** Al abrir la app: si ya se instaló la versión ofrecida, se olvida y se borra el APK. */
    fun cleanUpAfterUpdate() {
        val offered = prefs.availableUpdate.value
        if (offered == null || offered.versionCode <= currentVersionCode) {
            prefs.setAvailableUpdate(null)
            updatesDir.deleteRecursively()
        }
    }

    /** Lo que ofrece la PC en cada sincronización. */
    fun onPcOffer(app: AndroidAppInfo?, pcName: String) {
        if (app == null || app.versionCode <= currentVersionCode) {
            if (prefs.availableUpdate.value != null) prefs.setAvailableUpdate(null)
            return
        }
        val previous = prefs.availableUpdate.value
        prefs.setAvailableUpdate(app)
        if (previous?.versionCode != app.versionCode) {
            Notifications.updateAvailable(context, app.versionName, pcName)
        }
    }

    /** Baja la versión nueva desde la PC y abre el instalador de Android. */
    suspend fun downloadAndInstall() {
        if (!running.tryLock()) return
        try {
            val offered = prefs.availableUpdate.value ?: return
            if (!context.packageManager.canRequestPackageInstalls()) {
                _state.value = UpdateUi.NeedsPermission
                return
            }
            val apk = File(updatesDir, "cyberia-${offered.versionCode}.apk")
            val ready = apk.isFile && apk.length() == offered.size &&
                withContext(Dispatchers.IO) { Hashing.sha256File(apk) } == offered.sha256
            val file = if (ready) apk else (download(offered) ?: return)
            _state.value = UpdateUi.Installing
            withContext(Dispatchers.IO) { install(file) }
        } catch (e: CancellationException) {
            _state.value = UpdateUi.Idle
            throw e
        } catch (e: CyberiaException) {
            _state.value = UpdateUi.Failed(e.message ?: "No se pudo bajar la actualización.")
        } catch (e: Exception) {
            _state.value = UpdateUi.Failed("No se pudo instalar la actualización: ${e.message}")
        } finally {
            running.unlock()
        }
    }

    private suspend fun download(offered: AndroidAppInfo): File? {
        val pc = prefs.pairedPc.value ?: return null
        val network = WifiNetworks.current(context)
        if (network == null) {
            _state.value = UpdateUi.Failed("Conectate al Wi-Fi de casa para bajar la actualización.")
            return null
        }
        _state.value = UpdateUi.Downloading(0f)
        val endpoint = locator.locate(pc, network)
        if (endpoint == null) {
            _state.value = UpdateUi.Failed("No encuentro a ${pc.pcName}. ¿Está prendida y en el mismo Wi-Fi?")
            return null
        }
        val client = CyberiaClient(pc.fingerprint, network.socketFactory)
        // La PC pudo actualizarse otra vez mientras tanto: se usa lo que ofrece ahora.
        val now = client.info(endpoint, pc.token).androidApp
        if (now == null || now.versionCode <= currentVersionCode) {
            prefs.setAvailableUpdate(null)
            _state.value = UpdateUi.Idle
            return null
        }
        if (now != offered) prefs.setAvailableUpdate(now)
        val target = File(updatesDir, "cyberia-${now.versionCode}.apk")
        updatesDir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        client.downloadAndroidApp(endpoint, pc.token, target, now.size) { done ->
            _state.value = UpdateUi.Downloading((done.toFloat() / now.size).coerceIn(0f, 1f))
        }
        val hash = withContext(Dispatchers.IO) { Hashing.sha256File(target) }
        if (target.length() != now.size || hash != now.sha256) {
            target.delete()
            _state.value = UpdateUi.Failed("La actualización llegó dañada. Probá de nuevo.")
            return null
        }
        return target
    }

    /** Le pasa el APK al instalador de Android. El resultado llega a [UpdateReceiver]. */
    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Desde Android 12, si Cyberia ya se instaló a sí misma, las próximas no piden confirmar.
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("cyberia.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }

    fun onInstallFailed(message: String?) {
        _state.value = UpdateUi.Failed(
            "Android no instaló la actualización${message?.let { ": $it" } ?: "."}",
        )
    }

    fun onInstallCancelled() {
        _state.value = UpdateUi.Idle
    }

    fun clearError() {
        if (_state.value is UpdateUi.Failed || _state.value is UpdateUi.NeedsPermission) _state.value = UpdateUi.Idle
    }
}
