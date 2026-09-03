#!/usr/bin/env bash
#
# Builds a release, uploads it to that household's own secret directory on the VPS, and publishes
# it: one step, start to finish. See DEPLOYMENT.md and server/README.md for the whole mechanism
# this feeds.
#
#   ./publish.sh --casa papa
#
# The household's document is updated as soon as this finishes — its next poll will see the new
# release — but nothing installs on its own from that alone: the device itself decides when, via
# the icon beside TV or, in simple mode, by holding OK over the channel list. That gesture is the
# safety net a staged-but-unsent step used to be.
#
# One casa at a time, and only ever one: every flavour bakes in a different
# `casa.<id>.remoteConfig.url`, so there is no such thing as one APK for every household. Building
# for all of them is running this once per casa.
#
set -euo pipefail
cd "$(dirname "$0")"

CASA=""
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --casa) CASA="${2:-}"; shift 2 ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"

die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[36m→ %s\033[0m\n' "$*"; }

[[ -f local.properties ]] || die "No hay local.properties. Copia local.properties.example."
prop() { sed -n "s/^$1=//p" local.properties | tail -1; }

[[ -n "$CASA" ]] || die "Uso: ./publish.sh --casa <id>

No hay 'publicar para todas' de un solo golpe: cada APK lleva compilada la URL
del documento de su propia casa, así que publicar es, por fuerza, una casa
cada vez."

PANEL=$(prop panel.url)
PUSER=$(prop panel.user)
PPASS=$(prop panel.password)
SSH=$(prop vps.ssh)
SDK=$(prop sdk.dir)

[[ -n "$PANEL" && -n "$PUSER" && -n "$PPASS" ]] || die "Faltan panel.url, panel.user o panel.password en local.properties."
[[ -n "$SSH" ]] || die "Falta vps.ssh en local.properties.

    vps.ssh=usuario@tu-vps.example.org"

# --- a qué directorio secreto va ---------------------------------------------

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

note "Preguntando al panel por '$CASA'"
curl -sf --max-time 15 -u "$PUSER:$PPASS" "${PANEL%/}/casas" > "$WORK/casas.json" \
    || die "El panel no contestó, o la contraseña no es esa."

# El segmento no se calcula aquí ni se guarda en ningún sitio de este portátil: se le pregunta al
# panel, que es quien lo generó — el mismo criterio que ya usa ./sync-casas.sh con /casas.
REMOTE_DIR=$(python3 - "$WORK/casas.json" "$CASA" <<'PY'
import json, sys, urllib.parse

casas_path, casa_id = sys.argv[1], sys.argv[2]
casas = json.load(open(casas_path))["casas"]
casa = next((c for c in casas if c["id"] == casa_id and c.get("app") == "videoclub"), None)
if not casa:
    sys.exit(1)
# .../videoclub/<segmento>/provider.json  ->  <segmento>
segmento = urllib.parse.urlsplit(casa["url"]).path.rsplit("/", 2)[-2]
print(f"/srv/videoclub/{segmento}")
PY
) || die "No hay ninguna casa de Videoclub con id '$CASA' en el panel.

Si acabas de crearla: ./sync-casas.sh"

note "Casa: $CASA  →  $REMOTE_DIR"

# --- compilar -----------------------------------------------------------

note "Compilando release para la casa '$CASA'"
# Mismo criterio que `flavourOf()` en app/build.gradle.kts: un slug con guiones se convierte en un
# nombre de flavour de Gradle en camelCase (primera palabra en minúscula, el resto capitalizadas, sin
# guiones) — capitalizar solo la primera letra de todo el slug, como hacía esto antes, deja tareas
# como "assembleDavid-hijo-ignasiRelease" que Gradle no reconoce en cuanto la casa tiene más de una
# palabra.
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
[[ -f "$SRC" ]] || die "No se generó $SRC. ¿La casa '$CASA' tiene un flavour de Gradle?"

# El versionCode sale de la fecha de compilación (AAMMDDHH; ver app/build.gradle.kts), y leerlo del
# APK ya compilado es más fiable que volver a calcularlo aquí: es exactamente lo que la app misma
# comparará contra lo que publique este guion.
AAPT2=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort | tail -1)
[[ -n "$AAPT2" ]] || die "No encuentro aapt2. Pon sdk.dir en local.properties."
VERSION=$("$AAPT2" dump badging "$SRC" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
[[ -n "$VERSION" ]] || die "No he podido leer el versionCode de $SRC."

SHA256=$(sha256sum "$SRC" | cut -d' ' -f1)
FILENAME="videoclub-${VERSION}.apk"

note "Versión $VERSION, $(du -h "$SRC" | cut -f1), sha256 ${SHA256:0:12}…"

# --- subir ----------------------------------------------------------------

note "Subiendo a $SSH:$REMOTE_DIR/$FILENAME"
# Al lado y luego mover: una tele que consultara el documento a mitad de la subida vería el APK de
# siempre, no uno a medio escribir — el mismo cuidado que ya tiene push-config.sh con provider.json.
scp -q "$SRC" "$SSH:$REMOTE_DIR/$FILENAME.tmp"
ssh "$SSH" "chmod 644 '$REMOTE_DIR/$FILENAME.tmp' && mv '$REMOTE_DIR/$FILENAME.tmp' '$REMOTE_DIR/$FILENAME'" \
    || die "Falló la subida por SSH."

# --- registrarla con el panel ----------------------------------------------

note "Registrando la versión en el panel"
curl -sf --max-time 15 -u "$PUSER:$PPASS" \
    --data-urlencode "casa=$CASA" \
    --data-urlencode "version=$VERSION" \
    --data-urlencode "sha256=$SHA256" \
    --data-urlencode "filename=$FILENAME" \
    "${PANEL%/}/liberar" >/dev/null \
    || die "Se subió el APK pero el panel no lo registró. Repite ./publish.sh --casa $CASA."

printf '\n\033[32m✓ Versión %s publicada para «%s»\033[0m\n' "$VERSION" "$CASA"
echo "  La casa lo verá en su próximo sondeo. Nada se instala hasta que alguien lo toque allí."
