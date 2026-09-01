#!/usr/bin/env bash
#
# Trae del panel la lista de casas y la deja en local.properties.
#
#   ./sync-casas.sh
#
# Las casas nacen en el panel del VPS: allí se crea el directorio, el documento y el token, y de ahí
# sale la URL. Este guion es lo único que cruza esa lista hasta la máquina que compila, y existe
# para que no haya que copiarla a mano.
#
# Deliberadamente un guion aparte y no una llamada dentro de Gradle. Una petición de red en la fase
# de configuración rompe las compilaciones sin internet, pelea con la caché de configuración y
# convierte "compilar" en algo que depende de que el VPS esté vivo. Así, `local.properties` sigue
# siendo la única fuente para la build, y esto sólo se lanza cuando has dado de alta una casa.
#
set -euo pipefail
cd "$(dirname "$0")"

# Qué aplicación es este proyecto: el panel gestiona las dos y aquí sólo interesan las suyas.
APP="${SYNC_APP:-videoclub}"

die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[36m→ %s\033[0m\n' "$*"; }

[[ -f local.properties ]] || die "No hay local.properties."
prop() { sed -n "s/^$1=//p" local.properties | tail -1; }

PANEL=$(prop panel.url)
USER=$(prop panel.user)
PASS=$(prop panel.password)

[[ -n "$PANEL" && -n "$USER" && -n "$PASS" ]] || die "Faltan panel.url, panel.user o panel.password en local.properties.

  panel.url=https://tu-vps/panel
  panel.user=...
  panel.password=..."

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

note "Preguntando a $PANEL"
curl -sf --max-time 15 -u "$USER:$PASS" "${PANEL%/}/casas" > "$WORK/casas.json" \
    || die "El panel no contestó, o la contraseña no es esa."

python3 - "$WORK/casas.json" "$APP" local.properties <<'PY'
import json, re, sys

casas_path, app, props_path = sys.argv[1], sys.argv[2], sys.argv[3]
casas = [c for c in json.load(open(casas_path))["casas"] if c.get("app") == app]

lines = open(props_path, encoding="utf-8").read().split("\n")
# Fuera las líneas de casas que hubiera, incluida la cabecera que este guion escribe. Lo demás
# —sdk.dir, la keystore, el tailnet— se queda exactamente donde estaba.
kept, skip_header = [], False
for line in lines:
    if line.startswith("# --- Casas (las escribe ./sync-casas.sh)"):
        skip_header = True
        continue
    if re.match(r"^casa\.[^.]+\.remoteConfig\.url=", line):
        continue
    if skip_header and not line.strip():
        skip_header = False
        continue
    kept.append(line)

body = "\n".join(kept).rstrip("\n")
if casas:
    body += "\n\n# --- Casas (las escribe ./sync-casas.sh) --------------------------------------\n"
    body += "\n".join(f"casa.{c['id']}.remoteConfig.url={c['url']}" for c in casas)
open(props_path, "w", encoding="utf-8").write(body + "\n")

print(f"  {len(casas)} casa(s) de {app}:")
for c in casas:
    print(f"    {c['id']:12} {c['nombre']}")
PY

printf '\n\033[32m✓ local.properties al día\033[0m\n'
echo "  Gradle generará un flavour por casa. Compila con: ./deploy.sh --casa <nombre>"
