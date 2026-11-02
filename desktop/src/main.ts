import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { ask, open as openDialog } from "@tauri-apps/plugin-dialog";

// ---- Tipos que manda el núcleo en Rust ----

interface LibraryItem {
  name: string;
  path: string;
  size: number;
  supported: boolean;
}

interface PlaylistView {
  name: string;
  items: LibraryItem[];
}

interface ScanError {
  path: string;
  message: string;
}

interface LibraryView {
  playlists: PlaylistView[];
  totalFiles: number;
  totalBytes: number;
  unsupportedCount: number;
  errors: ScanError[];
}

interface ReportItem {
  id: string;
  path: string;
}

interface FailedItem extends ReportItem {
  reason: string;
}

interface StoredReport {
  receivedAtMs: number;
  deviceName: string;
  manifestVersion: number;
  downloaded: ReportItem[];
  deleted: ReportItem[];
  failed: FailedItem[];
  pendingDeletions: number;
  noSpace: boolean;
  freeBytes: number | null;
  libraryCount: number;
  libraryBytes: number;
  complete: boolean;
}

interface PairingOffer {
  uri: string;
  qrSvg: string;
  expiresAtMs: number;
  hosts: string[];
  port: number;
}

interface UiState {
  pcId: string;
  pcName: string;
  port: number | null;
  fingerprint: string;
  lanAddresses: string[];
  folder: string | null;
  folderExists: boolean;
  manifestVersion: number;
  publishedAtMs: number;
  publishedCount: number;
  publishedBytes: number;
  pendingChanges: boolean;
  library: LibraryView | null;
  device: { name: string; pairedAtMs: number } | null;
  phoneConnected: boolean;
  phoneLastSeenMs: number | null;
  lastReport: StoredReport | null;
  pairing: PairingOffer | null;
  serverError: string | null;
  defaultFolder: string;
}

interface SyncOutcome {
  version: number;
  changed: boolean;
  files: number;
  bytes: number;
  phoneConnected: boolean;
}

type CoreEvent =
  | { type: "stateChanged" }
  | { type: "paired"; deviceName: string }
  | { type: "unpaired" }
  | { type: "phoneConnection"; connected: boolean }
  | { type: "report"; report: StoredReport }
  | { type: "published"; version: number; changed: boolean };

// ---- Utilidades ----

type Child = Node | string | null | undefined | false;

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<Record<string, string | boolean | ((e: Event) => void)>> = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === false) continue;
    if (typeof value === "function") {
      node.addEventListener(key.replace(/^on/, "").toLowerCase(), value as EventListener);
    } else if (key === "class") {
      node.className = String(value);
    } else if (value === true) {
      node.setAttribute(key, "");
    } else {
      node.setAttribute(key, String(value));
    }
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child);
  }
  return node;
}

function $(id: string): HTMLElement {
  const node = document.getElementById(id);
  if (!node) throw new Error(`falta #${id}`);
  return node;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toLocaleString("es", { maximumFractionDigits: value < 10 ? 1 : 0 })} ${units[unit]}`;
}

function relativeTime(ms: number): string {
  const seconds = Math.round((Date.now() - ms) / 1000);
  if (seconds < 45) return "recién";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `hace ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `hace ${hours} h`;
  const days = Math.round(hours / 24);
  if (days === 1) return "ayer";
  return `hace ${days} días`;
}

function plural(n: number, one: string, many: string): string {
  return `${n.toLocaleString("es")} ${n === 1 ? one : many}`;
}

function fileName(path: string): string {
  return path.split("/").pop() ?? path;
}

function errorText(err: unknown): string {
  return typeof err === "string" ? err : err instanceof Error ? err.message : JSON.stringify(err);
}

// ---- Estado de la ventana ----

let state: UiState | null = null;
let syncing = false;
/** Versión publicada con el botón, hasta que llega el informe del celular. */
let awaitingVersion: number | null = null;
let lastSyncError: string | null = null;
let pairedName: string | null = null;
const openPlaylists = new Set<string>();

async function refresh(): Promise<void> {
  state = await invoke<UiState>("get_state");
  render();
}

let refreshTimer: number | undefined;
function scheduleRefresh(): void {
  window.clearTimeout(refreshTimer);
  refreshTimer = window.setTimeout(() => void refresh(), 80);
}

// ---- Render ----

function render(): void {
  if (!state) return;
  const s = state;

  $("pc-name").textContent = s.port
    ? `${s.pcName} · puerto ${s.port}`
    : s.pcName;

  const pill = $("phone-pill");
  if (!s.device) {
    pill.className = "pill off";
    pill.textContent = "Sin vincular";
  } else if (s.phoneConnected) {
    pill.className = "pill ok";
    pill.textContent = "Conectado";
  } else {
    pill.className = "pill off";
    pill.textContent = "Sin conexión";
  }

  const serverError = $("server-error");
  serverError.hidden = !s.serverError;
  serverError.textContent = s.serverError ?? "";

  renderPhone(s);
  renderFolder(s);
  renderSync(s);
  renderLibrary(s);
  if (pairDialog.open) renderPairing();
}

function renderPhone(s: UiState): void {
  const body = $("phone-body");
  if (!s.device) {
    body.replaceChildren(
      el(
        "div",
        { class: "stack" },
        el("p", {}, "Ningún celular vinculado."),
        el("p", { class: "muted" }, "Instalá Cyberia en el celular y escaneá el código que muestra la PC."),
        el("div", {}, el("button", { class: "primary", type: "button", onclick: () => void openPairing() }, "Vincular celular")),
      ),
    );
    return;
  }
  const status = s.phoneConnected
    ? "Conectado: descarga al instante cuando sincronizás."
    : s.phoneLastSeenMs
      ? `Sin conexión. Visto ${relativeTime(s.phoneLastSeenMs)}.`
      : "Sin conexión. Se conecta solo mientras carga, o cada 15 minutos con Wi-Fi.";
  body.replaceChildren(
    el(
      "div",
      { class: "row between" },
      el(
        "div",
        {},
        el("strong", {}, s.device.name),
        el("p", { class: "muted" }, status),
      ),
      el("button", { class: "ghost small", type: "button", onclick: () => void unpair() }, "Desvincular"),
    ),
  );
}

function renderFolder(s: UiState): void {
  const body = $("folder-body");
  if (!s.folder) {
    body.replaceChildren(
      el(
        "div",
        { class: "stack" },
        el("p", {}, "Elegí dónde van a vivir tus audios. Cada subcarpeta es una playlist."),
        el("p", { class: "mono muted" }, s.defaultFolder),
        el(
          "div",
          { class: "row" },
          el("button", { class: "primary", type: "button", onclick: () => void createDefaultFolder() }, "Crear carpeta Cyberia"),
          el("button", { type: "button", onclick: () => void chooseFolder() }, "Elegir una existente…"),
        ),
      ),
    );
    return;
  }
  body.replaceChildren(
    el(
      "div",
      { class: "stack" },
      el("p", { class: "mono" }, s.folder),
      !s.folderExists &&
        el("div", { class: "banner warn" }, "No se encuentra la carpeta. ¿Está conectado el disco? Mientras falte, no se publica nada."),
      el(
        "div",
        { class: "row" },
        el("button", { type: "button", onclick: () => void invoke("open_folder").catch(showError), disabled: !s.folderExists }, "Abrir"),
        el("button", { type: "button", onclick: () => void chooseFolder() }, "Cambiar…"),
      ),
    ),
  );
}

function renderSync(s: UiState): void {
  const button = $("sync-btn") as HTMLButtonElement;
  button.disabled = syncing || !s.folder || !s.folderExists;
  button.textContent = syncing ? "Sincronizando…" : "Sincronizar";

  const status = $("sync-status");
  status.replaceChildren();
  if (s.manifestVersion === 0) {
    status.append("Todavía no sincronizaste.");
  } else {
    status.append(
      `${plural(s.publishedCount, "audio", "audios")} (${formatBytes(s.publishedBytes)}) · publicado ${relativeTime(s.publishedAtMs)}`,
    );
  }
  if (s.pendingChanges) {
    status.append(" ", el("span", { class: "badge" }, "Hay cambios sin sincronizar"));
  }

  const report = $("report");
  const parts: Node[] = [];
  if (lastSyncError) {
    parts.push(el("div", { class: "banner danger" }, lastSyncError));
  }

  const r = s.lastReport;
  const device = s.device?.name ?? "el celular";
  const waiting = awaitingVersion !== null && (!r || r.manifestVersion < awaitingVersion);
  if (waiting && s.device) {
    parts.push(
      el(
        "div",
        { class: "banner info" },
        s.phoneConnected
          ? `Avisé a ${device}. Esperando que descargue…`
          : `${device} no está conectado. Los cambios quedan pendientes y se descargan la próxima vez que se conecte (al cargar, o en menos de 15 minutos con Wi-Fi).`,
      ),
    );
  } else if (!s.device && s.manifestVersion > 0) {
    parts.push(el("div", { class: "banner warn" }, "Publicado. Vinculá un celular para que lo descargue."));
  }

  // Mientras se espera el informe nuevo, el anterior confunde: no se muestra.
  if (r && s.device && !waiting) {
    const when = relativeTime(r.receivedAtMs);
    if (r.downloaded.length > 0) {
      parts.push(
        el("div", { class: "banner ok" }, `✓ ${plural(r.downloaded.length, "audio llegó", "audios llegaron")} a ${r.deviceName || device} · ${when}`),
      );
    } else if (r.complete) {
      parts.push(
        el("div", { class: "banner ok" }, `✓ ${r.deviceName || device} tiene todo: ${plural(r.libraryCount, "audio", "audios")} · ${when}`),
      );
    }
    if (r.deleted.length > 0) {
      parts.push(el("p", { class: "muted" }, `Se ${r.deleted.length === 1 ? "borró 1 audio" : `borraron ${r.deleted.length} audios`} del celular.`));
    }
    if (r.pendingDeletions > 0) {
      parts.push(
        el(
          "div",
          { class: "banner warn" },
          `El celular espera tu confirmación para borrar ${plural(r.pendingDeletions, "audio", "audios")}: es más de la mitad de su biblioteca.`,
        ),
      );
    }
    if (r.noSpace) {
      parts.push(
        el(
          "div",
          { class: "banner danger" },
          `El celular se quedó sin espacio${r.freeBytes != null ? ` (quedan ${formatBytes(r.freeBytes)})` : ""}. No se borró nada para hacer lugar.`,
        ),
      );
    }
    if (r.failed.length > 0) {
      parts.push(
        el(
          "div",
          { class: "banner danger" },
          `${plural(r.failed.length, "audio no llegó", "audios no llegaron")}:`,
          el(
            "ul",
            { class: "fail-list" },
            ...r.failed.slice(0, 20).map((f) => el("li", {}, el("strong", {}, fileName(f.path)), ` — ${f.reason}`)),
          ),
        ),
      );
    }
    if (!waiting && r.manifestVersion < s.manifestVersion) {
      parts.push(
        el("p", { class: "muted" }, "El celular todavía no bajó la última versión. Lo hace la próxima vez que se conecte."),
      );
    }
  }
  report.replaceChildren(...parts);
}

function renderLibrary(s: UiState): void {
  const summary = $("library-summary");
  const list = $("library");
  ($("refresh-btn") as HTMLButtonElement).disabled = !s.folder || !s.folderExists;
  if (!s.library) {
    summary.textContent = s.folder ? "Leyendo la carpeta…" : "Elegí una carpeta para ver los audios.";
    list.replaceChildren();
    return;
  }
  const lib = s.library;
  let text = `${plural(lib.totalFiles, "audio", "audios")} · ${formatBytes(lib.totalBytes)}`;
  if (lib.unsupportedCount > 0) {
    text += ` · ${plural(lib.unsupportedCount, "archivo no compatible", "archivos no compatibles")} (se ignoran)`;
  }
  summary.textContent = text;

  if (lib.playlists.length === 0) {
    list.replaceChildren(
      el("p", { class: "muted" }, "La carpeta está vacía. Copiá audios MP3, M4A, AAC, WAV, FLAC u OGG; cada subcarpeta va a ser una playlist."),
    );
    return;
  }

  const nodes: Node[] = lib.playlists.map((playlist) => {
    const supported = playlist.items.filter((i) => i.supported).length;
    const title = playlist.name === "" ? "Sueltos (en la raíz de la carpeta)" : playlist.name;
    const details = el(
      "details",
      { open: openPlaylists.has(playlist.name) || lib.playlists.length === 1 },
      el("summary", {}, el("span", {}, title), el("span", { class: "muted" }, plural(supported, "audio", "audios"))),
      el(
        "ul",
        { class: "tracks" },
        ...playlist.items.map((item) =>
          el(
            "li",
            { class: item.supported ? "" : "unsupported", title: item.path },
            el("span", { class: "name" }, item.name),
            item.supported
              ? el("span", { class: "size" }, formatBytes(item.size))
              : el("span", { class: "badge" }, "no compatible"),
          ),
        ),
      ),
    );
    details.addEventListener("toggle", () => {
      if (details.open) openPlaylists.add(playlist.name);
      else openPlaylists.delete(playlist.name);
    });
    return details;
  });

  if (lib.errors.length > 0) {
    nodes.push(
      el(
        "div",
        { class: "banner warn" },
        `No se pudieron leer ${plural(lib.errors.length, "archivo", "archivos")} (quizás se están copiando):`,
        el(
          "ul",
          { class: "fail-list" },
          ...lib.errors.slice(0, 10).map((e) => el("li", {}, el("strong", {}, e.path || "?"), ` — ${e.message}`)),
        ),
      ),
    );
  }
  list.replaceChildren(...nodes);
}

// ---- Acciones ----

function showError(err: unknown): void {
  lastSyncError = errorText(err);
  render();
}

async function syncNow(): Promise<void> {
  if (syncing) return;
  syncing = true;
  lastSyncError = null;
  render();
  try {
    const outcome = await invoke<SyncOutcome>("sync_now");
    awaitingVersion = outcome.version;
  } catch (err) {
    lastSyncError = errorText(err);
  } finally {
    syncing = false;
    await refresh();
  }
}

async function chooseFolder(): Promise<void> {
  const picked = await openDialog({ directory: true, multiple: false, title: "Elegí la carpeta de audios" });
  if (typeof picked !== "string") return;
  try {
    state = await invoke<UiState>("set_folder", { path: picked });
    render();
  } catch (err) {
    showError(err);
  }
}

async function createDefaultFolder(): Promise<void> {
  try {
    state = await invoke<UiState>("create_default_folder");
    render();
  } catch (err) {
    showError(err);
  }
}

async function unpair(): Promise<void> {
  const name = state?.device?.name ?? "el celular";
  const ok = await ask(`¿Desvincular ${name}? Va a dejar de recibir audios hasta que lo vuelvas a vincular. Lo que ya descargó sigue en el celular.`, {
    title: "Desvincular celular",
    kind: "warning",
    okLabel: "Desvincular",
    cancelLabel: "Cancelar",
  });
  if (!ok) return;
  try {
    await invoke("unpair");
    awaitingVersion = null;
  } catch (err) {
    showError(err);
  }
  await refresh();
}

// ---- Vinculación ----

const pairDialog = $("pair-dialog") as HTMLDialogElement;
let countdownTimer: number | undefined;

async function openPairing(): Promise<void> {
  pairedName = null;
  try {
    await invoke<PairingOffer>("start_pairing");
  } catch (err) {
    showError(err);
    return;
  }
  await refresh();
  if (!pairDialog.open) pairDialog.showModal();
  renderPairing();
  window.clearInterval(countdownTimer);
  countdownTimer = window.setInterval(renderCountdown, 1000);
}

function closePairing(): void {
  window.clearInterval(countdownTimer);
  if (pairDialog.open) pairDialog.close();
  if (!pairedName) void invoke("cancel_pairing");
}

function renderPairing(): void {
  const body = $("pair-body");
  const close = $("pair-close");
  if (pairedName) {
    close.textContent = "Cerrar";
    body.replaceChildren(
      el("div", { class: "banner ok" }, `✓ Listo: ${pairedName} quedó vinculado.`),
      el("p", { class: "muted", style: "margin-top:10px" }, "Ahora copiá audios a la carpeta y tocá Sincronizar."),
    );
    return;
  }
  close.textContent = "Cancelar";
  const offer = state?.pairing;
  if (!offer) {
    body.replaceChildren(
      el("p", {}, "El código venció."),
      el("div", { class: "row", style: "margin-top:12px" }, el("button", { class: "primary", type: "button", onclick: () => void openPairing() }, "Generar otro")),
    );
    return;
  }
  const qr = el("div", { class: "qr", role: "img", "aria-label": "Código QR para vincular el celular" });
  // El SVG lo genera el núcleo a partir de datos propios; no incluye texto del usuario.
  qr.innerHTML = offer.qrSvg;
  body.replaceChildren(
    el(
      "div",
      { class: "stack" },
      el("p", {}, "En el celular, abrí Cyberia y tocá «Escanear QR»."),
      qr,
      el("p", { class: "muted center countdown", id: "pair-countdown" }),
      offer.hosts.length === 0
        ? el("div", { class: "banner warn" }, "No encontré una red local. Conectá la PC al mismo Wi-Fi o router que el celular.")
        : el("p", { class: "muted center mono" }, `${offer.hosts.join(" · ")} : ${offer.port}`),
    ),
  );
  renderCountdown();
}

function renderCountdown(): void {
  const offer = state?.pairing;
  const node = document.getElementById("pair-countdown");
  if (!offer || !node) return;
  const left = Math.max(0, Math.round((offer.expiresAtMs - Date.now()) / 1000));
  if (left === 0) {
    window.clearInterval(countdownTimer);
    void refresh();
    return;
  }
  const min = Math.floor(left / 60);
  const sec = String(left % 60).padStart(2, "0");
  node.textContent = `Vence en ${min}:${sec} · sirve una sola vez`;
}

$("pair-close").addEventListener("click", closePairing);
pairDialog.addEventListener("cancel", (e) => {
  e.preventDefault();
  closePairing();
});

// ---- Arranque ----

$("sync-btn").addEventListener("click", () => void syncNow());
$("refresh-btn").addEventListener("click", async () => {
  try {
    state = await invoke<UiState>("refresh_library");
    render();
  } catch (err) {
    showError(err);
  }
});

void listen<CoreEvent>("cyberia://event", (event) => {
  const payload = event.payload;
  if (payload.type === "paired") {
    pairedName = payload.deviceName;
    window.clearInterval(countdownTimer);
    if (pairDialog.open) {
      renderPairing();
      window.setTimeout(() => {
        if (pairDialog.open) pairDialog.close();
      }, 2500);
    }
  }
  if (payload.type === "report" && awaitingVersion !== null && payload.report.manifestVersion >= awaitingVersion) {
    awaitingVersion = null;
  }
  scheduleRefresh();
});

// Actualiza los "hace X min".
window.setInterval(() => render(), 30_000);

void refresh();
