#!/usr/bin/env python3
#
# Refresca el espejo del catálogo, cada vez que se lanza, con la cuenta de la casa marcada como
# "maestra" en el panel (Servidor → Casa que alimenta el catálogo compartido).
#
#   python3 catalogo-maestro.py
#
# Deliberadamente no importa simpletv-admin.py: lee casas.json y el provider.json de la casa
# maestra a pelo, sin arrastrar las tres mil líneas del panel para esto. Mismo espíritu que
# sync-casas.sh o build-ffmpeg-decoder.sh — un guion pequeño, autocontenido, pensado para
# lanzarse solo (systemd timer, ver videoclub-catalogo.timer) y no para importarse desde nada.
#
# Por qué existe: seis casas, cada una con su propia cuenta, sincronizando el catálogo entero
# (unas 900 peticiones cada una — ver CatalogSync.kt) contra el mismo proveedor por separado es
# lo que llevó a un 429 Too Many Requests reproducido en directo. Todas las cuentas de este panel
# responden al mismo stream_id para el mismo título — confirmado a mano — así que basta con
# pedirlo una vez, con cualquiera de ellas, y dejarlo listo para que las demás lo bajen de aquí.
#
# El JSON de cada categoría se guarda tal cual lo devuelve el proveedor, sin reinterpretarlo: así
# CatalogJson.kt, en la app, lo lee exactamente igual que si viniera en directo — no hay que
# tocar el parser para esto.
#
import json
import os
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

STATE_DIR = os.environ.get("SIMPLETV_ADMIN_STATE", "/var/lib/simpletv-admin")
HOUSES_FILE = os.path.join(STATE_DIR, "casas.json")

# El mismo directorio que ya sirve provider.json y los APKs por alias de nginx — nada nuevo que
# proteger, este fichero no lleva usuario ni contraseña dentro.
OUT_DIR = "/srv/videoclub/_catalogo"
OUT_FILE = os.path.join(OUT_DIR, "vod.json")

TIMEOUT = 20
# Menos agresivo que CatalogSync.kt (BATCH=4) a propósito: la app reparte esas cuatro peticiones
# entre la latencia normal de una conexión doméstica, mientras que este guion, corriendo en el
# propio VPS, las dispara casi sin esperar nada de por sí — la primera vez que se probó, a este
# ritmo, se colaron 429 del proveedor en más de la mitad de las categorías. REQUEST_GAP fuerza un
# hueco mínimo entre peticiones sea cual sea la concurrencia, y RETRY_DELAY espera de verdad antes
# de reintentar en vez de aporrear la misma categoría dos veces seguidas.
BATCH = 2
ATTEMPTS = 3
REQUEST_GAP = 0.35
RETRY_DELAY = 3.0

_pacing_lock = threading.Lock()
_last_request_at = 0.0


def _pace():
    """Blocks just long enough that no two requests, from any worker, leave less than
    REQUEST_GAP seconds apart — a shared throttle, not a per-worker one."""
    global _last_request_at
    with _pacing_lock:
        wait = _last_request_at + REQUEST_GAP - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        _last_request_at = time.monotonic()

# (nombre en el JSON de salida, acción de categorías, acción de listados)
KINDS = (
    ("vod", "get_vod_categories", "get_vod_streams"),
    ("series", "get_series_categories", "get_series"),
)


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", file=sys.stderr, flush=True)


def die(msg):
    log(msg)
    sys.exit(1)


def api_url(base_url, username, password, action, extra=None):
    params = {"username": username, "password": password, "action": action}
    if extra:
        params.update(extra)
    return f"{base_url.rstrip('/')}/player_api.php?{urllib.parse.urlencode(params)}"


def get_json(url, user_agent):
    _pace()
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_categories(base_url, username, password, user_agent, action):
    for attempt in range(ATTEMPTS):
        try:
            data = get_json(api_url(base_url, username, password, action), user_agent)
            return data if isinstance(data, list) else []
        except Exception as error:
            if attempt == ATTEMPTS - 1:
                log(f"No se pudieron leer las categorías ({action}): {error.__class__.__name__}")
            else:
                time.sleep(RETRY_DELAY)
    return []


def fetch_listings(base_url, username, password, user_agent, action, category_id):
    for attempt in range(ATTEMPTS):
        try:
            data = get_json(
                api_url(base_url, username, password, action, {"category_id": category_id}),
                user_agent,
            )
            return data if isinstance(data, list) else []
        except Exception as error:
            if attempt == ATTEMPTS - 1:
                log(f"Categoría {category_id} ({action}) no bajó: {error.__class__.__name__}")
            else:
                time.sleep(RETRY_DELAY)
    return None


def build_kind(base_url, username, password, user_agent, categories_action, listings_action):
    categories = fetch_categories(base_url, username, password, user_agent, categories_action)
    ids = [
        str(c.get("category_id")) for c in categories
        if isinstance(c, dict) and c.get("category_id") is not None
    ]

    streams = {}
    incompleto = False
    with ThreadPoolExecutor(max_workers=BATCH) as pool:
        for category_id, listing in zip(
            ids,
            pool.map(
                lambda cid: fetch_listings(
                    base_url, username, password, user_agent, listings_action, cid
                ),
                ids,
            ),
        ):
            if listing is None:
                incompleto = True
                continue
            streams[category_id] = listing

    return {"categorias": categories, "streams": streams}, incompleto


def main():
    if not os.path.exists(HOUSES_FILE):
        die(f"No hay {HOUSES_FILE} — nada que hacer todavía.")

    with open(HOUSES_FILE, encoding="utf-8") as handle:
        state = json.load(handle)

    master_id = state.get("catalogo_maestro")
    if not master_id:
        log("Ninguna casa marcada como maestra (Panel → Servidor). No se hace nada.")
        return

    casa = next((c for c in state.get("casas") or [] if c["id"] == master_id), None)
    if not casa:
        die(f"La casa maestra «{master_id}» ya no está en el panel.")

    with open(casa["provider"], encoding="utf-8") as handle:
        doc = json.load(handle)

    base_url = doc.get("url") or ""
    username = doc.get("username") or ""
    password = doc.get("password") or ""
    user_agent = doc.get("userAgent") or "Videoclub/1.0"
    if not base_url or not username or not password:
        die(f"«{casa['nombre']}» no tiene cuenta completa todavía.")

    log(f"Usando la cuenta de «{casa['nombre']}»")

    os.makedirs(OUT_DIR, exist_ok=True)
    handle, scratch = tempfile.mkstemp(dir=OUT_DIR, prefix=".vod-")
    algo_incompleto = False
    try:
        # NDJSON — una línea, un objeto JSON pequeño — y no un único documento de cien y pico MB.
        # La primera versión escribía eso, y VodClient.kt cargándolo entero con
        # `response.body().string()` se encontró intentando reservar un String de Java de ~200 MB
        # (UTF-16) de una sentada: OutOfMemoryError en un aparato real, siempre, en silencio. Aquí
        # ninguna línea pesa más que lo que ya pesa una categoría sola pedida al proveedor en
        # directo — la app parsea esto exactamente igual, línea a línea.
        with os.fdopen(handle, "w", encoding="utf-8") as f:
            escribir = lambda obj: f.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")
            escribir({"tipo": "meta", "generado_en": int(time.time())})
            for nombre, cat_action, list_action in KINDS:
                log(f"Descargando {nombre}…")
                bloque, incompleto = build_kind(
                    base_url, username, password, user_agent, cat_action, list_action
                )
                algo_incompleto = algo_incompleto or incompleto
                escribir({"tipo": "categorias", "kind": nombre, "items": bloque["categorias"]})
                for category_id, items in bloque["streams"].items():
                    escribir({
                        "tipo": "listado", "kind": nombre, "category_id": category_id, "items": items
                    })
                log(f"{nombre}: {len(bloque['categorias'])} categorías, {len(bloque['streams'])} completas")
        os.chmod(scratch, 0o644)
        os.replace(scratch, OUT_FILE)
    except Exception:
        os.unlink(scratch)
        raise

    log(f"Escrito {OUT_FILE}" + (" (con huecos — algunas categorías no bajaron)" if algo_incompleto else ""))


if __name__ == "__main__":
    main()
