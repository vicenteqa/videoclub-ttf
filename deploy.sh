#!/usr/bin/env bash
#
# Build Videoclub and install it on the television over the network.
#
# The box sits behind someone else's router, so the transport is a Tailscale
# address rather than a LAN one: `tv.adb.host` in `local.properties` is the
# machine name on the tailnet. See REMOTE-DEPLOY.md for the one-time setup that
# has to happen with the television in front of you.
#
#   ./deploy.sh --casa padre         build, install, restart
#   ./deploy.sh --casa padre --host tablet   ...on another box, for a quick test
#   ./deploy.sh --logs               attach to the app's logcat and stay there
#   ./deploy.sh --no-build           install whatever is already in build-out/
#   ./deploy.sh --pair H:P C         Android 11+ wireless debugging, one-time pairing
#
set -euo pipefail

cd "$(dirname "$0")"

PKG=com.videoclub.app

# Which household this build is for. The list is not written down here: it comes from the panel via
# ./sync-casas.sh, and Gradle turns each entry into a flavour.
CASA=""
HOST_OVERRIDE=""
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --casa) CASA="${2:-}"; shift 2 ;;
        --host) HOST_OVERRIDE="${2:-}"; shift 2 ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"

die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[36m→ %s\033[0m\n' "$*"; }

# --- configuration ----------------------------------------------------------

[[ -f local.properties ]] || die "No hay local.properties. Copia local.properties.example."

prop() { sed -n "s/^$1=//p" local.properties | tail -1; }

# Las casas configuradas, tal cual las dejó ./sync-casas.sh.
mapfile -t CASAS < <(sed -n 's/^casa\.\([^.]*\)\.remoteConfig\.url=.*/\1/p' local.properties)

if [[ ${#CASAS[@]} -gt 0 ]]; then
    if [[ -z "$CASA" ]]; then
        [[ ${#CASAS[@]} -eq 1 ]] && CASA="${CASAS[0]}" || die "Hay varias casas. Elige una:
$(printf '    ./deploy.sh --casa %s\n' "${CASAS[@]}")"
    fi
    printf '%s\n' "${CASAS[@]}" | grep -qx "$CASA" || die "No conozco la casa '$CASA'. Hay:
$(printf '    %s\n' "${CASAS[@]}")

Si acabas de crearla en el panel: ./sync-casas.sh"
fi

APK="build-out/videoclub${CASA:+-$CASA}.apk"

# La tele de cada casa se declara aparte y no se hereda. Heredarla significa que un
# `--casa suegros` despistado instala en el televisor de tu padre, y eso no lo arregla ningún
# mensaje de error posterior. --host está para probar en un cacharro tuyo, y hay que escribirlo.
if [[ -n "$HOST_OVERRIDE" ]]; then
    HOST="$HOST_OVERRIDE"
    note() { printf '\033[36m→ %s\033[0m\n' "$*"; }
elif [[ -n "$CASA" ]]; then
    HOST=$(prop "casa.$CASA.tv.adb.host")
else
    HOST=$(prop tv.adb.host)
fi
PORT=$(prop "casa.$CASA.tv.adb.port"); [[ -n "$PORT" ]] || PORT=$(prop tv.adb.port)
SDK=$(prop sdk.dir)
: "${PORT:=5555}"

[[ -n "$HOST" ]] || die "No sé a qué aparato instalar la casa '$CASA'.

Añade a local.properties el nombre Tailscale de su televisor:

    casa.$CASA.tv.adb.host=simpletv-salon

o, para una prueba en otro cacharro:

    ./deploy.sh --casa $CASA --host $(prop tv.adb.host)"

[[ -n "$CASA" ]] && note "Casa: $CASA  →  $HOST"

# The SDK's adb, not whatever an unrelated distro package put on the PATH: a
# server/client version mismatch makes adb kill and restart the daemon on every
# invocation, which drops the network device.
ADB="${SDK:+$SDK/platform-tools/adb}"
[[ -x "${ADB:-}" ]] || ADB=$(command -v adb) || die "No encuentro adb. Pon sdk.dir en local.properties."

TARGET="$HOST:$PORT"

# --- pairing (Android 11+) --------------------------------------------------

if [[ "${1:-}" == "--pair" ]]; then
    [[ $# -eq 3 ]] || die "Uso: ./deploy.sh --pair <host:puerto-de-emparejamiento> <codigo>"
    # The pairing port is not the connect port and it changes every time the
    # dialog is opened; both come off the television's screen.
    exec "$ADB" pair "$2" "$3"
fi

# --- connect ----------------------------------------------------------------

connect() {
    "$ADB" connect "$TARGET" >/dev/null 2>&1 || true
    "$ADB" -s "$TARGET" get-state >/dev/null 2>&1
}

note "Conectando con $TARGET"
if ! connect; then
    sleep 2
    connect || die "No hay respuesta de $TARGET.

  Comprueba, en este orden:
    1. tailscale status          — ¿está la tele online en el tailnet?
    2. En la tele: Ajustes → Opciones de desarrollador → Depuración por red.
       Muchas cajas la desactivan al reiniciar; hay que volver a encenderla.
    3. Android 11+: usa 'Depuración inalámbrica' con emparejamiento:
       ./deploy.sh --pair <host:puerto> <codigo>"
fi

STATE=$("$ADB" -s "$TARGET" get-state 2>/dev/null || echo unknown)
[[ "$STATE" == device ]] || die "La tele responde pero el estado es '$STATE'.
Si pone 'unauthorized', hay que aceptar el diálogo de la huella RSA en la
pantalla del televisor — eso sí requiere que alguien esté delante, una vez."

note "Conectado: $("$ADB" -s "$TARGET" shell getprop ro.product.model | tr -d '\r') / Android $("$ADB" -s "$TARGET" shell getprop ro.build.version.release | tr -d '\r')"

# --- logs only --------------------------------------------------------------

if [[ "${1:-}" == "--logs" ]]; then
    PID=$("$ADB" -s "$TARGET" shell pidof "$PKG" | tr -d '\r')
    [[ -n "$PID" ]] || die "La app no está corriendo en la tele."
    note "logcat de $PKG (pid $PID) — Ctrl-C para salir"
    exec "$ADB" -s "$TARGET" logcat --pid="$PID"
fi

# --- build ------------------------------------------------------------------

if [[ "${1:-}" != "--no-build" ]]; then
    if [[ -n "$CASA" ]]; then
        note "Compilando release para la casa '$CASA'"
        # Mismo criterio que `flavourOf()` en app/build.gradle.kts y que publish.sh: un slug con
        # guiones se convierte en un nombre de flavour de Gradle en camelCase, no en un slug con solo
        # su primera letra en mayúscula — eso deja tareas que Gradle no reconoce en cuanto la casa
        # tiene más de una palabra (p. ej. "david-hijo-ignasi").
        FLAVOUR=""
        IFS='-' read -ra PARTES <<< "$CASA"
        for i in "${!PARTES[@]}"; do
            parte="${PARTES[$i]}"
            if [[ $i -eq 0 ]]; then
                FLAVOUR="${FLAVOUR}${parte}"
            else
                FLAVOUR="${FLAVOUR}$(tr '[:lower:]' '[:upper:]' <<<"${parte:0:1}")${parte:1}"
            fi
        done
        CAP="$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOUR:0:1}")${FLAVOUR:1}"
        ./gradlew --quiet ":app:assemble${CAP}Release"
        SRC="app/build/outputs/apk/$FLAVOUR/release/app-$FLAVOUR-release.apk"
    else
        note "Compilando release"
        ./gradlew --quiet :app:assembleRelease
        SRC="app/build/outputs/apk/release/app-release.apk"
    fi
    mkdir -p build-out
    cp "$SRC" "$APK"
fi

[[ -f "$APK" ]] || die "No existe $APK. Lanza ./deploy.sh sin --no-build."
note "APK: $(du -h "$APK" | cut -f1), $(date -r "$APK" '+%H:%M:%S')"

# --- install ----------------------------------------------------------------

# No hay comprobación en tiempo de ejecución aquí, y no la hay porque no habría nada honesto que
# comprobar: las APKs de todas las casas comparten paquete e icono, y ninguna instalada dice a qué
# casa pertenece. Lo que protege es estructural: el televisor sale de `casa.$CASA.tv.adb.host`, así
# que elegir la casa elige la caja.
note "Instalando en $TARGET${CASA:+ (casa: $CASA)}"
OUT=$("$ADB" -s "$TARGET" install -r "$APK" 2>&1) || true
echo "$OUT" | sed 's/^/    /'

if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$OUT"; then
    die "Firma distinta a la del APK instalado.

La tele tiene todavía la build firmada con la clave de debug. Android no deja
sustituirla por una firmada con la keystore de release sin desinstalar antes:

    $ADB -s $TARGET uninstall $PKG && ./deploy.sh --no-build

Hazlo UNA vez (se pierde la caché local; la cuenta vive en el VPS y se vuelve
a bajar sola). A partir de ahí todas las actualizaciones son directas."
fi

if grep -q INSTALL_FAILED_VERSION_DOWNGRADE <<<"$OUT"; then
    die "La tele tiene un versionCode mayor que el que acabas de compilar.
Sube versionCode en app/build.gradle.kts."
fi

grep -q '^Success' <<<"$OUT" || die "La instalación falló (arriba el motivo)."

# --- restart ----------------------------------------------------------------

note "Reiniciando la app"
"$ADB" -s "$TARGET" shell am force-stop "$PKG"
"$ADB" -s "$TARGET" shell am start -n "$PKG/.MainActivity" >/dev/null

printf '\n\033[32m✓ Videoclub%s actualizada en %s\033[0m\n' "${CASA:+ (casa: $CASA)}" "$HOST"
echo "  Logs en vivo:  ./deploy.sh --logs"
