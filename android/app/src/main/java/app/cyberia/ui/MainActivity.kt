package app.cyberia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.cyberia.graph
import app.cyberia.sync.StandbyService
import app.cyberia.sync.SyncWorker
import app.cyberia.sync.Trigger
import app.cyberia.update.UpdateUi
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private var ignoringBatteryOptimizations by mutableStateOf(true)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Escáner de QR de ZXing, sin depender de Google Play Services. */
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        vm.onScanned(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) handlePairingLink(intent)

        setContent {
            CyberiaTheme {
                CyberiaRoot(
                    vm = vm,
                    onScan = ::scanQr,
                    onBatterySettings = ::openBatterySettings,
                    onAllowInstalls = ::openInstallSettings,
                    ignoringBatteryOptimizations = ignoringBatteryOptimizations,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** `cyberia://pair?...`: el QR de la PC abierto como enlace. */
    private fun handlePairingLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "cyberia" || data.host != "pair") return
        vm.onPairingLink(data.toString())
        intent.data = null
    }

    override fun onStart() {
        super.onStart()
        // Volvió de dar el permiso de instalar apps: sigue con la actualización.
        if (graph.updates.state.value is UpdateUi.NeedsPermission && packageManager.canRequestPackageInstalls()) {
            vm.installUpdate()
        }
        ignoringBatteryOptimizations = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) ?: true
        if (graph.prefs.pairedPc.value != null) {
            // Con la app a la vista sí se puede arrancar el servicio en primer plano.
            StandbyService.start(this)
            SyncWorker.scheduleWhenCharging(this)
            // Abrir la app es una revisión inmediata.
            vm.syncNow(Trigger.App)
        }
    }

    private fun scanQr() {
        vm.clearPairingError()
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Apuntá al código que muestra Cyberia en la PC")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /** Permiso de Android para que Cyberia instale sus actualizaciones (se da una sola vez). */
    private fun openInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    /** Guía para excluir a Cyberia de la optimización de batería (riesgo principal del MVP). */
    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
