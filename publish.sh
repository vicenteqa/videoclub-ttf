#!/usr/bin/env bash
#
# Builds a release, uploads it to the households' secret directories on the VPS, and publishes it:
# one step, start to finish. See DEPLOYMENT.md and the videoclub-server README for the whole
# mechanism this feeds.
#
#   ./publish.sh --casa vicente
#   ./publish.sh --casa vicente --casa manel        (o --casa "vicente manel")
#   ./publish.sh --todas
#
# Qué APK recibe cada casa no se elige aquí: sale de la casa. Una casa simple recibe el suyo, con la
# URL de su documento compilada dentro; cualquier otra recibe `general`, que la primera vez pregunta de
# qué casa es — y que, en un aparato que ya tenía la app, ya lo sabe. Cada flavour que haga falta se
# compila una sola vez, en una sola pasada de Gradle, y se sube una sola vez: las demás casas que
# reciben el mismo APK se llevan un enlace duro en su propio directorio, porque `/liberar` apunta a
# cada una al suyo.
#
# The household's document is updated as soon as this finishes — its next poll will see the new
# release — but nothing installs on its own from that alone: the device itself decides when, via
# the icon beside TV or, in simple mode, by holding OK over the channel list. That gesture is the
# safety net a staged-but-unsent step used to be.
#
# Todas las casas pedidas se comprueban contra el panel antes de compilar nada: un nombre mal escrito
# al final de la lista no debe dejar la mitad publicada. Y Android no deja volver a un versionCode
# anterior, así que lo que pide DEPLOYMENT.md sigue en pie: primero una casa, el resto al día siguiente.
#
set -euo pipefail
cd "$(dirname "$0")"

die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[36m→ %s\033[0m\n' "$*"; }

USO="Uso: ./publish.sh --casa <id> [--casa <id> …]
     ./publish.sh --todas"

PEDIDAS=()
TODAS=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --casa) read -ra MAS <<< "${2:-}"; PEDIDAS+=(${MAS[@]+"${MAS[@]}"}); shift 2 ;;
        --todas) TODAS=1; shift ;;
        *) die "No entiendo '$1'.

$USO" ;;
    esac
done

[[ $TODAS -eq 1 || ${#PEDIDAS[@]} -gt 0 ]] || die "$USO"
[[ $TODAS -eq 1 && ${#PEDIDAS[@]} -gt 0 ]] && die "O --todas o --casa, no las dos."

[[ -f local.properties ]] || die "No hay local.properties. Copia local.properties.example."
prop() { sed -n "s/^$1=//p" local.properties | tail -1; }

PANEL=$(prop panel.url)
PUSER=$(prop panel.user)
PPASS=$(prop panel.password)
SSH=$(prop vps.ssh)
SDK=$(prop sdk.dir)

[[ -n "$PANEL" && -n "$PUSER" && -n "$PPASS" ]] || die "Faltan panel.url, panel.user o panel.password en local.properties."
[[ -n "$SSH" ]] || die "Falta vps.ssh en local.properties.

    vps.ssh=usuario@tu-vps.example.org"

# --- qué casa recibe qué, y adónde va --------------------------------------------

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

note "Preguntando al panel"
curl -sf --max-time 15 -u "$PUSER:$PPASS" "${PANEL%/}/casas" > "$WORK/casas.json" \
    || die "El panel no contestó, o la contraseña no es esa."

# Una línea por casa: id, flavour y directorio remoto. El segmento no se calcula aquí ni se guarda en
# este portátil: sale de la URL que dio el panel, que es quien lo generó.
PLAN=$(python3 - "$WORK/casas.json" local.properties "$TODAS" ${PEDIDAS[@]+"${PEDIDAS[@]}"} <<'PY'
import json, re, sys, urllib.parse

casas_path, props_path, todas = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
casas = {c["id"]: c for c in json.load(open(casas_path))["casas"] if c.get("app") == "videoclub"}
props = open(props_path, encoding="utf-8").read()
pedidas = sorted(casas) if todas else sys.argv[4:]

problems, seen = [], []
for casa_id in pedidas:
    if casa_id in seen:
        continue
    seen.append(casa_id)
    casa = casas.get(casa_id)
    if not casa:
        problems.append(f"No hay ninguna casa de Videoclub con id '{casa_id}' en el panel.")
        continue
    if casa.get("simple"):
        if not re.search(rf"^casa\.{re.escape(casa_id)}\.remoteConfig\.url=", props, re.M):
            problems.append(f"'{casa_id}' es simple y no está en local.properties: ./sync-casas.sh")
            continue
        # Mismo criterio que `flavourOf()` en app/build.gradle.kts.
        parts = [p for p in re.split(r"[^A-Za-z0-9]+", casa_id) if p]
        flavour = parts[0].lower() + "".join(p[:1].upper() + p[1:] for p in parts[1:])
    else:
        flavour = "general"
    segment = urllib.parse.urlsplit(casa["url"]).path.rsplit("/", 2)[-2]
    print(f"{casa_id} {flavour} /srv/videoclub/{segment}")

if not seen:
    problems.append("El panel no devolvió ninguna casa de Videoclub.")
if problems:
    print("\n".join(problems), file=sys.stderr)
    sys.exit(1)
PY
) || die "No se publica nada."

while read -r casa flavour dir; do
    note "Casa: $casa  →  APK $flavour  ($dir)"
done <<< "$PLAN"

# --- compilar -----------------------------------------------------------

mapfile -t FLAVOURS < <(awk '{print $2}' <<< "$PLAN" | sort -u)
TASKS=()
for f in "${FLAVOURS[@]}"; do
    TASKS+=(":app:assemble$(tr '[:lower:]' '[:upper:]' <<<"${f:0:1}")${f:1}Release")
done

# Una sola pasada: el versionCode sale de la hora de compilación (ver app/build.gradle.kts), y así
# todos los APKs de esta publicación llevan el mismo.
note "Compilando: ${FLAVOURS[*]}"
./gradlew --quiet "${TASKS[@]}"

AAPT2=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort | tail -1)
[[ -n "$AAPT2" ]] || die "No encuentro aapt2. Pon sdk.dir en local.properties."

declare -A SRC VERSION SHA256 FILENAME FIRST_DIR
for f in "${FLAVOURS[@]}"; do
    SRC[$f]="app/build/outputs/apk/$f/release/app-$f-release.apk"
    [[ -f "${SRC[$f]}" ]] || die "No se generó ${SRC[$f]}."
    # Leído del APK ya compilado y no recalculado aquí: es exactamente lo que la app comparará.
    VERSION[$f]=$("$AAPT2" dump badging "${SRC[$f]}" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
    [[ -n "${VERSION[$f]}" ]] || die "No he podido leer el versionCode de ${SRC[$f]}."
    SHA256[$f]=$(sha256sum "${SRC[$f]}" | cut -d' ' -f1)
    FILENAME[$f]="videoclub-${VERSION[$f]}.apk"
    note "$f: versión ${VERSION[$f]}, $(du -h "${SRC[$f]}" | cut -f1), sha256 ${SHA256[$f]:0:12}…"
done

# --- subir y registrar, casa por casa ------------------------------------------

PUBLICADAS=()
while read -r casa flavour dir; do
    file="${FILENAME[$flavour]}"
    # Al lado y luego mover: una tele que consultara el documento a mitad de la subida vería el APK de
    # siempre, no uno a medio escribir. `ssh -n` porque este bucle lee el plan por la entrada estándar.
    if [[ -z "${FIRST_DIR[$flavour]:-}" ]]; then
        note "Subiendo $flavour a $SSH:$dir/$file"
        scp -q "${SRC[$flavour]}" "$SSH:$dir/$file.tmp"
        ssh -n "$SSH" "chmod 644 '$dir/$file.tmp' && mv '$dir/$file.tmp' '$dir/$file'" \
            || die "Falló la subida por SSH."
        FIRST_DIR[$flavour]="$dir"
    else
        note "Enlazando $flavour en $dir"
        ssh -n "$SSH" "ln -f '${FIRST_DIR[$flavour]}/$file' '$dir/$file.tmp' && mv '$dir/$file.tmp' '$dir/$file'" \
            || die "No se pudo enlazar el APK en $dir. Publicadas hasta aquí: ${PUBLICADAS[*]:-ninguna}"
    fi

    note "Registrando la versión para '$casa'"
    curl -sf --max-time 15 -u "$PUSER:$PPASS" \
        --data-urlencode "casa=$casa" \
        --data-urlencode "version=${VERSION[$flavour]}" \
        --data-urlencode "sha256=${SHA256[$flavour]}" \
        --data-urlencode "filename=$file" \
        "${PANEL%/}/liberar" >/dev/null \
        || die "Se subió el APK pero el panel no lo registró para '$casa'. Publicadas hasta aquí: ${PUBLICADAS[*]:-ninguna}"
    PUBLICADAS+=("$casa")
done <<< "$PLAN"

printf '\n\033[32m✓ Publicado para: %s\033[0m\n' "${PUBLICADAS[*]}"
echo "  Cada casa lo verá en su próximo sondeo. Nada se instala hasta que alguien lo toque allí."
