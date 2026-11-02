package app.cyberia.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.cyberia.R
import app.cyberia.core.PairedPc
import app.cyberia.core.PendingDeletion
import app.cyberia.data.LastSync
import app.cyberia.data.TrackEntity
import app.cyberia.player.PlayerUi
import app.cyberia.sync.SyncStatus
import kotlinx.coroutines.delay

@Composable
fun CyberiaRoot(
    vm: MainViewModel,
    onScan: () -> Unit,
    onBatterySettings: () -> Unit,
    ignoringBatteryOptimizations: Boolean,
) {
    val pc by vm.pairedPc.collectAsStateWithLifecycle()
    val current = pc
    if (current == null) {
        val pairing by vm.pairing.collectAsStateWithLifecycle()
        PairingScreen(pairing, onScan)
    } else {
        LibraryScreen(vm, current, onBatterySettings, ignoringBatteryOptimizations)
    }
}

// ---- Vinculación ----

@Composable
fun PairingScreen(state: PairingUi, onScan: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(180.dp),
            )
            Text("Cyberia", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tus audios de la PC, en el celular y sin conexión.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Para vincular este celular", style = MaterialTheme.typography.titleMedium)
                    Text("1. Conectá el celular al mismo Wi-Fi que la PC.")
                    Text("2. En la PC, abrí Cyberia y tocá «Vincular celular».")
                    Text("3. Escaneá el código que aparece.")
                }
            }
            Spacer(Modifier.height(24.dp))
            when (state) {
                is PairingUi.Working -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Vinculando con ${state.pcName}…")
                }
                else -> {
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Escanear QR")
                    }
                    if (state is PairingUi.Error) {
                        Spacer(Modifier.height(16.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

// ---- Biblioteca ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    pc: PairedPc,
    onBatterySettings: () -> Unit,
    ignoringBatteryOptimizations: Boolean,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val pending by vm.pendingDeletion.collectAsStateWithLifecycle()
    val manualHost by vm.manualHost.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    var editHost by rememberSaveable { mutableStateOf(false) }
    var batteryTipHidden by rememberSaveable { mutableStateOf(vm.batteryTipDismissed) }

    val busy = status is SyncStatus.Searching || status is SyncStatus.Syncing
    val totalTracks = playlists.sumOf { it.tracks.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cyberia", fontWeight = FontWeight.SemiBold)
                        Text(
                            "$totalTracks ${if (totalTracks == 1) "audio" else "audios"} · ${formatBytes(playlists.sumOf { it.bytes })} · ${pc.pcName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.syncNow() }, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Buscar audios nuevos")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (playlists.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Escuchar todo") },
                                    onClick = {
                                        menuOpen = false
                                        vm.playAll(playlists)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("IP de la PC a mano") },
                                onClick = {
                                    menuOpen = false
                                    editHost = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Que el celular no cierre Cyberia") },
                                onClick = {
                                    menuOpen = false
                                    onBatterySettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Desvincular de ${pc.pcName}") },
                                onClick = {
                                    menuOpen = false
                                    confirmUnpair = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (player.currentPath != null) {
                MiniPlayer(player, vm)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = busy,
            onRefresh = { vm.syncNow() },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "status") {
                    StatusCard(status, lastSync, pc, onDismissError = vm::clearSyncError)
                }
                pending?.let { p ->
                    item(key = "pending") {
                        PendingDeletionCard(p, onConfirm = vm::confirmDeletion, onDismiss = vm::dismissDeletion)
                    }
                }
                if (!ignoringBatteryOptimizations && !batteryTipHidden) {
                    item(key = "battery") {
                        BatteryTipCard(
                            onOpen = onBatterySettings,
                            onDismiss = {
                                batteryTipHidden = true
                                vm.batteryTipDismissed = true
                            },
                        )
                    }
                }
                if (playlists.isEmpty()) {
                    item(key = "empty") { EmptyLibrary() }
                }
                playlists.forEach { group ->
                    item(key = "h:${group.name}") {
                        PlaylistHeader(
                            group = group,
                            onPlay = { vm.play(group, group.tracks.first()) },
                            onShuffle = { vm.playShuffled(group) },
                        )
                    }
                    items(group.tracks, key = { "t:${it.path}" }) { track ->
                        TrackRow(
                            track = track,
                            isCurrent = player.currentPath == track.path,
                            isPlaying = player.isPlaying,
                            onClick = { vm.play(group, track) },
                        )
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("¿Desvincular de ${pc.pcName}?") },
            text = { Text("Los audios descargados siguen en el celular. Para recibir nuevos vas a tener que escanear otra vez el QR.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    vm.unpair()
                }) { Text("Desvincular") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancelar") } },
        )
    }

    if (editHost) {
        var text by rememberSaveable { mutableStateOf(manualHost.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editHost = false },
            title = { Text("IP de la PC") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Solo hace falta si Cyberia no encuentra la PC sola, por ejemplo si el router bloquea el descubrimiento. La IP aparece en la PC al vincular.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("Ej.: 192.168.0.10:${pc.port}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setManualHost(text)
                    editHost = false
                    vm.syncNow()
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setManualHost(null)
                    editHost = false
                }) { Text("Borrar") }
            },
        )
    }
}

@Composable
private fun StatusCard(status: SyncStatus, lastSync: LastSync?, pc: PairedPc, onDismissError: () -> Unit) {
    val colors = when (status) {
        is SyncStatus.Failed -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }
    Card(
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (status) {
                SyncStatus.Searching -> Text("Buscando a ${pc.pcName}…", style = MaterialTheme.typography.titleSmall)
                is SyncStatus.Syncing -> {
                    val p = status.progress
                    if (p == null || p.total == 0) {
                        Text("Revisando qué hay de nuevo…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Descargando ${p.index} de ${p.total}", style = MaterialTheme.typography.titleSmall)
                        Text(p.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { if (p.bytesTotal > 0) (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is SyncStatus.Failed -> {
                    Text(status.message)
                    TextButton(onClick = onDismissError, contentPadding = PaddingValues(0.dp)) { Text("Entendido") }
                }
                SyncStatus.Idle -> {
                    if (lastSync == null) {
                        Text("Todavía no sincronizó.", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Tirá hacia abajo para revisar. Con el celular cargando en el Wi-Fi de casa, los audios llegan solos.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val headline = when {
                            lastSync.downloaded > 0 ->
                                if (lastSync.downloaded == 1) "Llegó 1 audio nuevo" else "Llegaron ${lastSync.downloaded} audios nuevos"
                            lastSync.complete -> "Al día con ${pc.pcName}"
                            else -> "Faltan audios de ${pc.pcName}"
                        }
                        Text(headline, style = MaterialTheme.typography.titleSmall)
                        Text("Revisado ${relativeTime(lastSync.atMs)}", style = MaterialTheme.typography.bodySmall)
                        if (lastSync.noSpace) {
                            Text(
                                "No hay espacio en el celular. No se borró nada para hacer lugar.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (lastSync.failed > 0) {
                            Text(
                                "No ${if (lastSync.failed == 1) "llegó 1 audio" else "llegaron ${lastSync.failed} audios"}. ${lastSync.firstFailure.orEmpty()}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingDeletionCard(pending: PendingDeletion, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val n = pending.paths.size
            Text("¿Borrar $n ${if (n == 1) "audio" else "audios"} del celular?", style = MaterialTheme.typography.titleSmall)
            Text(
                "Ya no están en la PC y son más de la mitad de tu biblioteca. Si la carpeta de la PC está en un disco desconectado, conectalo y no los borres.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Borrar") }
                TextButton(onClick = onDismiss) { Text("Ahora no") }
            }
        }
    }
}

@Composable
private fun BatteryTipCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Para que los audios lleguen solos", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Algunos celulares cierran las apps en segundo plano. Excluí a Cyberia de la optimización de batería.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Configurar") }
                    TextButton(onClick = onDismiss) { Text("Ocultar") }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Todavía no hay audios", style = MaterialTheme.typography.titleMedium)
        Text(
            "En la PC, copiá audios a la carpeta de Cyberia y tocá Sincronizar. Cada subcarpeta es una playlist.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistHeader(group: PlaylistGroup, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${group.tracks.size} ${if (group.tracks.size == 1) "audio" else "audios"} · ${formatBytes(group.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShuffle) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Aleatorio en ${group.label}")
            }
            FilledIconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir ${group.label}")
            }
        }
    }
}

@Composable
private fun TrackRow(track: TrackEntity, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                Icon(
                    if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.Pause,
                    contentDescription = if (isPlaying) "Sonando" else "En pausa",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            track.title,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            formatBytes(track.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Reproductor ----

@Composable
private fun MiniPlayer(player: PlayerUi, vm: MainViewModel) {
    // Actualiza la posición mientras suena y la app está a la vista.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, player.isPlaying) {
        if (!player.isPlaying) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshPlayer()
                delay(500)
            }
        }
    }

    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = {
                    if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        player.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${player.subtitle.orEmpty()} · ${formatTime(player.positionMs)} / ${formatTime(player.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = if (player.shuffle) "Aleatorio activado" else "Aleatorio desactivado",
                        tint = if (player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = vm::previous, enabled = player.hasPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior")
                }
                FilledIconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (player.isPlaying) "Pausar" else "Reproducir",
                    )
                }
                IconButton(onClick = vm::next, enabled = player.hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Siguiente")
                }
            }
        }
    }
}

// ---- Formatos ----

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    val text = if (value < 10) String.format(java.util.Locale("es"), "%.1f", value) else value.toLong().toString()
    return "$text ${units[unit]}"
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun relativeTime(atMs: Long): String {
    val seconds = (System.currentTimeMillis() - atMs) / 1000
    return when {
        seconds < 45 -> "recién"
        seconds < 3600 -> "hace ${(seconds + 30) / 60} min"
        seconds < 86_400 -> "hace ${(seconds + 1800) / 3600} h"
        seconds < 172_800 -> "ayer"
        else -> "hace ${seconds / 86_400} días"
    }
}
