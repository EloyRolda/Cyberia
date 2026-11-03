#!/usr/bin/env bash
# Prueba de punta a punta en el emulador: el servidor Rust real corre en el runner y la app del
# celular se vincula, descarga, informa y recibe un aviso al instante por la conexión de espera.
# Uso: android-e2e.sh <apk> <cyberia-server>
set -euo pipefail
# Escribir al servidor cuando ya terminó no tiene que matar el script.
trap '' PIPE

APK="$1"
SERVER="$2"
WORK="$(mktemp -d)"
OUT="${E2E_OUT:-e2e-out}"
mkdir -p "$OUT" "$WORK/music/Para correr" "$WORK/music/Podcasts"

# Audios WAV reales (un tono de 2 segundos) para que el reproductor los acepte.
wav() {
  python3 - "$1" "$2" <<'PY'
import math, struct, sys, wave
path, freq = sys.argv[1], float(sys.argv[2])
with wave.open(path, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
    w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * freq * i / 22050))) for i in range(22050 * 2)))
PY
}
wav "$WORK/music/Para correr/01 Arranque.wav" 440
wav "$WORK/music/Para correr/02 Subida.wav" 494
wav "$WORK/music/Podcasts/Episodio 1.wav" 523

# Servidor con comandos por un FIFO.
mkfifo "$WORK/server.in"
"$SERVER" --folder "$WORK/music" --data-dir "$WORK/pc" --port 47474 --no-mdns --no-qr \
  < "$WORK/server.in" > "$OUT/server.log" 2>&1 &
SERVER_PID=$!
exec 3> "$WORK/server.in"
trap 'echo quit >&3 || true; kill $SERVER_PID 2>/dev/null || true; adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true' EXIT

shot() { # shot <nombre>: captura de pantalla en $OUT (y en el log si E2E_PRINT_SCREENSHOTS=1)
  adb exec-out screencap -p > "$OUT/$1.png"
  if [ -n "${E2E_PRINT_SCREENSHOTS:-}" ] && command -v convert >/dev/null; then
    echo "SCREENSHOT $1 $(convert "$OUT/$1.png" -resize 432x -quality 70 jpg:- | base64 -w0)"
  fi
}

tap_desc() { # tap_desc <descripción>: toca el elemento con esa descripción de accesibilidad
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml "$WORK/ui.xml" >/dev/null
  local xy
  xy="$(python3 - "$WORK/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get("content-desc") == sys.argv[2]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)"
  [ -n "$xy" ] || { echo "No encontré «$1» en la pantalla"; return 1; }
  adb shell input tap $xy
}

wait_for() { # wait_for <segundos> <comando...>
  local deadline=$((SECONDS + $1)); shift
  until "$@"; do
    if (( SECONDS > deadline )); then return 1; fi
    sleep 2
  done
}

status() {
  echo status >&3
  sleep 1
  grep '^STATUS ' "$OUT/server.log" | tail -1 | cut -c8-
}

report_field() { # report_field <expresión python sobre r>
  status | python3 -c "
import json, sys
s = json.load(sys.stdin)
r = s.get('lastReport') or {}
print($1)
"
}

wait_for 20 grep -q '^LISTENING' "$OUT/server.log"
echo sync >&3
wait_for 20 grep -q '^SYNCED 1' "$OUT/server.log"
echo pair >&3
wait_for 20 grep -q '^PAIR_URI' "$OUT/server.log"
# Desde el emulador, la PC es 10.0.2.2.
URI="$(grep '^PAIR_URI ' "$OUT/server.log" | tail -1 | cut -c10- | sed 's/hosts=[^&]*/hosts=10.0.2.2/')"
echo "QR: $URI"

adb install -r "$APK"
adb shell pm grant app.cyberia android.permission.POST_NOTIFICATIONS || true
adb shell dumpsys battery set ac 1 || true
adb logcat -c

echo "== Vinculación por enlace y primera sincronización"
adb shell "am start -W -a android.intent.action.VIEW -d '$URI' --ez cyberia.test.autoConfirm true -n app.cyberia/.ui.MainActivity"
# Puede haber más de un informe (al abrir la app, al cargar...): se mira el estado final.
check_first() { [ "$(report_field "r.get('libraryCount') == 3 and bool(r.get('complete'))")" = "True" ]; }
if ! wait_for 120 check_first; then
  echo "El celular no informó 3 audios descargados"; status; exit 1
fi
echo "OK: llegaron 3 audios"
shot 01-biblioteca

echo "== Aviso al instante con el modo espera (celular cargando)"
python3 - "$WORK/music/Podcasts/Episodio 2.wav" <<'PY'
import math, struct, sys, wave
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
    w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 587 * i / 22050))) for i in range(22050)))
PY
# Da tiempo a que el modo espera abra la conexión de avisos.
check_connected() { [ "$(status | python3 -c 'import json,sys; print(json.load(sys.stdin)["phoneConnected"])')" = "True" ]; }
if ! wait_for 90 check_connected; then
  echo "El modo espera no abrió la conexión de avisos"; status; exit 1
fi
echo sync >&3
check_push() { [ "$(report_field "r.get('manifestVersion') == 2 and r.get('libraryCount') == 4 and bool(r.get('complete'))")" = "True" ]; }
if ! wait_for 60 check_push; then
  echo "El celular no descargó el audio nuevo después del aviso"; status; exit 1
fi
echo "OK: el audio nuevo llegó por el aviso"
shot 02-audio-nuevo

echo "== Reproducción"
# Reproduce la playlist «Para correr» desde la app, con la PC apagada (RF-30).
kill $SERVER_PID 2>/dev/null || true
tap_desc "Reproducir Para correr"
playing() { adb shell dumpsys media_session | grep -q "state=PlaybackState {state=3"; }
if ! wait_for 30 playing; then
  echo "El reproductor no empezó a sonar"; adb shell dumpsys media_session | head -60; exit 1
fi
echo "OK: suena la playlist sin la PC"
sleep 2
shot 03-reproductor
adb shell cmd statusbar expand-notifications || true
sleep 2
shot 04-notificaciones
adb shell cmd statusbar collapse || true

if adb logcat -d | grep -A 20 "FATAL EXCEPTION" | grep -q "app.cyberia"; then
  echo "La app se cerró con un error"; adb logcat -d | grep -A 30 "FATAL EXCEPTION"; exit 1
fi
echo "Todo OK"
