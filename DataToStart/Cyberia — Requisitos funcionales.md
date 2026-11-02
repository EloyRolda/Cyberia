# Cyberia — Requisitos funcionales

Nov 2, 2026 · @Eloy

Cyberia v1 son dos apps que se hablan por el Wi-Fi de casa: una app de bandeja en la PC (Tauri + Rust) que publica una carpeta de audios, y una app Android nativa (Kotlin) que la descarga y la reproduce sin conexión. No hay servidor en internet, cuentas ni costos de infraestructura.

## Alcance y MVP

El MVP resuelve un solo recorrido: dejar audios en la carpeta de la PC, que el celular los baje mientras carga, y escucharlos afuera con auriculares. Todo lo que no sirve a ese recorrido pasa a v1 o queda para después.

| Funcionalidad | Fase |
| --- | --- |
| Vincular PC y celular con un QR | MVP |
| Botón Sincronizar en la PC | MVP |
| Espera con notificación fija y descarga al instante | MVP |
| Revisión periódica y al empezar a cargar | MVP |
| Playlists por subcarpeta | MVP |
| Reproductor offline con controles de auriculares | MVP |
| Confirmación en la PC de lo que llegó al celular | MVP |
| Sincronización automática al detectar cambios | v1 |
| Mover o renombrar audios sin volver a descargarlos | v1 |
| Posición guardada por cada audio | v1 |
| Título y duración desde las etiquetas del archivo | v1 |
| Arrastrar audios a la ventana de la PC | v1 |
| Publicación en Google Play | v1 |
| Sincronizar fuera de casa | Después |
| Enviar grabaciones del celular a la PC | Después |
| Varios celulares por PC | Después |
| Editar playlists desde el celular | Después |
| Convertir audios pesados a un formato liviano | Después |
| iPhone | Fuera de alcance |

**Supuestos**

- La PC y el celular comparten el Wi-Fi de casa al sincronizar. Fuera de casa, el celular solo reproduce.
- Un usuario, una PC y un celular con Android 8.0 o superior.
- La PC está prendida y Cyberia corre en la bandeja cuando se quiere sincronizar.
- No hay servidor en internet, cuentas ni nube.

## Arquitectura

Son dos apps en dos carriles: la PC indexa y sirve la carpeta; el celular sincroniza, guarda y reproduce.

&#91;embedded content: arquitectura · PC y celular por el Wi-Fi de casa\]

El celular nunca hace de servidor: solo abre conexiones hacia la PC, que es la que está siempre enchufada y tiene la carpeta.

## Cómo y cuándo se sincroniza

La PC nunca empuja archivos: avisa que hay cambios y el celular los descarga. Así el celular solo abre conexiones hacia la PC y nunca hace de servidor.

**Cuándo**

| Disparador | Qué pasa | Fase |
| --- | --- | --- |
| Apretás Sincronizar en la PC | La PC publica una versión nueva del manifiesto y avisa al celular conectado, que descarga en el momento. | MVP |
| El celular empieza a cargar | Abre la conexión de avisos con la PC y baja lo pendiente. | MVP |
| Revisión periódica | Cada 15 minutos (el mínimo de Android), solo con Wi-Fi, pide el manifiesto y baja lo pendiente. | MVP |
| Abrís la app o tirás para actualizar | Revisión inmediata. | MVP |
| Cambia algo en la carpeta, con sincronización automática activada | La PC espera 5 segundos sin cambios nuevos y hace lo mismo que el botón. | v1 |

**Cómo**

1. La PC recorre la carpeta y arma el manifiesto: la huella, la ruta, la playlist y el tamaño de cada audio, más un número de versión.
2. La PC avisa al celular que hay una versión nueva.
3. El celular pide el manifiesto y lo compara con su base local.
4. Descarga lo nuevo o cambiado, de a un archivo, a un archivo temporal.
5. Verifica tamaño y huella. Si coinciden, le pone el nombre final; si la descarga se cortó, la retoma desde donde quedó.
6. Recién con todo descargado, borra lo que ya no está en el manifiesto. Si eso borraría más de la mitad de la biblioteca, pide confirmación.
7. Actualiza las playlists, muestra la notificación con los audios nuevos e informa el resultado a la PC.

La huella de cada archivo se calcula una sola vez y se guarda con su tamaño y fecha; solo se recalcula si alguno de los dos cambia.

## Requisitos funcionales

Son 44 requisitos: 36 entran en el MVP y 8 quedan para v1.

### Emparejamiento

| ID | Requisito | Fase |
| --- | --- | --- |
| RF-01 | La app de PC muestra un código QR para vincular un celular. | MVP |
| RF-02 | El código vence a los 5 minutos y sirve una sola vez. | MVP |
| RF-03 | La app del celular escanea el QR y queda vinculada a esa PC, sin cuentas ni contraseñas. | MVP |
| RF-04 | La vinculación se guarda: no hay que repetirla. | MVP |
| RF-05 | La PC y el celular se encuentran solos en la red, aunque cambie la IP de alguno. | MVP |
| RF-06 | Las transferencias viajan cifradas y solo entre dispositivos vinculados. | MVP |
| RF-07 | El usuario puede desvincular el celular desde cualquiera de las dos apps. | MVP |

### Aplicación de PC

| ID | Requisito | Fase |
| --- | --- | --- |
| RF-08 | La app crea una carpeta de Cyberia o permite elegir una existente. | MVP |
| RF-09 | Muestra los audios de la carpeta, agrupados por subcarpeta. | MVP |
| RF-10 | Tiene un botón Sincronizar que avisa al celular en el momento. | MVP |
| RF-11 | Muestra si el celular está conectado. | MVP |
| RF-12 | Al terminar, confirma cuántos audios llegaron al celular; si algo falla, indica qué audio y por qué. | MVP |
| RF-13 | Acepta MP3, M4A/AAC, WAV, FLAC y OGG. Los archivos no compatibles se ignoran y se marcan en la lista. | MVP |
| RF-14 | Vive en la bandeja del sistema: cerrar la ventana no cierra la app. | MVP |
| RF-15 | Permite arrastrar audios a la ventana; se copian a la carpeta. | v1 |
| RF-16 | Puede arrancar junto con el sistema. | v1 |

### Sincronización

| ID | Requisito | Fase |
| --- | --- | --- |
| RF-17 | La sincronización va en una sola dirección: de la PC al celular. | MVP |
| RF-18 | Un audio nuevo o modificado en la PC se descarga en el celular. | MVP |
| RF-19 | Un audio eliminado de la carpeta se elimina del celular, después de completar las descargas. | MVP |
| RF-20 | Si un cambio borraría más de la mitad de la biblioteca del celular, se pide confirmación antes. | MVP |
| RF-21 | Cada subcarpeta es una playlist con su nombre; los audios se ordenan por nombre de archivo. | MVP |
| RF-22 | Si el celular no está disponible, los cambios quedan pendientes para la próxima conexión. | MVP |
| RF-23 | Una descarga cortada se retoma desde donde quedó. Un audio aparece en el celular solo cuando llegó completo y verificado. | MVP |
| RF-24 | Al terminar, el celular muestra una notificación con la cantidad de audios nuevos. | MVP |
| RF-25 | El celular informa el resultado a la PC. | MVP |
| RF-26 | Si el celular no tiene espacio, ambas apps avisan y no se borra nada para hacer lugar. | MVP |
| RF-27 | Mover o renombrar un audio en la PC no lo vuelve a descargar. | v1 |
| RF-28 | Sincronización automática opcional: la PC avisa sola 5 segundos después del último cambio en la carpeta. | v1 |

### Aplicación del celular

| ID | Requisito | Fase |
| --- | --- | --- |
| RF-29 | Muestra los audios descargados y las playlists. | MVP |
| RF-30 | Reproduce sin conexión a internet. | MVP |
| RF-31 | Controles: reproducir, pausar, anterior, siguiente y aleatorio. | MVP |
| RF-32 | Sigue sonando con la pantalla apagada o con otra app abierta. | MVP |
| RF-33 | Se controla desde la pantalla bloqueada y la notificación de reproducción. | MVP |
| RF-34 | Responde a los botones de auriculares con cable y Bluetooth. | MVP |
| RF-35 | Pausa sola cuando se desconectan los auriculares. | MVP |
| RF-36 | Retoma el último audio donde quedó. | MVP |
| RF-37 | Permite revisar a mano si hay audios nuevos (tirar para actualizar). | MVP |
| RF-38 | Recuerda la posición de cada audio por separado. | v1 |
| RF-39 | Muestra título y duración desde las etiquetas del archivo, o el nombre si no tiene. | v1 |

### Segundo plano

| ID | Requisito | Fase |
| --- | --- | --- |
| RF-40 | Modo espera: con una notificación fija, la app mantiene abierta la conexión de avisos mientras el celular carga y descarga al instante. | MVP |
| RF-41 | Sin modo espera, o sin cargar, la app revisa cada 15 minutos con Wi-Fi y al empezar a cargar. | MVP |
| RF-42 | La app nunca usa datos móviles. | MVP |
| RF-43 | El usuario puede apagar el modo espera y quedarse solo con la revisión periódica. | v1 |
| RF-44 | El modo espera se reactiva solo al reiniciar el celular. | v1 |

## Requisitos no funcionales

La regla general: Cyberia tiene que pasar desapercibida en la PC y no gastar batería en el celular cuando no carga.

| ID | Requisito | Fase |
| --- | --- | --- |
| RNF-01 | Con la ventana cerrada y en la bandeja, la app de PC usa menos de 50 MB de RAM y casi nada de CPU. | MVP |
| RNF-02 | El instalador de PC pesa menos de 15 MB. | MVP |
| RNF-03 | La conexión de avisos se abre solo mientras el celular carga; sin cargar, la app solo hace revisiones periódicas. | MVP |
| RNF-04 | La huella de cada archivo se calcula una sola vez; nada se re-escanea entero en cada sincronización. | MVP |
| RNF-05 | La PC solo acepta conexiones desde la red local y de celulares vinculados. | MVP |
| RNF-06 | El certificado de la PC se fija en el celular al escanear el QR; si cambia, el celular no se conecta. | MVP |
| RNF-07 | Sin telemetría ni cuentas: ningún dato sale de la red de casa. | MVP |
| RNF-08 | Nunca queda visible un audio a medio copiar, aunque se corte el Wi-Fi o se apague la PC. | MVP |
| RNF-09 | PC: Windows 10 y 11 primero; macOS y Linux sin cambios de diseño. | MVP |
| RNF-10 | Celular: Android 8.0 o superior. | MVP |
| RNF-11 | Los audios se guardan en el almacenamiento propio de la app, sin pedir permisos de archivos. | MVP |

## Interconexión

La PC es el servidor y el celular el cliente: la PC publica una API HTTPS en la red local y el celular la consulta. Es el mismo enfoque del [protocolo abierto de LocalSend](https://github.com/localsend/protocol), que transfiere archivos en la red local sin servidores externos.

**Descubrimiento**

- La PC se anuncia por mDNS como servicio `_cyberia._tcp`, con su nombre y puerto.
- El celular la busca con el descubrimiento nativo de Android (NsdManager).
- Si el router bloquea mDNS, el celular prueba la última IP conocida y, como último recurso, una IP escrita a mano.

**Emparejamiento**

1. La PC genera su propio certificado la primera vez que se instala.
2. El QR lleva el nombre de la PC, su IP y puerto, la huella del certificado y un código de un solo uso.
3. El celular se conecta por HTTPS, verifica que el certificado coincida con la huella del QR y envía el código.
4. La PC le entrega un token propio para ese celular, que va en cada pedido siguiente.

**API de la PC**

| Método y ruta | Para qué |
| --- | --- |
| `POST /v1/pair` | Cambiar el código del QR por un token. |
| `GET /v1/info` | Nombre de la PC y versión actual del manifiesto. |
| `GET /v1/manifest` | Lista completa de audios: huella, ruta, playlist y tamaño. |
| `GET /v1/files/{huella}` | Descargar un audio; acepta rangos para retomar descargas cortadas. |
| `GET /v1/events` | Conexión de avisos abierta: la PC escribe la versión nueva cuando hay cambios. |
| `POST /v1/report` | El celular informa qué descargó, qué borró y qué falló. |
| `DELETE /v1/pair` | Desvincular el celular. |

Los avisos usan Server-Sent Events y no WebSocket: van en una sola dirección, son HTTP común y son más simples de implementar y depurar. Sin cambios, la PC envía una línea vacía cada 60 segundos para mantener viva la conexión.

Ejemplo de manifiesto:

```json
{
  "version": 42,
  "files": [
    { "id": "b3:9f2c…", "path": "Para correr/tema1.mp3", "playlist": "Para correr", "size": 5242880 }
  ]
}
```

## Tecnologías

La combinación más barata en recursos es Tauri 2 (Rust) en la PC y Kotlin nativo en Android. Las dos partes difíciles, una app que vive todo el día en la bandeja y un servicio de espera en Android, salen mejor con las herramientas nativas de cada lado.

**Opciones evaluadas**

| Opción | Dónde | Consumo | A favor | En contra |
| --- | --- | --- | --- | --- |
| Tauri 2 (Rust + WebView del sistema) | PC | Instalador de 2–10 MB; 30–50 MB de RAM en reposo | Liviano, bandeja incluida, Windows, macOS y Linux | Hay que escribir algo de Rust |
| Electron | PC | Instalador de 80–150 MB; 150–300 MB de RAM en reposo | Todo en JavaScript, muy maduro | Demasiado pesado para algo abierto todo el día |
| Flutter | PC y Android | Intermedio | Un solo código para las dos apps; LocalSend está hecho así | Segundo plano y reproductor dependen de plugins |
| Kotlin + Compose Multiplatform | PC y Android | En la PC corre sobre la JVM, que usa más RAM | Un solo lenguaje y lógica compartida | App de PC más pesada |
| Kotlin nativo (Compose + Media3) | Android | El más bajo | Acceso directo a servicios, WorkManager y Media3 | Solo sirve para Android |
| Tauri 2 en Android | Android | Bajo | Mismo código que la PC | El servicio de espera y el reproductor igual necesitan Kotlin |

Cifras de Tauri y Electron: [OpenReplay, diciembre de 2025](https://blog.openreplay.com/comparing-electron-tauri-desktop-applications/). Varían según la app, pero la diferencia se mantiene.

**Stack elegido**

| Lado | Pieza | Tecnología | Para qué |
| --- | --- | --- | --- |
| PC | Ventana y bandeja | Tauri 2 + HTML/TypeScript sin framework | Interfaz mínima; al cerrar la ventana se libera el WebView y queda solo el proceso Rust |
| PC | Servidor | axum + rustls | API HTTPS y descargas con rangos |
| PC | Certificado | rcgen | Certificado propio generado al instalar |
| PC | Vigilar la carpeta | notify | Detectar audios nuevos, cambiados o borrados |
| PC | Descubrimiento | mdns-sd | Anunciar la PC en la red |
| PC | Huellas | BLAKE3 | Identificar cada audio rápido |
| PC | QR | qrcode | Emparejamiento |
| Android | Interfaz | Jetpack Compose | Lista de audios y reproductor |
| Android | Reproductor | Media3 (ExoPlayer + MediaSessionService) | Segundo plano, pantalla bloqueada, auriculares, pausa al desconectar |
| Android | Red | OkHttp + okhttp-sse | Descargas, certificado fijado y avisos |
| Android | Descubrimiento | NsdManager | Encontrar la PC por mDNS |
| Android | Tareas | WorkManager | Revisión cada 15 minutos y al cargar, solo con Wi-Fi |
| Android | Modo espera | Servicio en primer plano tipo connectedDevice | Mantener abierta la conexión de avisos |
| Android | Base local | Room | Audios, huellas, playlists y posiciones |
| Android | Escáner QR | ZXing | Leer el QR sin depender de Google Play Services |

El modo espera usa el tipo connectedDevice y no dataSync: en Android 15, los servicios dataSync pueden correr [como máximo 6 horas cada 24](https://developer.android.com/develop/background-work/services/fg-service-timeout) y [no pueden arrancar al encender el celular](https://developer.android.com/about/versions/15/changes/foreground-service-types). connectedDevice pide declarar un permiso de red, como [CHANGE\_NETWORK\_STATE](https://developer.android.com/reference/kotlin/android/content/pm/ServiceInfo), que se otorga sin preguntarle al usuario.

**Costo en plata:** todas las herramientas son gratuitas y de código abierto, y no hay servidores que pagar. El único gasto previsto es la cuenta de desarrollador de Google Play, un pago único de USD 25, recién en v1.

## Plan de fases

El MVP se construye en cuatro fases, empezando por lo más riesgoso: que Android mantenga viva la espera. Cada fase termina cuando cumple su criterio de salida.

1. **Fase 0, prueba de concepto.** Un servidor mínimo en la PC y una app Android mínima con el modo espera.
   - Sale cuando un celular cargando recibe un archivo una hora después de la última vez que se abrió la app, en al menos dos marcas distintas.
2. **Fase 1, sincronización.** Emparejamiento por QR, manifiesto, descargas que se retoman, borrados y revisión periódica.
   - Sale cuando 100 audios llegan sin errores y una descarga cortada a la mitad se completa sola.
3. **Fase 2, reproductor.** Media3, playlists, controles de auriculares y de pantalla bloqueada.
   - Sale cuando se escucha una playlist entera con el celular en el bolsillo, sin tocar la pantalla.
4. **Fase 3, pulido del MVP.** Bandeja, confirmación en la PC, mensajes de error e instaladores. La app Android se distribuye como APK directo, sin pasar todavía por Google Play.
   - Sale cuando alguien que no conoce la app la instala, la vincula y escucha un audio en menos de 5 minutos.
5. **Fase 4, v1.** Las funciones marcadas v1 y la publicación en Google Play.

## Riesgos y preguntas abiertas

El riesgo mayor es que el fabricante del celular cierre la app en segundo plano; por eso es lo primero que se prueba.

| Riesgo | Mitigación |
| --- | --- |
| Algunas marcas cierran apps en segundo plano aunque tengan un servicio en primer plano. | Probarlo en la fase 0; guiar al usuario para excluir Cyberia de la optimización de batería; la revisión periódica queda de respaldo. |
| Google Play puede no aceptar connectedDevice para este uso. | El MVP se distribuye como APK; si Play lo rechaza, esa versión usa solo la revisión periódica. |
| Routers que aíslan dispositivos, o redes de invitados, impiden que la PC y el celular se vean. | La app detecta el caso y lo explica; queda la conexión manual por IP. |
| El firewall de Windows pide permiso la primera vez. | Explicarlo en el instalador y abrir el puerto solo en redes privadas. |
| La PC suspendida no responde. | La app evita la suspensión solo durante una transferencia; si la PC duerme, el celular sincroniza cuando vuelva. |
| Si la carpeta está en un disco desconectado, aparece vacía y el espejo borraría todo. | Sin carpeta, la PC no publica manifiesto; además, RF-20 pide confirmación ante borrados grandes. |

**Preguntas abiertas**

- ¿Empezar solo por Windows? El diseño sirve para los tres sistemas, pero probar uno acota el trabajo.
- ¿Hace falta poder ocultar o borrar audios desde el celular? En el MVP no se puede.
- ¿Está libre el nombre Cyberia en Google Play? Revisarlo antes de la fase 4.

## Auditoría del borrador anterior

El borrador describía bien qué se ve, pero no cómo funciona por dentro; estas son las 11 correcciones.

| Hallazgo | Corrección |
| --- | --- |
| La espera "mientras carga" no era viable tal cual: Android no deja arrancar un servicio en primer plano desde segundo plano al enchufar el cargador. | El servicio queda siempre activo; lo que se abre y cierra con la carga es la conexión de avisos (RF-40, RNF-03). |
| No definía quién es servidor y quién cliente. | La PC sirve y el celular consulta (Interconexión). |
| No decía cuándo se sincroniza. | Tabla de disparadores (Cómo y cuándo se sincroniza). |
| La confirmación en la PC no tenía de dónde salir. | El celular informa el resultado (RF-25). |
| Un borrado en la PC se copiaba sin red de seguridad, incluso si la carpeta desaparecía. | Borrados al final, confirmación ante borrados grandes y protección si falta la carpeta (RF-19, RF-20). |
| Mover un audio de subcarpeta obligaba a descargarlo de nuevo. | Identificación por huella (RF-27). |
| Todos los requisitos pesaban igual. | Fase asignada a cada requisito. |
| No había requisitos de recursos ni de seguridad. | Sección de requisitos no funcionales. |
| No decía qué pasa al cerrar la ventana de la PC. | La app vive en la bandeja (RF-14). |
| El QR no vencía. | Vence a los 5 minutos y sirve una vez (RF-02). |
| El tipo de servicio de espera no estaba definido, y el obvio, dataSync, se corta a las 6 horas en Android 15. | Se usa connectedDevice (Tecnologías). |

## Fuentes

- [Android: límite de tiempo de los servicios en primer plano](https://developer.android.com/develop/background-work/services/fg-service-timeout)
- [Android 15: cambios en los tipos de servicio en primer plano](https://developer.android.com/about/versions/15/changes/foreground-service-types)
- [Android: referencia de ServiceInfo, tipo connectedDevice](https://developer.android.com/reference/kotlin/android/content/pm/ServiceInfo)
- [OpenReplay: Comparing Electron and Tauri for Desktop Applications, diciembre de 2025](https://blog.openreplay.com/comparing-electron-tauri-desktop-applications/)
- [LocalSend Protocol v2.1](https://github.com/localsend/protocol)
