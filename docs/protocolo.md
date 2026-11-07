# Protocolo PC ↔ celular (API v1)

La PC es el servidor y el celular el cliente. La PC publica una API HTTPS en la red local; el
celular nunca hace de servidor ni recibe conexiones.

## Descubrimiento

- La PC se anuncia por mDNS como `_cyberia._tcp` con su nombre como instancia y estos registros
  TXT: `id` (identificador de la PC) y `v` (versión de la API).
- El celular la busca con `NsdManager` y se queda con la instancia cuyo `id` coincide con el de la
  PC vinculada.
- Orden de búsqueda en el celular: últimas IP conocidas → mDNS → IP escrita a mano.
- Puerto preferido: `47474`. Si está ocupado, la PC usa otro, lo recuerda y lo anuncia.

## Seguridad

- La PC genera su certificado la primera vez (ECDSA P-256, `rcgen`).
- El QR lleva la huella SHA-256 del certificado en DER. El celular solo acepta ese certificado
  (no valida nombre ni cadena); si cambia, no se conecta.
- Después de vincularse, cada pedido lleva `Authorization: Bearer <token>`. La PC guarda solo el
  BLAKE3 del token.
- La PC rechaza conexiones que no vengan de direcciones locales (RFC 1918, enlace local,
  loopback, ULA de IPv6).
- El celular ata sus conexiones a la red Wi-Fi: nunca usa datos móviles.

## QR

```
cyberia://pair?v=1&id=<pcId>&name=<nombre>&hosts=<ip1>,<ip2>&port=<puerto>&fp=<sha256 hex>&code=<código>
```

Los valores van codificados como URL. El código vence a los 5 minutos, sirve una sola vez y se
invalida después de 10 intentos fallidos.

## Rutas

| Método y ruta | Autenticación | Respuesta |
| --- | --- | --- |
| `POST /v1/pair` | Código del QR | `{ token, pcId, pcName, apiVersion }`. `410` si el código venció o ya se usó, `403` si no coincide, `429` si hubo demasiados intentos. |
| `GET /v1/info` | Token | `{ apiVersion, pcId, pcName, manifestVersion }` |
| `GET /v1/manifest` | Token | Manifiesto (abajo). |
| `GET /v1/files/{id}` | Token | El audio. Acepta `Range: bytes=<desde>-` (`206`). `409` si cambió en la PC después de publicarse, `410` si ya no está, `404` si no está en el manifiesto. |
| `GET /v1/events` | Token | Server-Sent Events (abajo). |
| `POST /v1/report` | Token | `204`. Cuerpo: informe (abajo). |
| `DELETE /v1/pair` | Token | `204`. Desvincula el celular. |

Sin token válido: `401` con `{ "error": "unpaired" }`. El celular lo toma como desvinculación.

## Manifiesto

```json
{
  "version": 42,
  "files": [
    { "id": "b3:9f2c…", "path": "Para correr/tema1.mp3", "playlist": "Para correr", "size": 5242880 }
  ]
}
```

- `id`: `b3:` + BLAKE3 del contenido en hexadecimal. Identifica el contenido, no la ruta: mover o
  renombrar un audio no obliga a descargarlo de nuevo.
- `playlist`: la subcarpeta que contiene al audio; `""` para los que están en la raíz.
- La versión sube solo cuando se aprieta Sincronizar y algo cambió. Sin carpeta (por ejemplo, un
  disco desconectado) no se publica nada.

## Avisos (SSE)

Al conectarse, la PC manda la versión actual; después, una cada vez que se aprieta Sincronizar:

```
event: version
data: {"version":42}
```

Si la PC desvincula al celular:

```
event: unpaired
data: {}
```

Sin cambios, la PC manda un comentario vacío cada 60 segundos para mantener viva la conexión. El
celular abre esta conexión solo mientras carga y está en el Wi-Fi.

## Informe

```json
{
  "manifestVersion": 42,
  "downloaded": [{ "id": "b3:…", "path": "Para correr/tema1.mp3" }],
  "deleted": [{ "id": "b3:…", "path": "Viejo/tema.mp3" }],
  "failed": [{ "id": "b3:…", "path": "Podcasts/ep1.mp3", "reason": "No hay espacio en el celular" }],
  "pendingDeletions": 0,
  "noSpace": false,
  "freeBytes": 1234567890,
  "libraryCount": 120,
  "libraryBytes": 987654321,
  "complete": true
}
```

## Sincronización en el celular

1. Pide el manifiesto y lo compara con su biblioteca.
2. Descarga lo que falta, de a un archivo, a `audio/.partial/<hash>.part`, retomando con `Range`
   si ya había una parte.
3. Verifica tamaño y BLAKE3 y recién ahí lo renombra a `audio/<hash>`: nunca queda visible un
   audio a medio copiar.
4. Con todo descargado, borra lo que ya no está. Si sería más de la mitad de la biblioteca, espera
   la confirmación del usuario.
5. Notifica los audios nuevos y manda el informe a la PC.
