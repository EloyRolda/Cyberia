# Cyberia

Tus audios de la PC, en el celular y sin conexión. Dos apps que se hablan por el Wi-Fi de casa,
sin servidores en internet, cuentas ni nube:

- **PC** (`desktop/`, Tauri 2 + Rust): vive en la bandeja, publica una carpeta de audios y avisa
  al celular cuando apretás **Sincronizar**.
- **Celular** (`android/`, Kotlin + Compose): baja los audios mientras carga en el Wi-Fi de casa
  y los reproduce sin conexión, con controles de auriculares y de pantalla bloqueada.

Los requisitos completos están en [`DataToStart/Cyberia — Requisitos funcionales.md`](DataToStart/).

## Cómo se usa

1. Instalá Cyberia en la PC y elegí (o creá) la carpeta de audios. Cada subcarpeta es una playlist.
2. Instalá el APK en el celular, tocá **Escanear QR** y apuntá al código que muestra la PC
   (**Vincular celular**). También se puede escanear con la cámara del sistema: abre Cyberia y
   pide confirmar. El código vence a los 5 minutos y sirve una sola vez. La primera vez, Windows
   pregunta si deja que Cyberia use la red: hay que permitirlo en redes privadas.
3. Copiá audios a la carpeta y tocá **Sincronizar**. Si el celular está cargando en el Wi-Fi de
   casa, los baja al instante; si no, en la próxima revisión (cada 15 minutos con Wi-Fi, al
   empezar a cargar o al abrir la app).
4. La PC confirma cuántos audios llegaron y, si algo falló, cuál y por qué.

## Actualizaciones

El celular se actualiza desde la PC, sin cables ni archivos que pasar:

1. Cada corrida de la CI arma una versión nueva (el número de corrida es la versión) y mete el
   APK del celular dentro del instalador de Windows.
2. Instalás el instalador nuevo en la PC, encima del anterior. La configuración, la carpeta y la
   vinculación se mantienen.
3. La próxima vez que el celular se conecta, ve que la PC trae una versión más nueva, avisa con
   una notificación y muestra **Actualizar**. La baja por el Wi-Fi, verifica su SHA-256 y abre el
   instalador de Android.

La primera vez, Android pide permitir que Cyberia instale apps y confirmar la instalación. Desde
Android 12, las siguientes actualizaciones ya no piden confirmar.

### Clave de firma (una sola vez)

Android solo acepta una actualización si está firmada con la misma clave que la versión
instalada; si no, hay que desinstalar y se pierden la vinculación y los audios. La clave vive en
los secretos de GitHub, nunca en el repositorio:

1. En GitHub: **Actions → Crear clave de firma → Run workflow**.
2. Bajá el artefacto `clave-de-firma` y seguí su `LEEME.txt`: son dos secretos,
   `CYBERIA_KEYSTORE_BASE64` y `CYBERIA_KEYSTORE_PASSWORD`, en **Settings → Secrets and variables
   → Actions**.
3. Desde la próxima corrida, todas las versiones salen firmadas con esa clave. Las versiones
   anteriores a la clave hay que desinstalarlas una vez.

Sin esos secretos la CI sigue funcionando, pero firma con una clave temporal y lo avisa.

## Estructura

| Carpeta | Qué hay |
| --- | --- |
| `crates/cyberia-core` | Núcleo de la PC: índice de la carpeta con huellas BLAKE3 en caché, manifiesto versionado, certificado propio, QR de un solo uso, API HTTPS (axum + rustls) con rangos y avisos SSE, anuncio mDNS. |
| `crates/cyberia-server` | El mismo núcleo sin interfaz, para la fase 0, depurar la app Android y las pruebas. |
| `desktop/` | App de bandeja (Tauri 2) con interfaz en HTML/TypeScript sin framework. |
| `android/core` | Núcleo del celular en Kotlin puro (JVM): cliente con certificado fijado, motor de sincronización, BLAKE3. Se prueba sin el SDK de Android. |
| `android/app` | App Android: Compose, Room, Media3, WorkManager, servicio de espera, escáner ZXing. |
| `docs/protocolo.md` | La API entre la PC y el celular. |

## Desarrollo

Requisitos: Rust estable, Node 22, JDK 17 y, para la app Android, el SDK de Android (API 35).
En Linux, además, las dependencias de Tauri:

```bash
sudo apt-get install -y libwebkit2gtk-4.1-dev librsvg2-dev libayatana-appindicator3-dev libxdo-dev
```

### PC

```bash
cd desktop && npm ci && npm run tauri dev
```

Instalador de Windows (NSIS):

```bash
cd desktop && npx tauri build --bundles nsis
```

Servidor sin interfaz (muestra el QR en la terminal; comandos por la entrada estándar: `sync`,
`pair`, `unpair`, `status`, `quit`):

```bash
cargo run -p cyberia-server -- --folder ~/Música/Cyberia --pair
```

### Celular

```bash
cd android && ./gradlew :app:assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/`. La versión que se distribuye es la de
release (`./gradlew :app:assembleRelease`), que es la que prueba la CI.

### Pruebas

```bash
cargo test --workspace
```

```bash
cargo build -p cyberia-server && cd android && ./gradlew -p core test
```

La segunda corre el núcleo Kotlin contra el servidor Rust real: emparejamiento, certificado
fijado, descargas que se retoman, mover sin volver a descargar, borrados con confirmación,
avisos SSE y desvinculación. No necesita el SDK de Android.

La CI (`.github/workflows/ci.yml`) corre todo eso, compila el APK y el instalador de Windows, y
además prueba la app en un emulador Android 14 contra el servidor Rust
(`.github/scripts/android-e2e.sh`): se vincula abriendo el enlace del QR, descarga y le informa a
la PC, abre la conexión de espera mientras carga, baja al instante un audio nuevo después de
Sincronizar y reproduce una playlist con la PC apagada. También prueba la actualización: la PC
vuelve con una versión más nueva, el celular la baja, se instala encima y sigue vinculado y con
sus audios. El APK, el instalador y las capturas del emulador quedan como artefactos de cada
ejecución.

En la PC de desarrollo también se puede abrir el enlace del QR en un celular o emulador conectado:

```bash
adb shell "am start -a android.intent.action.VIEW -d 'cyberia://pair?...'"
```

## Estado del MVP

| Funcionalidad | Estado |
| --- | --- |
| Vincular PC y celular con un QR (RF-01 a RF-07) | Hecho |
| App de PC: carpeta, lista por subcarpeta, botón Sincronizar, estado del celular, resultado, bandeja (RF-08 a RF-14) | Hecho |
| Sincronización de una vía, borrados al final, confirmación de borrados grandes, descargas que se retoman, sin espacio (RF-17 a RF-26) | Hecho |
| Reproductor offline con controles de auriculares y pantalla bloqueada (RF-29 a RF-37) | Hecho |
| Modo espera mientras carga, revisión periódica, nunca datos móviles (RF-40 a RF-42) | Hecho |
| Mover o renombrar sin volver a descargar (RF-27, v1) | Hecho de paso: los audios se identifican por huella |
| Reactivar el modo espera al reiniciar el celular (RF-44, v1) | Hecho de paso |

**Medido hasta ahora:** el instalador de Windows pesa 2,4 MB (RNF-02 pide menos de 15). En Linux,
con la ventana cerrada, la app de bandeja usa 28 MB de memoria propia; el resto de lo que muestra
el sistema son librerías compartidas de WebKitGTK. En Windows (WebView2) falta medirlo (RNF-01).

**Falta probar en celulares reales** (criterios de salida de las fases 0 a 3): que la espera
sobreviva una hora en al menos dos marcas, 100 audios sin errores, una playlist entera con el
celular en el bolsillo y la instalación por alguien que no conoce la app.

**Pendiente para v1:** sincronización automática al detectar cambios (RF-28), posición por audio
(RF-38), título y duración desde las etiquetas (RF-39), arrastrar audios a la ventana (RF-15),
arrancar con el sistema (RF-16), apagar el modo espera (RF-43), firma de release y Google Play.
