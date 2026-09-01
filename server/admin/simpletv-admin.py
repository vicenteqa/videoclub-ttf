#!/usr/bin/env python3
"""
The panel where the households live.

One page: the supplier's server, shared by everybody, and a card per household with its own account
and its own people. Saving rewrites each household's `provider.json`, which is the file its
television reads on every launch.

It also creates households. Doing that by hand means inventing a random path, making a directory,
writing a document, generating a token and registering it — five manual steps whose only useful
output is a URL. It is a button here instead.

Runs on the loopback interface only. TLS and the password prompt are nginx's job, because nginx is
already there and already holds the certificate; this process is deliberately ignorant of both.

Stdlib only. This box runs other people's things; a panel used a few times a year does not get to
introduce a virtualenv, a package index, or a dependency that needs patching.
"""

import base64
import calendar
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import sqlite3
import tempfile
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

STATE_DIR = os.environ.get("SIMPLETV_ADMIN_STATE", "/var/lib/simpletv-admin")
PORT = int(os.environ.get("SIMPLETV_ADMIN_PORT", "8791"))

# The households. In the state directory rather than in /etc because the panel writes it: granting
# a service write access to /etc so that a form can add a row is widening a sandbox for convenience.
HOUSES_FILE = os.path.join(STATE_DIR, "casas.json")

# Where the original single-household config lived. Read once, if it is all there is, so that an
# upgrade of this file does not need a hand migration.
LEGACY_CONFIG = os.environ.get("SIMPLETV_ADMIN_CONFIG", "/etc/simpletv-admin.json")

# What each television last said it was showing, one file per household. Kept out of /srv on
# purpose: those directories are published by an `alias`, and what somebody watches is nobody
# else's business.
WATCH_DIR = os.path.join(STATE_DIR, "visto")

# La base pública de las URLs que este panel entrega a la compilación. Sólo se antepone a rutas que
# ha generado él mismo.
#
# Se configura con `SIMPLETV_ADMIN_BASE` en el servicio de systemd. El valor por defecto es un
# marcador a propósito: si alguien olvida ponerla, las URLs salen visiblemente falsas en vez de
# sutilmente equivocadas, que es la clase de fallo que se descubre semanas después en un APK que
# nunca encontró su documento.
PUBLIC_BASE = os.environ.get("SIMPLETV_ADMIN_BASE", "https://tu-vps.example.org")

# The two applications, and the only two directories a household may be created in. A generated
# path is joined onto one of these and nothing else — no value from a form ever reaches a
# filesystem path.
APPS = {
    "simpletv": {"label": "SimpleTV", "root": "/srv/simpletv", "web": "/simpletv"},
    "videoclub": {"label": "Videoclub", "root": "/srv/videoclub", "web": "/videoclub"},
}

# Written by the shared section, into every household alike.
SHARED_FIELDS = ("url", "userAgent")

# A report bigger than this is not a report. The body is two short fields.
MAX_REPORT_BYTES = 1024

# Cuántas cosas vistas se recuerdan por casa. Doscientas entradas de texto corto son unos pocos
# kilobytes, y dan para meses de televisión: el límite existe para que el fichero no crezca sin
# techo, no porque haga falta apretar.
WATCH_HISTORY = 200

# El informe es leer-modificar-escribir, y llega por la misma vía que todo lo demás: un hilo por
# petición. Sin esto, dos avisos seguidos de la misma casa pueden perder uno de los dos.
WATCH_LOCK = threading.Lock()

# Lo mismo para el progreso: leer el contador, escribir las filas y guardar el contador nuevo tiene
# que ser una sola cosa, o dos aparatos sincronizando a la vez se reparten el mismo número.
SYNC_LOCK = threading.Lock()

# Every change to the household list is read-modify-write, and this server answers requests on a
# thread each. Without this, two people pressing «Crear» at the same time — or one person pressing
# it twice — both read the same list and the second write drops the first household, leaving its
# directory on disk with nothing pointing at it.
HOUSES_LOCK = threading.Lock()


# ------------------------------------------------------------------------------------- households

def state():
    """
    Everything the panel knows: the shared server, and the households.

    Two sections rather than one list, because they are two different kinds of thing. The server
    belongs to the subscription and is the same wherever it is used; a household is one television's
    account. Keeping the server here — instead of deducing it by reading whichever household's
    document happened to have one — is what stops deleting a household from taking the server with
    it, which is exactly what it used to do.
    """
    if os.path.exists(HOUSES_FILE):
        with open(HOUSES_FILE, encoding="utf-8") as handle:
            doc = json.load(handle)
        doc.setdefault("casas", [])
        if "servidor" not in doc:
            # Written before the server had a section of its own: lift it out of whichever document
            # still carries it, once, and never deduce it again.
            doc["servidor"] = {}
            for casa in doc["casas"]:
                found, _ = read_provider(casa["provider"])
                if found and found.get("url"):
                    doc["servidor"] = {f: found[f] for f in SHARED_FIELDS if found.get(f)}
                    break
            save_state(doc)
        return doc
    return migrate_legacy()


def houses():
    return state()["casas"]


def server():
    return state()["servidor"]


def migrate_legacy():
    """
    The single-household config, promoted to the two-section document the first time this runs.

    Kept rather than dropped: the household it describes is a television in somebody's living room,
    and the URL compiled into its APK points at the path named there. Losing that mapping would
    mean a box that still works and a panel that no longer knows about it.
    """
    try:
        with open(LEGACY_CONFIG, encoding="utf-8") as handle:
            provider = json.load(handle)["provider"]
    except Exception:
        return {"servidor": {}, "casas": []}

    segment = provider.split("/srv/simpletv/", 1)[-1].rsplit("/", 1)[0]
    doc, _ = read_provider(provider)
    documento = {
        "servidor": {f: (doc or {})[f] for f in SHARED_FIELDS if (doc or {}).get(f)},
        "casas": [{
            "id": "padre",
            "nombre": "Padre",
            "app": "simpletv",
            "provider": provider,
            "url": f"{PUBLIC_BASE}/simpletv/{segment}/provider.json",
        }],
    }
    save_state(documento)
    return documento


def save_state(doc):
    write_json(HOUSES_FILE, doc)


def save_houses(casas):
    doc = state()
    doc["casas"] = casas
    save_state(doc)


def save_server(values):
    doc = state()
    doc["servidor"] = values
    save_state(doc)


def read_provider(path):
    try:
        with open(path, encoding="utf-8") as handle:
            doc = json.load(handle)
        return doc if isinstance(doc, dict) else {}, os.path.getmtime(path)
    except FileNotFoundError:
        return {}, None
    except Exception:
        # A file somebody edited by hand and broke. Reported rather than silently replaced: the
        # panel is very often the tool being reached for *because* of that.
        return None, os.path.getmtime(path) if os.path.exists(path) else None


def write_json(path, doc, mode=0o600):
    """Beside the real file and renamed over it, so a reader mid-write sees the old one."""
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    handle, scratch = tempfile.mkstemp(dir=directory, prefix=".tmp-", suffix=".json")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump(doc, out, indent=2, ensure_ascii=False)
            out.write("\n")
        os.chmod(scratch, mode)
        os.replace(scratch, path)
    except Exception:
        if os.path.exists(scratch):
            os.unlink(scratch)
        raise


# ------------------------------------------------------------------------------ creating a house

def secret_key():
    """
    The key the path segments are derived from. Generated once, never leaves this box.

    Kept in the state directory at 0600. Losing it is not a disaster but it is a chore: every
    household's URL changes, so every APK has to be rebuilt and reinstalled. Back it up with the
    keystore.
    """
    path = os.path.join(STATE_DIR, "clave")
    if os.path.exists(path):
        with open(path, "rb") as handle:
            return handle.read().strip()
    key = secrets.token_bytes(32)
    os.makedirs(STATE_DIR, exist_ok=True)
    handle, scratch = tempfile.mkstemp(dir=STATE_DIR, prefix=".clave-")
    with os.fdopen(handle, "wb") as out:
        out.write(key)
    os.chmod(scratch, 0o600)
    os.replace(scratch, path)
    return key


def segment_for(app, house_id):
    """
    The secret path a household's document lives at, derived rather than drawn.

    The obvious thing would be `/videoclub/suegros/provider.json`, and it is exactly what cannot be
    done: there is no login here, the document carries the account password in clear, and the
    secrecy of the URL is the whole of the access control. A guessable path is a published password.

    So the segment is an HMAC of the household's name under a key that never leaves this machine.
    From outside it is indistinguishable from the random string it replaces. From in here it is a
    function: delete a household and create it again under the same name and it lands on the same
    URL, which means the APK in that house keeps working and never needs rebuilding. That is worth
    a great deal — the alternative is a television in somebody else's living room that has to be
    visited because a row was deleted on a web page.
    """
    digest = hmac.new(secret_key(), f"{app}:{house_id}".encode("utf-8"), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").replace("-", "").replace("_", "")[:30]


def slug(name):
    text = re.sub(r"[^a-z0-9]+", "-", name.strip().lower()).strip("-")
    return text or "casa"


def create_house(nombre, app, username, password):
    with HOUSES_LOCK:
        return _create_house(nombre, app, username, password)


def _create_house(nombre, app, username, password):
    """
    A new household: a directory nobody can guess, a document, and a token.

    Everything the television needs is demanded up front — which application, and the account. A
    household created half-filled is a `provider.json` with no credentials in it, which is precisely
    the document that makes a box say «Error de credenciales»; leaving that lying around between
    pressing «Crear» and remembering to finish the form is a state worth making impossible rather
    than worth explaining.

    The path segment is generated here and never taken from the form. A directory name built out of
    text somebody typed is how a panel ends up writing outside the tree it is allowed to touch, and
    no amount of validation is as convincing as never doing it.
    """
    nombre = nombre.strip()
    username = username.strip()
    password = password.strip()

    if not nombre:
        return None, "La casa necesita un nombre."
    if app not in APPS:
        return None, "Elige la aplicación que va a correr en esa casa."
    if not username or not password:
        return None, "La casa necesita usuario y contraseña del proveedor."

    existing = houses()
    base = slug(nombre)
    house_id = base
    n = 2
    while any(c["id"] == house_id for c in existing):
        house_id = f"{base}-{n}"
        n += 1

    segment = segment_for(app, house_id)
    path = os.path.join(APPS[app]["root"], segment, "provider.json")

    shared = server()
    if not shared.get("url"):
        return None, ("Primero hace falta el servidor, en «Servidor →»: una casa sin él nace sin "
                      "poder reproducir nada.")

    # A household created under a name that existed before lands on the same path, so anything the
    # old document still holds — its people, its token — is picked back up rather than replaced.
    # That is what makes «borrar y volver a añadir» invisible to the television.
    previous, _ = read_provider(path)
    document = dict(previous or {})
    document.update(shared)
    document["username"] = username
    document["password"] = password
    document["reportUrl"] = PUBLIC_BASE + "/informe"
    document.setdefault("reportToken", secrets.token_hex(24))
    if app == "videoclub" and not document.get("perfiles"):
        document["perfiles"] = [{"id": 0, "nombre": nombre}]
        document.setdefault("siguientePerfilId", 1)

    try:
        write_json(path, document, mode=0o644)
        os.chmod(os.path.dirname(path), 0o755)
    except Exception as error:
        return None, f"No se pudo crear el directorio: {error.__class__.__name__}"

    casa = {
        "id": house_id,
        "nombre": nombre,
        "app": app,
        "provider": path,
        "url": f"{PUBLIC_BASE}{APPS[app]['web']}/{segment}/provider.json",
    }
    save_houses(existing + [casa])
    return casa, None


def delete_house(house_id):
    with HOUSES_LOCK:
        return _delete_house(house_id)


def _delete_house(house_id):
    """
    Takes a household off the panel and forgets what it reported. The document stays.

    Deleting the file was the obvious reading of «bórralo todo», and it is the one thing here worth
    not doing. A box already installed in that house reads that URL on every launch; take the file
    away and it gets a 404 for ever. It keeps playing from its cached account — that is deliberate —
    but nothing can be told to it again without standing in front of it.

    Leaving it costs nothing, because the path is derived from the household's name rather than
    drawn at random: create «Suegros» again and it lands on that same document, picks up its people
    and its token, and the television in that house never notices any of it happened. A file with a
    password in it, at a URL nobody can guess, is the same risk it was yesterday.

    Writes nothing unless exactly this household was found. A delete that matches nobody is a
    request that has already gone wrong somewhere, and the worst possible answer to it is to save
    whatever the list happened to look like at that moment — which is how a list of three becomes a
    list of none.
    """
    current = houses()
    if not any(c["id"] == house_id for c in current):
        return False
    save_houses([c for c in current if c["id"] != house_id])
    if os.path.exists(watch_path(house_id)):
        os.unlink(watch_path(house_id))
    # El progreso sí se va, al contrario que el documento: el documento se queda para que un aparato
    # que aún no se ha actualizado siga teniendo credenciales, y eso no aplica a dónde se quedó
    # alguien en una serie. Volver a crear la casa con el mismo nombre empieza de cero.
    try:
        sync_forget(house_id)
    except Exception:
        pass
    return True


# --------------------------------------------------------------------------------------- the form

def apply_server(form):
    """
    Guarda la sección Servidor, y la reparte por las casas.

    Va aparte del formulario de cada casa a propósito: son dos cosas distintas y se tocan en
    momentos distintos. La dirección cambia el día que el proveedor mueve el servidor, y ese día
    hay que cambiarla en todas; una contraseña cambia para una casa sola. Un único botón para
    ambas cosas obligaba a que todo estuviera bien para poder arreglar cualquier cosa.

    Devuelve (guardado, errores, avisos). Los avisos son casas que no se han podido actualizar:
    el servidor sí se guardó, así que no es un fallo del formulario que hay en pantalla.
    """
    url = form.get("url", [""])[0].strip().rstrip("/")
    agent = form.get("userAgent", [""])[0].strip()

    if not url:
        return False, ["El servidor no puede quedar vacío: sin él ninguna casa reproduce nada."], []
    if not re.match(r"^https?://[^\s/]+", url):
        return False, [f"«{url}» no parece una dirección. Debe empezar por http:// o https://"], []

    save_server({k: v for k, v in (("url", url), ("userAgent", agent)) if v})

    avisos = []
    for casa in houses():
        existing, _ = read_provider(casa["provider"])
        if existing is None:
            # Aquí no hay los campos de esa casa, así que reescribir su documento entero lo
            # dejaría sin credenciales. Se queda como está y se dice en voz alta.
            avisos.append(
                f"«{casa['nombre']}» tiene el fichero ilegible: guárdala desde su propia ficha."
            )
            continue
        doc = dict(existing)
        doc["url"] = url
        if agent:
            doc["userAgent"] = agent
        else:
            doc.pop("userAgent", None)
        try:
            write_json(casa["provider"], doc, mode=0o644)
        except Exception as error:
            avisos.append(f"No se pudo escribir «{casa['nombre']}»: {error.__class__.__name__}")

    return True, [], avisos


def send_channel(house_id, canal):
    """
    Deja escrito en el documento de una casa que ponga un canal. Devuelve el problema, o None.

    Es un recado con fecha y no un ajuste: la app obedece una vez y la orden caduca sola a los diez
    minutos. Por eso se escribe `cuando` — sin él, la caja saltaría a ese canal cada vez que releyera
    el documento, para siempre.

    Que llegue tarde o no llegue entra dentro de lo normal: la caja mira el documento cada dos
    minutos mientras está encendida, y si está apagada no se entera de nada. Esto no promete una
    entrega, ofrece una comodidad.
    """
    canal = (canal or "").strip()[:120]
    if not canal:
        return "Elige un canal."
    casa = next((c for c in houses() if c["id"] == house_id), None)
    if not casa:
        return "Esa casa ya no está en la lista."

    doc, error = read_provider(casa["provider"])
    if doc is None:
        return error or "No se puede leer el documento de esa casa."
    doc["poner"] = {"canal": canal, "cuando": int(time.time())}
    try:
        write_json(casa["provider"], doc, mode=0o644)
    except Exception:
        return "No se ha podido escribir el documento."
    return None


def apply_house(house_id, form):
    """
    Guarda una casa y sólo una. Devuelve (guardado, errores).

    El servidor no se lee del formulario aunque llegue en él: se lee de su vista, que
    es donde vive. Así una casa guardada no puede llevarse por delante la dirección de las demás.
    """
    casa = next((c for c in houses() if c["id"] == house_id), None)
    if not casa:
        return False, ["Esa casa ya no está en la lista."]

    existing, _ = read_provider(casa["provider"])
    # Un fichero que no parsea se reescribe entero en vez de fusionarse: fusionar sobre basura es
    # como sobrevive media credencial a una reparación.
    doc = dict(existing) if existing else {}
    prefix = casa["id"] + "."

    shared = server()
    if shared.get("url"):
        doc["url"] = shared["url"]
    if shared.get("userAgent"):
        doc["userAgent"] = shared["userAgent"]
    else:
        doc.pop("userAgent", None)

    errors = []

    # El nombre de la casa vive en `casas.json`, no en su documento: es cómo la llamamos aquí, no
    # algo que el televisor lea. Y sólo cambia el nombre — nunca el `id`, que es de donde sale el
    # segmento secreto de su URL, el flavour de Gradle y la clave de `local.properties`. Renombrar
    # una casa no puede obligar a recompilar su APK ni a ir a su salón a reinstalarlo.
    nuevo_nombre = form.get(prefix + "casa.nombre", [""])[0].strip()
    if not nuevo_nombre:
        errors.append(f"«{casa['nombre']}» no puede quedarse sin nombre.")
    elif nuevo_nombre != casa["nombre"]:
        with HOUSES_LOCK:
            casas = houses()
            if any(c["id"] != house_id and c["nombre"].lower() == nuevo_nombre.lower()
                   for c in casas):
                errors.append(f"Ya hay otra casa que se llama «{nuevo_nombre}».")
            else:
                for c in casas:
                    if c["id"] == house_id:
                        c["nombre"] = nuevo_nombre
                save_houses(casas)
                casa = dict(casa, nombre=nuevo_nombre)

    for field in ("username", "password"):
        value = form.get(prefix + field, [""])[0].strip()
        if value:
            doc[field] = value
        else:
            doc.pop(field, None)

    if casa["app"] == "simpletv":
        name = form.get(prefix + "name", [""])[0].strip()
        if name:
            doc["name"] = name
        else:
            doc.pop("name", None)
    else:
        people, next_id, problem = read_people(form, prefix, doc)
        if problem:
            errors.append(f"«{casa['nombre']}»: {problem}")
        doc["perfiles"] = people
        doc["siguientePerfilId"] = next_id
        if form.get(prefix + "simple"):
            doc["simple"] = True
        else:
            doc.pop("simple", None)

    if not doc.get("username") or not doc.get("password"):
        errors.append(f"«{casa['nombre']}» necesita usuario y contraseña.")
    if not doc.get("url"):
        errors.append("Rellena antes el servidor: sin dirección esta casa no reproduce nada.")
    if errors:
        return False, errors

    try:
        write_json(casa["provider"], doc, mode=0o644)
    except Exception as error:
        return False, [f"No se pudo escribir «{casa['nombre']}»: {error.__class__.__name__}"]
    return True, []


def read_people(form, prefix, doc):
    """
    The household, as the form left it.

    Ids travel in hidden fields and are never renumbered: an id is what the history tables on every
    television are filed under, so it belongs to the person rather than to their position in a list.
    A row whose name has been cleared is a person removed — their history goes with them on each box
    the next time it reads this document, which is why `siguientePerfilId` only ever goes up and
    their number is never handed out again.
    """
    next_id = int(doc.get("siguientePerfilId") or 0)
    people = []
    seen = set()

    for raw in form.get(prefix + "perfil.id", []):
        try:
            person_id = int(raw)
        except ValueError:
            continue
        if person_id in seen:
            continue
        name = form.get(f"{prefix}perfil.{person_id}.nombre", [""])[0].strip()
        if not name:
            continue
        seen.add(person_id)
        person = {"id": person_id, "nombre": name}
        if form.get(f"{prefix}perfil.{person_id}.infantil"):
            person["infantil"] = True
        people.append(person)
        next_id = max(next_id, person_id + 1)

    # Las filas nuevas vienen numeradas — `perfil.nuevo.0`, `.1`, … — porque la página deja añadir
    # varias de una vez. El número es sólo para emparejar cada nombre con su casilla de «infantil»
    # y para respetar el orden en que se escribieron; el id de verdad se asigna aquí.
    marca = prefix + "perfil.nuevo."
    nuevas = []
    for clave, valores in form.items():
        if not clave.startswith(marca) or clave.endswith(".infantil"):
            continue
        orden = clave[len(marca):]
        if not orden.isdigit():
            continue
        nombre = valores[0].strip()
        if nombre:
            nuevas.append((int(orden), nombre))

    for orden, nombre in sorted(nuevas):
        person = {"id": next_id, "nombre": nombre}
        if form.get(f"{marca}{orden}.infantil"):
            person["infantil"] = True
        people.append(person)
        next_id += 1

    if not people:
        return people, next_id, "hace falta al menos una persona."
    names = [p["nombre"].upper() for p in people]
    if len(set(names)) != len(names):
        return people, next_id, "dos personas con el mismo nombre."
    return people, next_id, None


# --------------------------------------------------------------- dónde se quedó cada uno, y en qué

# El progreso de cada persona, compartido entre los aparatos de su casa.
SYNC_DB = os.path.join(STATE_DIR, "progreso.db")

# Un cuerpo mayor que esto no es una sincronización. Una casa con años de televisión encima manda
# unos pocos cientos de filas la primera vez y una o dos después.
MAX_SYNC_BYTES = 512 * 1024

# Cuántas filas contesta como mucho un `GET`. Un aparato que lleva meses apagado se pone al día en
# varias vueltas en vez de en una respuesta de varios megabytes.
SYNC_PAGE = 500


def sync_db():
    """
    La base del progreso, creada al vuelo la primera vez que alguien la usa.

    ## Por qué la fila no lleva el identificador del título

    Cada aparato numera su catálogo por su cuenta: `title_id` es un `AUTOINCREMENT` que se reparte
    según el orden en que llegan los listados del proveedor ese día. El 4711 de una tablet y el 4711
    de un móvil son películas distintas. Sincronizar por ese número repartiría marcas de «seguir
    viendo» por películas al azar.

    Lo que sí es igual en los dos es `merge_key` — lo que funde sesenta listados de Blade Runner en
    una obra — y el número de temporada y episodio. Eso es lo que viaja, y cada aparato lo traduce a
    su numeración al recibirlo.

    ## Por qué las filas no se borran

    Quitar algo de «Seguir viendo» es una decisión, y tiene que llegar al otro aparato igual que
    llega haber visto media película. Una fila borrada no se puede mandar, así que se marca.

    ## Por qué «Mi lista» va en otra tabla y con el mismo contador

    Es otra cosa —guardar para después no tiene posición ni episodio— pero es el mismo libro: los
    dos se leen con un solo cursor por aparato, así que las dos tablas comparten la secuencia de
    `contador` de la casa. Un contador por tabla obligaría al aparato a llevar dos cursores y a que
    los dos avanzaran bien por separado, que es el doble de sitios donde perder una fila.

    `perfil` está en las dos por lo mismo: una casa, un catálogo, y una lista por persona.
    """
    conn = sqlite3.connect(SYNC_DB)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS progreso (
            casa      TEXT    NOT NULL,
            perfil    INTEGER NOT NULL,
            obra      TEXT    NOT NULL,
            episodio  INTEGER NOT NULL DEFAULT 0,
            posicion  INTEGER NOT NULL,
            duracion  INTEGER NOT NULL,
            visto_en  INTEGER NOT NULL,
            borrado   INTEGER NOT NULL DEFAULT 0,
            contador  INTEGER NOT NULL,
            PRIMARY KEY (casa, perfil, obra, episodio)
        );
        CREATE INDEX IF NOT EXISTS idx_progreso_contador ON progreso (casa, contador);
        CREATE TABLE IF NOT EXISTS lista (
            casa        TEXT    NOT NULL,
            perfil      INTEGER NOT NULL,
            obra        TEXT    NOT NULL,
            guardado_en INTEGER NOT NULL,
            cambiado_en INTEGER NOT NULL,
            borrado     INTEGER NOT NULL DEFAULT 0,
            contador    INTEGER NOT NULL,
            PRIMARY KEY (casa, perfil, obra)
        );
        CREATE INDEX IF NOT EXISTS idx_lista_contador ON lista (casa, contador);
        CREATE TABLE IF NOT EXISTS secuencia (
            casa  TEXT PRIMARY KEY,
            valor INTEGER NOT NULL
        );
        """
    )
    return conn


def sync_pull(house_id, desde):
    """Lo que ha cambiado en esa casa después del contador `desde`, progreso y lista."""
    with sync_db() as conn:
        filas = conn.execute(
            "SELECT perfil, obra, episodio, posicion, duracion, visto_en, borrado, contador "
            "FROM progreso WHERE casa = ? AND contador > ? ORDER BY contador LIMIT ?",
            (house_id, desde, SYNC_PAGE),
        ).fetchall()
        guardadas = conn.execute(
            "SELECT perfil, obra, guardado_en, cambiado_en, borrado, contador "
            "FROM lista WHERE casa = ? AND contador > ? ORDER BY contador LIMIT ?",
            (house_id, desde, SYNC_PAGE),
        ).fetchall()
        tope = conn.execute(
            "SELECT valor FROM secuencia WHERE casa = ?", (house_id,)
        ).fetchone()

    # El contador que el aparato debe pedir la próxima vez. Si una de las dos páginas se ha llenado,
    # es el de su última fila y no el tope: lo que falta se recoge en la siguiente vuelta. Y si se
    # han llenado las dos, el menor de los dos, porque el cursor es uno solo y no puede ir más lejos
    # de donde llega el libro que va más atrasado.
    limite = tope[0] if tope else 0
    if len(filas) == SYNC_PAGE:
        limite = min(limite, filas[-1][7])
    if len(guardadas) == SYNC_PAGE:
        limite = min(limite, guardadas[-1][5])

    return {
        "contador": limite,
        "progreso": [
            {
                "perfil": f[0], "obra": f[1], "episodio": f[2], "posicion": f[3],
                "duracion": f[4], "visto_en": f[5], "borrado": bool(f[6]),
                # El contador de cada fila y no sólo el del final: el aparato que recibe puede no
                # saber colocar una obra todavía —su catálogo aún se está descargando— y necesita
                # poder decir «he llegado hasta aquí» en vez de darlo todo por leído.
                "contador": f[7],
            }
            for f in filas
        ],
        "lista": [
            {
                "perfil": g[0], "obra": g[1], "guardado_en": g[2], "cambiado_en": g[3],
                "borrado": bool(g[4]), "contador": g[5],
            }
            for g in guardadas
        ],
    }


def sync_push(house_id, filas):
    """
    Guarda lo que manda un aparato. Gana la marca más reciente, no la más avanzada.

    Parece que debería ganar la posición más adelantada — si has visto media película en el móvil,
    que la tele no te devuelva al principio. Pero eso hace imposible volver a ver algo: empezar de
    cero una serie que terminaste el año pasado quedaría siempre pisado por el último episodio. La
    hora del aparato decide, que es lo que hace que «lo último que hice» sea lo que vale.

    Devuelve cuántas filas se han aplicado; las viejas se descartan en silencio, que es lo normal
    cuando dos aparatos se ponen al día a la vez.
    """
    if not filas:
        return 0

    aplicadas = 0
    with SYNC_LOCK, sync_db() as conn:
        valor = conn.execute(
            "SELECT valor FROM secuencia WHERE casa = ?", (house_id,)
        ).fetchone()
        contador = valor[0] if valor else 0

        for fila in filas:
            try:
                perfil = int(fila["perfil"])
                obra = str(fila["obra"]).strip()[:200]
                episodio = int(fila.get("episodio") or 0)
                posicion = max(0, int(fila["posicion"]))
                duracion = max(0, int(fila["duracion"]))
                visto_en = int(fila["visto_en"])
                borrado = 1 if fila.get("borrado") else 0
            except (KeyError, TypeError, ValueError):
                continue
            if not obra or perfil < 0:
                continue

            previo = conn.execute(
                "SELECT visto_en FROM progreso "
                "WHERE casa = ? AND perfil = ? AND obra = ? AND episodio = ?",
                (house_id, perfil, obra, episodio),
            ).fetchone()
            if previo and previo[0] >= visto_en:
                continue

            contador += 1
            aplicadas += 1
            conn.execute(
                "INSERT INTO progreso "
                "(casa, perfil, obra, episodio, posicion, duracion, visto_en, borrado, contador) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (casa, perfil, obra, episodio) DO UPDATE SET "
                "posicion = excluded.posicion, duracion = excluded.duracion, "
                "visto_en = excluded.visto_en, borrado = excluded.borrado, "
                "contador = excluded.contador",
                (house_id, perfil, obra, episodio, posicion, duracion, visto_en, borrado, contador),
            )

        if aplicadas:
            conn.execute(
                "INSERT INTO secuencia (casa, valor) VALUES (?, ?) "
                "ON CONFLICT (casa) DO UPDATE SET valor = excluded.valor",
                (house_id, contador),
            )
    return aplicadas


def sync_list_push(house_id, filas):
    """
    Guarda lo que un aparato ha metido o sacado de «Mi lista». Gana la marca más reciente.

    La misma regla que [sync_push] y por lo mismo: quitar algo tiene que poder ganarle a haberlo
    guardado, y guardarlo otra vez tiene que poder ganarle a haberlo quitado. Comparte la secuencia
    de `contador` de la casa, así que los dos libros se leen con un solo cursor.
    """
    if not filas:
        return 0

    aplicadas = 0
    with SYNC_LOCK, sync_db() as conn:
        valor = conn.execute(
            "SELECT valor FROM secuencia WHERE casa = ?", (house_id,)
        ).fetchone()
        contador = valor[0] if valor else 0

        for fila in filas:
            try:
                perfil = int(fila["perfil"])
                obra = str(fila["obra"]).strip()[:200]
                cambiado_en = int(fila["cambiado_en"])
                guardado_en = int(fila.get("guardado_en") or cambiado_en)
                borrado = 1 if fila.get("borrado") else 0
            except (KeyError, TypeError, ValueError):
                continue
            if not obra or perfil < 0:
                continue

            previo = conn.execute(
                "SELECT cambiado_en FROM lista WHERE casa = ? AND perfil = ? AND obra = ?",
                (house_id, perfil, obra),
            ).fetchone()
            if previo and previo[0] >= cambiado_en:
                continue

            contador += 1
            aplicadas += 1
            conn.execute(
                "INSERT INTO lista "
                "(casa, perfil, obra, guardado_en, cambiado_en, borrado, contador) "
                "VALUES (?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (casa, perfil, obra) DO UPDATE SET "
                "guardado_en = excluded.guardado_en, cambiado_en = excluded.cambiado_en, "
                "borrado = excluded.borrado, contador = excluded.contador",
                (house_id, perfil, obra, guardado_en, cambiado_en, borrado, contador),
            )

        if aplicadas:
            conn.execute(
                "INSERT INTO secuencia (casa, valor) VALUES (?, ?) "
                "ON CONFLICT (casa) DO UPDATE SET valor = excluded.valor",
                (house_id, contador),
            )
    return aplicadas


def sync_forget(house_id):
    """Se lleva el progreso y la lista de una casa cuando la casa se borra del panel."""
    with SYNC_LOCK, sync_db() as conn:
        conn.execute("DELETE FROM progreso WHERE casa = ?", (house_id,))
        conn.execute("DELETE FROM lista WHERE casa = ?", (house_id,))
        conn.execute("DELETE FROM secuencia WHERE casa = ?", (house_id,))


# ---------------------------------------------------------- cuándo se usó por última vez la cuenta

ACTIVITY_PATH = os.path.join(STATE_DIR, "actividad.json")
ACTIVITY_LOCK = threading.Lock()


MAX_LINEUP = 300

ACCESS_LOG = "/var/log/nginx/access.log"
# Cuánto hace falta haber visto a una app para darla por despierta. La consulta es cada dos minutos,
# así que cinco deja pasar una perdida sin dar por muerta a una casa que está perfectamente.
APP_ALIVE_SECONDS = 5 * 60
# Sólo el final del registro. Con seis casas consultando cada dos minutos son unas pocas decenas de
# kilobytes por hora: leer el fichero entero en cada carga de la página sería pagar por un historial
# que a esta pregunta no le sirve de nada.
ACCESS_LOG_TAIL = 256 * 1024

LOG_LINE = re.compile(
    r"\[(?P<t>[^\]]+)\]\s+\"GET (?P<ruta>/[^\s\"?]+)"
)


def apps_awake():
    """
    Qué documentos de casa se han pedido hace poco, por su ruta: `{ruta: epoch}`.

    Es la única señal honesta de que la app de una casa está despierta y **se enteraría** de un
    recado. `active_cons` del proveedor no lo es: dice que la cuenta tiene un stream abierto, que
    puede ser cualquier reproductor de cualquiera —y ése no lee nuestro documento— y a la vez se
    queda corto con una app abierta sin reproducir, que sí lo lee.

    Tiene además una propiedad que sale gratis: un APK viejo sólo pide su documento al arrancar y al
    encenderse la tele, así que no aparece aquí y su casa sale apagada. Que es la verdad — ése no
    obedecería la orden aunque se la mandaran.

    Devuelve None cuando el registro no se puede leer. No es lo mismo que «nadie está despierto», y
    quien llama lo distingue.
    """
    try:
        tamano = os.path.getsize(ACCESS_LOG)
        with open(ACCESS_LOG, "rb") as handle:
            if tamano > ACCESS_LOG_TAIL:
                handle.seek(tamano - ACCESS_LOG_TAIL)
                handle.readline()  # la primera línea queda cortada por la mitad
            crudo = handle.read().decode("utf-8", "replace")
    except Exception:
        return None

    visto = {}
    for linea in crudo.splitlines():
        encaje = LOG_LINE.search(linea)
        if not encaje:
            continue
        ruta = encaje.group("ruta")
        if not ruta.endswith("/provider.json"):
            continue
        try:
            marca = encaje.group("t")
            sello, desfase = marca.split()
            momento = calendar.timegm(time.strptime(sello, "%d/%b/%Y:%H:%M:%S"))
            # La hora del registro lleva su huso pegado, y el del servidor no tiene por qué ser el
            # mismo: sin descontarlo, dos husos de diferencia son dos horas de app «dormida».
            signo = -1 if desfase.startswith("-") else 1
            momento -= signo * (int(desfase[1:3]) * 3600 + int(desfase[3:5]) * 60)
        except Exception:
            continue
        if momento > visto.get(ruta, 0):
            visto[ruta] = momento
    return visto


def lineup_path(house_id):
    return os.path.join(WATCH_DIR, f"{house_id}.canales.json")


def read_lineup(house_id):
    """
    Los canales que dice tener esta casa, o lista vacía.

    Los manda la app, y es la única fuente honesta: el panel conoce los dos mil nombres crudos del
    proveedor, no los rótulos que produce la curación de la app. Escribir aquí una copia de esas
    reglas sería tenerlas en dos idiomas y verlas separarse con el tiempo.
    """
    try:
        with open(lineup_path(house_id), encoding="utf-8") as handle:
            doc = json.load(handle)
        canales = doc.get("canales")
        return [str(c) for c in canales] if isinstance(canales, list) else []
    except Exception:
        return []


def read_activity():
    """Cuándo se vio por última vez cada casa en uso: `{casa: epoch}`."""
    try:
        with open(ACTIVITY_PATH, encoding="utf-8") as handle:
            doc = json.load(handle)
        return doc if isinstance(doc, dict) else {}
    except Exception:
        return {}


def note_activity(house_id):
    """
    Apunta que a esta casa se le acaba de ver una conexión abierta.

    La fuente es `active_cons` del proveedor, y ahí está la gracia: vale **haya o no haya app
    nuestra**. Una casa con un APK viejo que no informa de nada, o alguien mirando la cuenta desde
    otro reproductor, cuentan igual. El proveedor no publica ningún «visto por última vez» —sólo
    `created_at` y `exp_date`—, así que si se quiere esa fecha hay que ir anotándola.

    Lo que esto **no** es: un registro completo. Sólo se mira cuando alguien abre el panel, así que
    una semana sin abrirlo es una semana sin apuntes. La fecha que sale de aquí es un suelo —«al
    menos hasta entonces se usó»— y nunca un «no se usa desde». Se guarda como mucho una vez por
    minuto, para no reescribir el fichero en cada carga de la página.
    """
    ahora = int(time.time())
    with ACTIVITY_LOCK:
        doc = read_activity()
        if ahora - int(doc.get(house_id) or 0) < 60:
            return
        doc[house_id] = ahora
        write_json(ACTIVITY_PATH, doc)


def fecha_corta(epoch):
    """
    Una fecha para leer de un vistazo, no para calcular con ella.

    Se resuelve en el servidor y no en el navegador —al revés que el «hace 3 min» del estado— porque
    la tarjeta se dibuja entera aquí y no vale la pena que un dato que ya está escrito espere a que
    corra JavaScript. El reloj es el del servidor, que es el mismo criterio de siempre.
    """
    ahora = int(time.time())
    minutos = max(0, (ahora - int(epoch)) // 60)
    if minutos < 60:
        return f"hace {minutos} min"
    horas = minutos // 60
    if horas < 24:
        return f"hace {horas} h"
    dias = horas // 24
    if dias == 1:
        return "ayer"
    if dias < 7:
        return f"hace {dias} días"
    return time.strftime("%d/%m/%Y", time.localtime(int(epoch)))


def last_used(house_id):
    """
    Lo más reciente que se sepa de esta casa, venga de donde venga, o None si no se sabe nada.

    Dos fuentes con virtudes opuestas: lo que informa la app es exacto pero sólo existe si la casa
    lleva un APK que informe, y lo que ve el panel vale para cualquier aparato pero sólo se apunta
    cuando alguien mira. La más reciente de las dos es mejor que cualquiera por separado.
    """
    visto = read_watch(house_id) or {}
    candidatos = [
        int(visto.get("desde") or 0),
        int(read_activity().get(house_id) or 0),
    ]
    return max(candidatos) or None


# ------------------------------------------------------------------------ what the box last said

def watch_path(house_id):
    return os.path.join(WATCH_DIR, f"{house_id}.json")


def read_watch(house_id):
    try:
        with open(watch_path(house_id), encoding="utf-8") as handle:
            doc = json.load(handle)
        return doc if isinstance(doc, dict) and doc.get("canal") else None
    except Exception:
        return None


def record_watch(house_id, que, tipo="canal"):
    """
    Apunta lo que una casa dice que está viendo, sin olvidar lo anterior.

    Guardaba sólo la última cosa, que es lo que necesita el rótulo de «Viendo». Con una lista se
    puede además contestar a qué ve normalmente esta casa, que es una pregunta distinta y más útil
    cuando el aparato está apagado. Cabe de sobra: son doscientas líneas de texto corto.

    Repetir lo mismo no crea una entrada nueva, sólo mueve su hora: la app ya no vuelve a informar
    de un canal mientras siga puesto, así que un repetido seguido sólo pasa al reiniciarse.
    """
    ahora = int(time.time())
    with WATCH_LOCK:
        previo = read_watch(house_id) or {}
        historial = [
            e for e in (previo.get("historial") or [])
            if isinstance(e, dict) and e.get("que")
        ]
        if historial and historial[0].get("que") == que and historial[0].get("tipo") == tipo:
            historial[0]["cuando"] = ahora
        else:
            historial.insert(0, {"que": que, "tipo": tipo, "cuando": ahora})
        write_json(watch_path(house_id), {
            "canal": que,
            "tipo": tipo,
            "desde": ahora,
            "historial": historial[:WATCH_HISTORY],
        })


# Cómo nombran los proveedores Xtream sus canales de adultos. Dos listas y no una porque no todas
# las palabras se pueden buscar igual: «BRAZZERS» dentro de un nombre no es nada más que eso, pero
# «SEX» suelto aparece dentro de palabras que no vienen a cuento, así que ésas se buscan enteras.
ADULTO_MARCAS = (
    "XXX", "BRAZZERS", "HUSTLER", "PLAYBOY", "PENTHOUSE", "DORCEL", "REDLIGHT", "RED LIGHT",
    "VIVID", "BANGBROS", "REALITY KINGS", "NAUGHTY", "PORN", "EROTIC", "EROTIK",
)
ADULTO_PALABRAS = ("ADULT", "ADULTS", "ADULTO", "ADULTOS", "SEX", "SEXO", "SEXY", "+18", "18+")

ADULTO = re.compile(
    "|".join(
        [re.escape(m) for m in ADULTO_MARCAS]
        + [r"(?<![A-Z0-9])" + re.escape(m) + r"(?![A-Z0-9])" for m in ADULTO_PALABRAS]
    )
)

# Cuántos días atrás mira el aviso de la ficha.
ADULTO_DIAS = 10


def looks_adult(nombre):
    """
    Si el nombre de un canal parece de contenido para adultos.

    Es una heurística sobre el nombre y nada más: no hay categoría en lo que se apunta, sólo el
    rótulo que el proveedor le puso al canal. Se equivocará alguna vez en ambos sentidos, y por eso
    lo que enseña es un emoji en una ficha y no una acusación.
    """
    limpio = unicodedata.normalize("NFKD", nombre).encode("ascii", "ignore").decode()
    return bool(ADULTO.search(limpio.upper()))


def adult_recently(house_id, dias=ADULTO_DIAS):
    """Si en lo apuntado de los últimos `dias` hay algo que lo parezca."""
    doc = read_watch(house_id) or {}
    desde = time.time() - dias * 86400
    for entry in doc.get("historial") or []:
        if not isinstance(entry, dict):
            continue
        try:
            cuando = int(entry.get("cuando") or 0)
        except (TypeError, ValueError):
            continue
        if cuando >= desde and looks_adult(str(entry.get("que") or "")):
            return True
    return False


def watch_history(house_id):
    """
    Lo apuntado, agrupado: lo último de cada cosa, y cuántas veces.

    «Lo último» y «lo que más ve» son dos lecturas de la misma lista y ninguna de las dos sobra —
    una dice qué está de moda esta semana en esa casa y la otra qué ponen siempre — así que cada
    fila lleva las dos: cuándo fue la última vez y cuántas veces aparece.
    """
    doc = read_watch(house_id) or {}
    grupos = {"canal": {}, "serie": {}, "pelicula": {}}
    for entry in doc.get("historial") or []:
        if not isinstance(entry, dict):
            continue
        tipo = entry.get("tipo") or "canal"
        que = str(entry.get("que") or "").strip()
        if tipo not in grupos or not que:
            continue
        fila = grupos[tipo].setdefault(que, {"que": que, "cuando": 0, "veces": 0})
        fila["veces"] += 1
        try:
            fila["cuando"] = max(fila["cuando"], int(entry.get("cuando") or 0))
        except (TypeError, ValueError):
            pass

    def ultimos(tipo, cuantos):
        return sorted(grupos[tipo].values(), key=lambda f: f["cuando"], reverse=True)[:cuantos]

    return {
        "canales": ultimos("canal", 10),
        "series": ultimos("serie", 5),
        "pelis": ultimos("pelicula", 5),
    }


def house_for_token(offered):
    """
    Which household a report belongs to, by the token it carries.

    The token is the identity: a box cannot type a password, and it already reads its own token out
    of the same document it reads its account from. Compared in constant time, and against every
    household in turn, because a report arrives before anybody has said who is sending it.
    """
    if not offered:
        return None
    for casa in houses():
        doc, _ = read_provider(casa["provider"])
        expected = (doc or {}).get("reportToken") or ""
        if expected and hmac.compare_digest(offered, expected):
            return casa
    return None


# ---------------------------------------------------------------------------- asking the supplier

def provider_status(doc, timeout=8):
    """
    What the supplier says about one account, asked live.

    Deliberately the *client* API — the same `player_api.php` the television calls — because that is
    all these credentials can open. It answers whether the account authenticates, how many of its
    connections are in use, and when it expires. It does not answer what is being watched; nothing
    reachable with these credentials does.

    Asking costs nothing that matters: this is the login endpoint, not a stream, so it does not take
    the one connection the account allows.
    """
    base = (doc.get("url") or "").rstrip("/")
    user = doc.get("username") or ""
    secret = doc.get("password") or ""
    if not (base and user and secret):
        return {"error": "sin credenciales"}

    query = urllib.parse.urlencode({"username": user, "password": secret})
    request = urllib.request.Request(
        f"{base}/player_api.php?{query}",
        headers={"User-Agent": doc.get("userAgent") or "SimpleTV/1.0"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            info = (json.load(response) or {}).get("user_info") or {}
    except urllib.error.HTTPError as error:
        return {"error": f"el servidor respondió {error.code}"}
    except Exception:
        # Deliberately not the exception text: it can carry the host, and this page gets read out
        # loud down a telephone often enough.
        return {"error": "no responde"}

    def number(key):
        try:
            return int(str(info.get(key, "")).strip())
        except ValueError:
            return None

    expires = number("exp_date")
    return {
        "auth": info.get("auth") in (1, "1"),
        "estado": info.get("status") or "?",
        "activas": number("active_cons"),
        "maximo": number("max_connections"),
        "prueba": str(info.get("is_trial", "0")) == "1",
        "caduca": time.strftime("%d/%m/%Y", time.localtime(expires)) if expires else None,
    }


def all_status():
    """
    Every household at once.

    In parallel because they are separate suppliers' servers and the slow one should not decide how
    long the fast ones take: asked one after another, three households mean three round trips end to
    end for a page that is already waiting.
    """
    casas = houses()
    if not casas:
        return {}

    def one(casa):
        doc, _ = read_provider(casa["provider"])
        status = provider_status(doc or {})
        # Una conexión abierta es la casa usándose, sea con nuestra app o con lo que sea. Se apunta
        # al pasar, que es la única forma de tener una fecha de último uso sin depender del APK.
        if (status.get("activas") or 0) > 0:
            note_activity(casa["id"])
        # Sólo lo último, nunca la lista entera. Esto lo pide la página sola cada vez que se abre;
        # lo que alguien ha estado viendo se manda cuando se pulsa «Qué ve» y no antes.
        visto = read_watch(casa["id"])
        status["visto"] = (
            {"canal": visto["canal"], "desde": visto.get("desde")} if visto else None
        )
        return casa["id"], status

    with ThreadPoolExecutor(max_workers=min(8, len(casas))) as pool:
        return dict(pool.map(one, casas))


# --------------------------------------------------------------------------------------- the page

# Dibujada aquí y no traída de ninguna parte: es la única de la página, son seis trazos, y una
# hoja de iconos entera —o una petición a un CDN— para esto sería pagar mucho por muy poco.
PAPELERA = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v5M14 11v5'/></svg>"
)

STYLE = r"""
:root{
  --ink:#03070a; --ink-2:#070d12; --panel:#0b141b; --line:#16242e;
  --phos:#5ef2a0; --phos-dim:#2f8f61; --amber:#ffc16b; --alarm:#ff6b6b;
  --text:#c8dbe4; --mute:#617785;
  --mono:ui-monospace,"SF Mono","JetBrains Mono","Cascadia Mono",Menlo,Consolas,"Roboto Mono",monospace;

  /* Tres radios y cinco tamaños, y nada fuera de esta lista. Había trece tamaños de letra y seis
     radios puestos a ojo, cada uno a dos centésimas del de al lado — diferencias que nadie ve por
     separado pero que juntas hacen que la página parezca montada por partes. */
  --r-sm:3px;    /* lo que se pulsa o se escribe: botones, campos */
  --r-md:6px;    /* lo que informa: avisos */
  --r-lg:14px;   /* lo que contiene: tarjetas, fichas */

  --t-xs:.64rem; /* etiquetas y rótulos */
  --t-sm:.72rem; /* texto secundario */
  --t-md:.8rem;  /* filas de datos */
  --t-lg:.94rem; /* campos y nombres */
  --t-xl:1rem;   /* títulos */

  /* Un solo anillo de foco para toda la página. Sin esto cada control heredaba el del navegador,
     que en un tema oscuro es blanco y no se parece a nada de lo que hay alrededor. */
  --foco:0 0 0 3px rgba(94,242,160,.16);
}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{
  margin:0; background:var(--ink); color:var(--text); font:15px/1.55 var(--mono);
  background-image:
    radial-gradient(120% 80% at 50% -10%, #0d2029 0%, transparent 60%),
    repeating-linear-gradient(0deg, rgba(94,242,160,.028) 0 1px, transparent 1px 3px);
  min-height:100vh; padding:0 0 3rem;
}
.wrap{max-width:680px;margin:0 auto;padding:0 1.1rem}

/* En `:where()` para que no tenga peso: cualquier regla de más abajo sigue mandando sobre el resto
   de propiedades, y esto sólo pone el anillo donde no había ninguno. */
:where(a,button,input,select,textarea,[tabindex]):focus-visible{
  outline:1px solid var(--phos-dim);outline-offset:2px;box-shadow:var(--foco);
}

header{
  border-bottom:1px solid var(--line); margin-bottom:2rem;
  padding:1.6rem 0 1.1rem; display:flex; align-items:baseline; gap:.9rem; flex-wrap:wrap;
}
h1{
  margin:0; font-size:var(--t-xl); letter-spacing:.34em; text-transform:uppercase;
  color:var(--phos); font-weight:600; text-shadow:0 0 18px rgba(94,242,160,.32);
}
.tag{color:var(--mute); font-size:var(--t-sm); letter-spacing:.2em; text-transform:uppercase}
.live{margin-left:auto;display:flex;align-items:center;gap:.5rem;color:var(--phos-dim);font-size:var(--t-sm);letter-spacing:.18em}
/* El paso de una vista a la otra. Un enlace y no un botón: es navegación, no una acción, así que
   debe poder abrirse en otra pestaña y sobrevivir a que el JavaScript no cargue. */
.ir{
  background:transparent;border:1px solid var(--line);color:var(--mute);text-decoration:none;
  font:inherit;font-size:var(--t-xs);letter-spacing:.16em;text-transform:uppercase;
  padding:.42rem .72rem;border-radius:var(--r-sm);white-space:nowrap;
  transition:color .16s,border-color .16s;
}
.ir:hover{color:var(--phos);border-color:var(--phos-dim)}
a.nada{color:var(--phos-dim);text-decoration:none;border-bottom:1px dotted var(--phos-dim)}
a.nada:hover{color:var(--phos)}
.dot{width:7px;height:7px;border-radius:50%;background:var(--phos);box-shadow:0 0 10px var(--phos);animation:p 2.4s ease-in-out infinite}
.dot.gris{background:var(--mute);box-shadow:none;animation:none}
.dot.mal{background:var(--alarm);box-shadow:0 0 10px var(--alarm);animation:none}
@keyframes p{0%,100%{opacity:1}50%{opacity:.25}}

.lede{color:var(--mute);font-size:var(--t-md);margin:-1rem 0 2rem;line-height:1.7}

fieldset{border:0;margin:0 0 1.6rem;padding:0}
legend{
  padding:0; font-size:var(--t-sm); letter-spacing:.26em; text-transform:uppercase;
  color:var(--phos-dim); margin-bottom:.85rem;
}
label{display:block;font-size:var(--t-sm);letter-spacing:.16em;text-transform:uppercase;color:var(--mute);margin:0 0 .4rem}
input[type=text],input[type=password]{
  width:100%; padding:.78rem .9rem; background:var(--ink-2); color:var(--text);
  border:1px solid var(--line); border-radius:var(--r-sm); font:inherit; font-size:var(--t-lg);
  transition:border-color .16s, box-shadow .16s;
}
input:focus{outline:0;border-color:var(--phos-dim);box-shadow:0 0 0 3px rgba(94,242,160,.09)}
input::placeholder{color:#3b4c58}
select{
  width:100%; padding:.78rem .9rem; background:var(--ink-2); color:var(--text);
  border:1px solid var(--line); border-radius:var(--r-sm); font:inherit; font-size:var(--t-lg);
}

.card{
  background:var(--panel); border:1px solid var(--line); border-radius:var(--r-md);
  padding:1.15rem; margin-bottom:1rem; position:relative; overflow:hidden;
}
.card::before{content:"";position:absolute;inset:0 auto 0 0;width:2px;background:var(--phos-dim);opacity:.55}
.card.shared{background:linear-gradient(180deg,#0c1922,#0a141b)}
.card h2{margin:0 0 .15rem;font-size:var(--t-lg);letter-spacing:.2em;text-transform:uppercase;color:var(--text)}
.cerdo{font-size:var(--t-xl);letter-spacing:normal;cursor:help;vertical-align:-1px}
.card .que{font-size:var(--t-xs);color:#465a67;margin:0 0 1.05rem;letter-spacing:.12em;text-transform:uppercase}
.grid{display:grid;gap:.9rem;grid-template-columns:1fr 1fr}
@media(max-width:560px){.grid{grid-template-columns:1fr}}
.full{grid-column:1/-1}
.hint{font-size:var(--t-sm);color:var(--mute);margin:.4rem 0 0}
.pw{position:relative}
.pw button{
  position:absolute;right:.45rem;top:50%;transform:translateY(-50%);
  background:transparent;border:1px solid var(--line);color:var(--mute);
  font:inherit;font-size:var(--t-xs);letter-spacing:.14em;padding:.32rem .5rem;border-radius:var(--r-sm);cursor:pointer;
}
.pw button:hover{color:var(--phos);border-color:var(--phos-dim)}

.gente{margin-top:1.1rem}
.persona{display:flex;align-items:center;gap:.7rem;margin-bottom:.5rem}
.persona input[type=text]{flex:1}
.nino{display:flex;align-items:center;gap:.4rem;font-size:var(--t-xs);letter-spacing:.12em;
  text-transform:uppercase;color:var(--mute);white-space:nowrap;margin:0}
.nino input{accent-color:var(--phos-dim);width:16px;height:16px}
button.mas{
  margin-top:.15rem;background:transparent;border:1px dashed var(--line);color:var(--mute);
  font:inherit;font-size:var(--t-xs);letter-spacing:.14em;text-transform:uppercase;
  padding:.5rem .8rem;border-radius:var(--r-sm);cursor:pointer;
}
button.mas:hover{color:var(--phos);border-color:var(--phos-dim)}

.fila{display:flex;justify-content:space-between;gap:1rem;padding:.32rem 0;font-size:var(--t-md)}
.k{color:var(--mute);letter-spacing:.1em;text-transform:uppercase;font-size:var(--t-sm)}
.v{color:var(--text);text-align:right}
.v.bien{color:var(--phos)} .v.ojo{color:var(--amber)} .v.mal{color:var(--alarm)}

.bolita{
  display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:.6rem;
  vertical-align:middle;position:relative;top:-1px;
}
.bolita.gris{background:var(--mute);opacity:.5}

/* --- La rejilla de casas ----------------------------------------------------------------------
   Una tarjeta por casa y nada más que quepa de un vistazo: el nombre, si está en marcha y qué hay
   puesto. Todo lo demás —la cuenta, la gente, el historial— vive dentro y se abre al pulsarla. */
.casas{display:grid;gap:.75rem;grid-template-columns:repeat(auto-fill,minmax(190px,1fr))}
.tarjeta{
  background:var(--panel);border:1px solid var(--line);border-radius:var(--r-lg);
  padding:.85rem 1rem .9rem;position:relative;overflow:hidden;cursor:pointer;
  transition:border-color .16s,transform .06s;
}
.tarjeta::before{content:"";position:absolute;inset:0 auto 0 0;width:2px;background:var(--phos-dim);opacity:.55}
.tarjeta:hover{border-color:var(--phos-dim)}
.tarjeta:focus-visible{outline:0;border-color:var(--phos);box-shadow:0 0 0 3px rgba(94,242,160,.09)}
/* Capitalizado y no en mayúsculas: son nombres de personas y de casas, no rótulos. */
.tarjeta h2{margin:0 0 .1rem;font-size:var(--t-lg);letter-spacing:.02em;color:var(--text)}
.tarjeta .que{font-size:var(--t-xs);color:#465a67;margin:0 0 .55rem;letter-spacing:.12em;text-transform:uppercase}
/* Una fila reservada: el estado llega después de dibujar la página, y sin esto la rejilla entera
   da un salto cuando contestan los proveedores. Ahora es una sola línea —el «viendo»— porque la
   caducidad se mudó a la ficha, así que se guarda el hueco de una. */
.tarjeta .hoja{padding-top:0;min-height:1.6rem}
/* En la tarjeta, el estado va como una línea suelta y no como etiqueta + valor. Con tres tarjetas
   por fila no caben las dos columnas —«VIENDO» se lleva un tercio del ancho y parte el texto en
   dos renglones— y la etiqueta tampoco hacía falta: si está en marcha lo dice la bolita. */
.tarjeta .linea{font-size:var(--t-sm);color:var(--mute);line-height:1.35}
.tarjeta .linea.bien{color:var(--phos)}
.tarjeta .linea.mal{color:var(--alarm)}
/* La etiqueta en una línea y el valor el que necesite. Al revés —que es lo que pasa por omisión—
   «Lo último» se parte en dos y la tarjeta crece un renglón por un título de dos palabras. */
.tarjeta .fila{align-items:baseline}
.tarjeta .k{white-space:nowrap}

/* --- La ficha, en modal ------------------------------------------------------------------------
   <dialog> de verdad: el navegador ya sabe cerrar con Esc, atrapar el foco dentro y pintar el velo.
   Salen del servidor con `open` a propósito, y es JavaScript quien las cierra al cargar — así, si
   el guion no llega, la página se queda con todas las fichas desplegadas en línea, que es feo pero
   se puede seguir usando. Un panel donde no se pueda cambiar una contraseña no sirve de nada. */
/* Clavada arriba en vez de centrada. Centrada, cada pestaña con un alto distinto movía la ficha
   entera —cabecera, pestañas y todo— y había que ir a buscar con el ratón la pestaña siguiente,
   que acababa de irse de debajo del puntero. Con el margen de arriba fijo, lo que crece o mengua
   lo hace hacia abajo y nada de lo que se pulsa se mueve. */
dialog.ficha{
  width:min(620px,calc(100vw - 2rem));max-height:calc(100vh - 12vh);
  margin:6vh auto auto;
  padding:0;overflow:auto;border-radius:var(--r-lg);
  background:var(--panel);color:var(--text);border:1px solid var(--line);
}
dialog.ficha::backdrop{background:rgba(3,7,10,.78)}
/* Al abrirla, el foco cae en la propia ficha y el navegador le pinta su anillo blanco alrededor
   del marco entero. El anillo es útil, pero sobre el control que tiene el foco, no sobre la caja
   que lo contiene: dentro hay campos y botones que ya traen el suyo. */
dialog.ficha:focus,dialog.ficha:focus-visible{outline:none}
dialog.ficha[open]:not(:modal){
  position:static;margin:0 0 1rem;width:auto;max-height:none;
}
.cabeza{display:flex;align-items:center;gap:.9rem;padding:1.15rem 1.15rem 0}
.cabeza h2{margin:0;flex:1;font-size:var(--t-xl);letter-spacing:.02em;color:var(--text)}
.cerrar{
  background:transparent;border:1px solid var(--line);color:var(--mute);font:inherit;
  /* 2.4rem ≈ 38px. La guía pide 44 para el dedo; en una cabecera de 3rem eso deja la cruz
     tocando los bordes, así que se queda en 38 con espacio muerto alrededor que no compite con
     nada más pulsable. */
  font-size:var(--t-md);line-height:1;width:2.4rem;height:2.4rem;border-radius:50%;cursor:pointer;
  display:flex;align-items:center;justify-content:center;
}
.cerrar:hover{color:var(--text);border-color:var(--phos-dim)}
/* Reserva el renglón de la caducidad antes de que llegue, para que la hoja de «Cuenta» ya se mida
   con él puesto y la ficha no crezca cuando conteste el proveedor. */
.cuentaestado{min-height:1.7rem;margin-top:.4rem}
dialog.ficha .pestanas{margin:1rem 1.15rem 0}
dialog.ficha .hoja{padding:1.15rem}
dialog.ficha .acciones{margin:0 1.15rem 1.15rem}
.bolita.viendo{background:var(--phos);box-shadow:0 0 9px var(--phos);animation:p 2.4s ease-in-out infinite}
.bolita.parada{background:var(--alarm);opacity:.75}

.pestanas{display:flex;gap:.5rem;margin-top:1.2rem;border-bottom:1px solid var(--line)}
.pes{
  background:transparent;border:0;border-bottom:2px solid transparent;color:var(--mute);
  font:inherit;font-size:var(--t-xs);letter-spacing:.16em;text-transform:uppercase;
  padding:.55rem .7rem;cursor:pointer;margin-bottom:-1px;
}
.pes:hover{color:var(--text)}
.pes.activa{color:var(--phos);border-bottom-color:var(--phos-dim)}
/* Una pestaña que no lleva a ninguna parte se ve apagada desde la barra, sin abrirla. El `title`
   dice por qué, que es lo que el gris solo no cuenta. `:hover` aparte, o seguiría iluminándose al
   pasar por encima y prometiendo lo que no va a hacer. */
.pes:disabled{color:#33454f;cursor:not-allowed}
.pes:disabled:hover{color:#33454f}
.hoja{padding-top:.85rem;min-height:2.4rem}
.visto .grupo{font-size:var(--t-xs);letter-spacing:.22em;text-transform:uppercase;
  color:var(--phos-dim);margin:.95rem 0 .25rem}
.visto .grupo:first-child{margin-top:0}
.visto .fila{align-items:baseline}
.visto .que{color:var(--text);text-align:left;word-break:break-word}
.visto .cuando{color:var(--mute);font-size:var(--t-xs);white-space:nowrap;letter-spacing:.06em}

.url{font-size:var(--t-xs);color:#465a67;word-break:break-all;margin:.9rem 0 0}
/* Rojo de salida y no sólo al pasar por encima: es el único botón de la página que no se puede
   deshacer, y a un icono sin palabra hay que dejarle el color diciendo de qué va. */
.retirar{
  background:transparent;border:1px solid var(--line);color:var(--alarm);
  display:flex;align-items:center;justify-content:center;
  /* `stretch` y no un relleno a ojo: los dos botones de esta fila tienen tipografías distintas
     —uno lleva texto y el otro un dibujo— así que cuadrar los altos con `padding` sale mal en
     cuanto cambia una fuente. Que lo decida la fila. */
  align-self:stretch;padding:0 .85rem;border-radius:var(--r-sm);cursor:pointer;opacity:.72;
  transition:opacity .16s,border-color .16s,background .16s;
}
.retirar:hover{opacity:1;border-color:var(--alarm);background:rgba(255,107,107,.08)}

.acciones{
  display:flex;align-items:center;gap:.7rem;margin-top:1.2rem;
  padding-top:1.05rem;border-top:1px dashed var(--line);
}
/* `display:flex` le gana al `display:none` que el navegador le pone a cualquier cosa con `hidden`,
   así que esconder esta fila en la pestaña de sólo mirar no hacía absolutamente nada. */
.acciones[hidden]{display:none}
button.save{
  flex:1;padding:.8rem;background:var(--phos);color:#04150c;border:0;border-radius:var(--r-sm);
  font:inherit;font-weight:700;font-size:var(--t-md);letter-spacing:.22em;text-transform:uppercase;cursor:pointer;
  transition:filter .16s, transform .06s;
}
button.save:hover{filter:brightness(1.12)}
/* Todo lo que se pulsa se hunde un pixel. Antes sólo lo hacían «Guardar» y las tarjetas, así que
   media página respondía al dedo y la otra media parecía muerta. */
button:active:not(:disabled),.tarjeta:active{transform:translateY(1px)}
/* `not-allowed` y no `default`: el botón cambia de color y de texto a «Sin cambios», pero es el
   cursor el que lo dice antes de llegar a leerlo. */
button.save:disabled{
  background:transparent;color:var(--mute);border:1px solid var(--line);
  cursor:not-allowed;filter:none;transform:none;
}

/* El «guardado» no es algo que haya que leer: es algo que hay que ver y olvidar. Ocupaba una
   franja arriba del todo que empujaba el formulario hacia abajo justo después de tocarlo, y en el
   móvil dejaba el campo que acababas de editar fuera de la pantalla. Los errores siguen siendo
   .msg, porque ésos sí hay que leerlos y no deben irse solos. */
.toasts{
  position:fixed;left:50%;transform:translateX(-50%);bottom:1.7rem;z-index:20;
  display:flex;flex-direction:column;align-items:center;gap:.5rem;
  width:max-content;max-width:calc(100% - 2.2rem);pointer-events:none;
}
.toast{
  background:#08161d;border:1px solid var(--phos-dim);border-left:3px solid var(--phos);
  border-radius:var(--r-sm);padding:.72rem 1.15rem;color:var(--phos);
  font-size:var(--t-sm);letter-spacing:.18em;text-transform:uppercase;
  box-shadow:0 12px 34px rgba(0,0,0,.6),0 0 22px rgba(94,242,160,.14);
  opacity:0;animation:toast 3.4s cubic-bezier(.2,.85,.25,1) forwards;
}
.toast b{font-weight:700}
/* Los rojos no se van solos: hay que leerlos. Se quitan pulsándolos, y para eso hacen falta los
   punteros que la pila de avisos deja pasar de largo. */
.toast.mal{
  border-color:var(--alarm);border-left-color:var(--alarm);color:var(--alarm);
  box-shadow:0 12px 34px rgba(0,0,0,.6),0 0 22px rgba(255,107,107,.14);
  pointer-events:auto;cursor:pointer;animation:toast-entra .3s ease forwards;
  text-transform:none;letter-spacing:.02em;font-size:var(--t-md);
}
@keyframes toast{
  0%{opacity:0;transform:translateY(14px)}
  7%{opacity:1;transform:translateY(0)}
  80%{opacity:1;transform:translateY(0)}
  100%{opacity:0;transform:translateY(6px)}
}
@keyframes toast-entra{0%{opacity:0;transform:translateY(14px)}100%{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){
  .toast{animation:toast-quieto 3.4s linear forwards}
  .toast.mal{animation:none;opacity:1}
  @keyframes toast-quieto{0%,80%{opacity:1}100%{opacity:0}}
}

.msg{border-radius:var(--r-md);padding:.9rem 1.05rem;margin-bottom:1.6rem;font-size:var(--t-md);line-height:1.6}
.ok{background:rgba(94,242,160,.07);border:1px solid var(--phos-dim);color:var(--phos)}
.bad{background:rgba(255,107,107,.07);border:1px solid var(--alarm);color:var(--alarm)}
.msg ul{margin:.45rem 0 0;padding-left:1.1rem}
.msg code{color:var(--text);word-break:break-all}

/* Dar de alta una casa se hace tres veces en la vida, así que en la portada es una línea y no un
   formulario. De trazo discontinuo y del mismo redondeo que las tarjetas: se lee como el hueco
   donde iría la siguiente. */
.nueva{
  width:100%;display:flex;align-items:center;justify-content:center;gap:.6rem;
  padding:.95rem;background:transparent;border:1px dashed var(--line);border-radius:var(--r-lg);
  color:var(--phos-dim);font:inherit;font-size:var(--t-sm);letter-spacing:.2em;text-transform:uppercase;
  cursor:pointer;transition:color .16s,border-color .16s,background .16s;
}
.nueva:hover{color:var(--phos);border-color:var(--phos-dim);background:rgba(94,242,160,.05)}
.nueva .sig{font-size:1.2rem;line-height:1;position:relative;top:-1px}
"""

SCRIPT = r"""
// Los nombres de canal y de película los escribe el proveedor, llegan por JSON y acaban en
// innerHTML. Escapar aquí es la única barrera que hay en ese camino.
function escapar(t){
  return String(t == null ? "" : t).replace(/[&<>"]/g, function(c){
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];
  });
}

// La hora es la del servidor, no la del aparato: el reloj de un TV box de treinta euros no es algo
// sobre lo que apoyar un «hace 12 min».
function hace(desde){
  var mins = Math.max(0, Math.round((Date.now()/1000 - desde) / 60));
  if (mins < 60) return 'hace ' + mins + ' min';
  if (mins < 60 * 20) return 'hace ' + Math.round(mins / 60) + ' h';
  return new Date(desde * 1000).toLocaleString('es-ES',
    {day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit'});
}

// Guardar sólo cuando haya algo que guardar. Empieza habilitado en el HTML y lo deshabilita esto:
// al revés, un fallo de JavaScript dejaría una página en la que no se puede guardar nada.
document.querySelectorAll('form.seccion').forEach(function(form){
  // El del pie y no «el primero que haya»: la pestaña de «Poner canal» tiene su propio botón verde,
  // y como cae antes en el documento, era ése el que esto encontraba — lo dejaba deshabilitado y
  // rotulado «Sin cambios», que es exactamente lo que no hace: no guarda nada, manda un recado.
  var boton = form.querySelector('.acciones.pie button.save');
  if (!boton) return;
  var titulo = boton.textContent;
  var campos = [], inicial = [];

  function valor(i){ return i.type === 'checkbox' ? i.checked : i.value; }
  function revisar(){
    boton.disabled = !campos.some(function(i, n){ return valor(i) !== inicial[n]; });
    boton.textContent = boton.disabled ? 'Sin cambios' : titulo;
  }
  // Los campos se apuntan de uno en uno y no de una sentada, porque las filas de persona pueden
  // aparecer después: una fila recién creada arranca con su valor actual como el de partida, así
  // que añadirla y no escribir nada no cuenta como un cambio pendiente de guardar.
  function vigilar(i){
    if (i.type === 'hidden') return;
    campos.push(i); inicial.push(valor(i));
    i.addEventListener('input', revisar);
    i.addEventListener('change', revisar);
  }
  form.querySelectorAll('input').forEach(vigilar);

  var mas = form.querySelector('button.mas');
  var nuevas = form.querySelector('.nuevas');
  if (mas && nuevas) {
    var n = nuevas.querySelectorAll('.persona').length;
    mas.addEventListener('click', function(){
      var pre = mas.getAttribute('data-prefijo') + 'perfil.nuevo.' + n;
      n++;
      var fila = document.createElement('div');
      fila.className = 'persona';
      var nombre = document.createElement('input');
      nombre.type = 'text'; nombre.name = pre; nombre.placeholder = 'añadir persona…';
      var etiqueta = document.createElement('label');
      etiqueta.className = 'nino';
      var nino = document.createElement('input');
      nino.type = 'checkbox'; nino.name = pre + '.infantil';
      etiqueta.appendChild(nino);
      etiqueta.appendChild(document.createTextNode('infantil'));
      fila.appendChild(nombre); fila.appendChild(etiqueta);
      nuevas.appendChild(fila);
      vigilar(nombre); vigilar(nino);
      nombre.focus();
    });
  }
  revisar();
});

document.querySelectorAll('.pw button').forEach(function(b){
  b.addEventListener('click', function(){
    var i = b.parentNode.querySelector('input');
    var shown = i.type === 'text';
    i.type = shown ? 'password' : 'text';
    b.textContent = shown ? 'VER' : 'OCULTAR';
  });
});

// «Qué ve»: el historial se pide al pulsar y no antes. Es la única parte de la página que lee lo
// que alguien ha estado viendo, y no tiene por qué salir a la vista de cualquiera que abra el
// panel para cambiar una contraseña.
// Un aviso rojo se queda hasta que se lea. Pulsarlo lo quita.
document.querySelectorAll('.toast.mal').forEach(function(t){
  t.addEventListener('click', function(){ t.remove(); });
  t.title = 'Pulsa para descartar';
});

// Las fichas. Salen del servidor abiertas para que sin JavaScript la página siga siendo usable;
// lo primero que hace esto es cerrarlas, y a partir de ahí las abre la tarjeta.
(function(){
  var fichas = document.querySelectorAll('dialog.ficha');
  if (!fichas.length) return;

  // Medir antes de cerrar. Las fichas salen del servidor abiertas y en línea, así que éste es el
  // único momento en que las tres hojas están puestas y se pueden medir: dándole a todas el alto
  // de la más alta, cambiar de pestaña ya no mueve el pie de la ficha. La de «Qué ve» está vacía
  // todavía —se rellena al abrirla— así que el mínimo sale de las dos que traen campos.
  fichas.forEach(function(f){
    var hojas = f.querySelectorAll('.hoja');
    var alto = 0;
    hojas.forEach(function(h){ alto = Math.max(alto, h.offsetHeight); });
    if (alto) hojas.forEach(function(h){ h.style.minHeight = alto + 'px'; });
  });

  fichas.forEach(function(f){ f.close(); });

  // showModal cuando lo hay: es lo que atrapa el foco dentro, pinta el velo y hace que Esc cierre
  // sin que haya que escribirlo. `open` a secas es el respaldo para lo que no lo tenga.
  function abrirFicha(f){
    if (!f) return;
    if (f.showModal) f.showModal(); else f.open = true;
  }

  function abrir(id){
    abrirFicha(document.getElementById('casa-' + id));
  }

  document.querySelectorAll('.tarjeta').forEach(function(t){
    var id = t.getAttribute('data-casa');
    t.addEventListener('click', function(){ abrir(id); });
    // La tarjeta es un <article role=button>, así que el teclado hay que atenderlo a mano: un
    // botón de verdad no puede envolver el bloque de estado sin romper el HTML.
    t.addEventListener('keydown', function(e){
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); abrir(id); }
    });
  });

  fichas.forEach(function(f){
    var cerrar = f.querySelector('[data-cerrar]');
    if (cerrar) cerrar.addEventListener('click', function(){ f.close(); });
    // Pulsar el velo. El <dialog> recibe el click del velo como suyo, así que lo que distingue
    // «fuera» de «dentro» es que el punto pulsado caiga fuera de su rectángulo.
    f.addEventListener('click', function(e){
      if (e.target !== f) return;
      var c = f.getBoundingClientRect();
      var dentro = e.clientX >= c.left && e.clientX <= c.right &&
                   e.clientY >= c.top  && e.clientY <= c.bottom;
      if (!dentro) f.close();
    });
  });

  // El botón de dar de alta abre su ficha, que es una más de éstas.
  var boton = document.querySelector('.nueva');
  var alta = document.getElementById('alta');
  if (boton && alta) boton.addEventListener('click', function(){ abrirFicha(alta); });

  // Y si el alta falló, el servidor la marca para que se vuelva a abrir con el error dentro: con
  // el velo puesto, un aviso en la banda de arriba de la página es un aviso que nadie ve.
  var marcada = document.querySelector('dialog.ficha[data-abrir]');
  if (marcada) abrirFicha(marcada);

  // Al volver de guardar. El servidor redirige con #casa-<id> para dejarte donde estabas.
  if (location.hash.indexOf('#casa-') === 0) abrir(location.hash.slice(6));
})();

// Las pestañas de una ficha. Cada botón dice qué hoja enseña y la hoja es `<hoja>-<casa>`, así que
// añadir una pestaña es añadir un botón y un div, sin tocar esto.
document.querySelectorAll('.pestanas').forEach(function(barra){
  var id = barra.getAttribute('data-casa');
  var botones = barra.querySelectorAll('.pes');
  var hojas = {};
  botones.forEach(function(b){
    var cual = b.getAttribute('data-hoja');
    hojas[cual] = document.getElementById(cual + '-' + id);
  });
  var caja = hojas.uso;
  var pedido = false;

  // «Qué ve» no tiene ni un campo: es una lista que se lee. Dejar ahí el botón de guardar era
  // ofrecer una acción que no va con lo que se está mirando.
  var ficha = barra.closest('dialog');
  var acciones = ficha ? ficha.querySelector('.acciones.pie') : null;

  function ensenar(cual){
    Object.keys(hojas).forEach(function(nombre){
      if (hojas[nombre]) hojas[nombre].hidden = nombre !== cual;
    });
    // Por atributo de la hoja y no por su nombre: así una pestaña nueva que no guarde nada —«Poner
    // canal» es la segunda— sólo tiene que decirlo en su propio div, que es donde se sabe.
    var hoja = hojas[cual];
    if (acciones) acciones.hidden = !!(hoja && hoja.hasAttribute('data-sin-guardar'));
  }

  // El estado de partida lo pone esto y no el servidor, por lo mismo que las fichas salen abiertas:
  // sin JavaScript las tres hojas se ven una debajo de otra, que es feo y se puede usar. Con él,
  // aquí se esconden las dos que no toca antes de que a nadie le dé tiempo a verlas.

  function grupo(titulo, lista){
    if (!lista || !lista.length) return '';
    return '<p class=grupo>' + titulo + '</p>' + lista.map(function(f){
      return '<div class=fila><span class="v que">' + escapar(f.que) + '</span>' +
             '<span class=cuando>' + hace(f.cuando) +
             (f.veces > 1 ? ' · ' + f.veces + ' veces' : '') + '</span></div>';
    }).join('');
  }

  function pedirHistorial(){
    if (pedido || !caja) return;
    pedido = true;
    caja.innerHTML = '<p class=hint>preguntando…</p>';
    fetch('historial?casa=' + encodeURIComponent(id), {cache: 'no-store'})
      .then(function(r){ return r.json(); })
      .then(function(d){
        var html = grupo('Últimos canales', d.canales) +
                   grupo('Últimas series', d.series) +
                   grupo('Últimas películas', d.pelis);
        caja.innerHTML = html || '<p class=hint>Nada apuntado todavía. La app avisa cuando algo ' +
          'se queda puesto un rato, así que esto se llena solo en cuanto la casa la tenga.</p>';
      })
      .catch(function(){
        caja.innerHTML = '<p class=hint>No se pudo consultar.</p>';
        pedido = false;
      });
  }

  var inicial = barra.querySelector('.pes.activa') || botones[0];
  if (inicial) ensenar(inicial.getAttribute('data-hoja'));

  botones.forEach(function(pes){
    pes.addEventListener('click', function(){
      botones.forEach(function(otra){ otra.classList.toggle('activa', otra === pes); });
      var cual = pes.getAttribute('data-hoja');
      ensenar(cual);
      // Sólo al mirarla. Es la única parte del panel que enseña lo que alguien ha estado viendo,
      // y no tiene por qué salir a la vista de quien abre esto a cambiar una clave.
      if (cual === 'uso') pedirHistorial();
    });
  });
});

// El estado se pide aparte del render: consultar a los proveedores tarda lo que tarden sus
// servidores, y el formulario no tiene por qué esperarles para aparecer.
(function(){
  var punto = document.getElementById('punto');
  var rotulo = document.getElementById('rotulo');

  function pinta(id, estado){
    var bolita = document.getElementById('bolita-' + id);
    if (bolita) bolita.className = 'bolita ' + estado;
  }

  function fila(k, v, clase){
    return '<div class=fila><span class=k>' + k + '</span>' +
           '<span class="v ' + (clase || '') + '">' + v + '</span></div>';
  }

  // Lo de la tarjeta: una línea y ya. Ver `.tarjeta .linea`.
  function linea(v, clase){
    return '<div class="linea ' + (clase || '') + '">' + v + '</div>';
  }


  fetch('estado', {cache: 'no-store'}).then(function(r){ return r.json(); }).then(function(todo){
    var vivas = 0, total = 0, viendo = 0;
    Object.keys(todo).forEach(function(id){
      var caja = document.getElementById('estado-' + id);
      if (!caja) return;
      var d = todo[id];
      total++;

      var vence = document.getElementById('vence-' + id);

      if (d.error) {
        // Ni verde ni roja: no es que no haya nadie viendo, es que no lo sabemos. Y por lo mismo el
        // botón de mandar canal se queda como estaba: negar una acción por no haber podido
        // preguntar sería convertir un fallo del proveedor en una limitación nuestra.
        pinta(id, 'gris');
        caja.innerHTML = linea(escapar(d.error), 'mal');
        if (vence) vence.innerHTML = '';
        return;
      }

      var vivo = d.auth && d.estado === 'Active';
      if (vivo) vivas++;
      var enUso = d.activas > 0;
      if (enUso) viendo++;
      var html = '';

      // Si hay alguien viendo cabe en una bolita, y ahí es donde va: es lo que se mira de un
      // vistazo desde el otro lado de la mesa, y ocupaba una fila entera de texto.
      pinta(id, enUso ? 'viendo' : 'parada');

      // Un informe viejo no describe lo que hay puesto ahora: la app avisa al quedarse en algo y
      // no vuelve a decir nada, así que pasadas unas horas sólo sirve como «lo último que se vio».
      var fresco = d.visto && (Date.now()/1000 - d.visto.desde) < 12 * 3600;

      // El «qué» sólo cuando se sabe. Antes había una fila explicando por qué no se sabía, y era
      // una línea de disculpa en la ficha de cada casa que todavía no ha informado nunca.
      // El «qué» sólo cuando se sabe. Si está en marcha o parada no se escribe: lo dice la bolita
      // del título, y repetirlo con palabras es una fila entera para no decir nada nuevo.
      if (enUso && fresco) {
        html += linea(escapar(d.visto.canal) + ' · ' + hace(d.visto.desde), 'bien');
      } else if (d.visto) {
        html += linea(escapar(d.visto.canal) + ' · ' + hace(d.visto.desde));
      }

      // La cuenta rechazada sí se queda en la tarjeta: es una alarma, no un dato, y la bolita no
      // la distingue de una casa en reposo — las dos son rojas.
      if (!vivo) html += linea('cuenta ' + escapar(d.estado || 'rechazada'), 'mal');

      caja.innerHTML = html;
      if (vence) {
        vence.innerHTML = d.caduca
          ? fila('Caduca', d.caduca + (d.prueba ? ' (prueba)' : ''))
          : '';
      }
    });

    punto.className = 'dot' + (total && vivas ? '' : ' mal');
    rotulo.textContent = !total ? 'sin casas'
                       : viendo ? (viendo + ' en uso')
                       : (vivas === total ? 'en reposo' : 'revisar cuentas');
  }).catch(function(){
    punto.className = 'dot mal'; rotulo.textContent = 'sin respuesta';
  });
})();
"""


def esc(value):
    return html.escape(str(value or ""), quote=True)


def when(mtime):
    return time.strftime("%d/%m/%Y %H:%M", time.localtime(mtime)) if mtime else "nunca escrito"


def shell(titulo, nav=""):
    """
    Todo lo que hay antes del contenido, que es idéntico en las dos vistas.

    La barra de estado va aquí y no sólo en la portada a propósito: lo que consulta es si los
    proveedores contestan, que es exactamente de lo que trata la vista del servidor.
    """
    return [
        "<!doctype html><html lang=es><head><meta charset=utf-8>",
        "<meta name=viewport content='width=device-width,initial-scale=1'>",
        "<meta name=color-scheme content=dark>",
        f"<title>{esc(titulo)} · Control</title>",
        f"<style>{STYLE}</style></head><body><div class=wrap>",
        f"<header><h1>{esc(titulo)}</h1><span class=tag>panel de control</span>",
        nav,
        "<span class=live><span class='dot gris' id=punto></span>",
        "<span id=rotulo>consultando</span></span></header>",
    ]


def alerts(errors=(), note=None, avisos=()):
    """
    Todo lo que la página tiene que decir, como avisos flotantes.

    Ninguno de estos mensajes es parte de la página: son la respuesta a algo que se acaba de
    pulsar. Como bandas encima del contenido empujaban hacia abajo justo lo que se acababa de
    tocar, y en el móvil dejaban fuera de pantalla el campo recién editado.

    Los verdes se van solos. Los rojos no: hay que leerlos, y se quitan pulsándolos.
    """
    fuera = []
    if note:
        fuera.append((note, ""))
    fuera += [(esc(e), "mal") for e in errors]
    # Distinto de un error: esto sí se guardó. Lo que no se pudo fue repartirlo.
    fuera += [(esc(a), "mal") for a in avisos]
    return fuera


def tail(guardado=None, toasts=()):
    """
    El cierre. Se llama con el <div class=wrap> ya cerrado, porque los avisos van flotando encima.

    `guardado` no es texto del formulario —es «servidor» o el nombre de una casa que existe— pero
    se escapa igual.
    """
    fuera = list(toasts)
    if guardado:
        fuera.insert(0, (f"<b>{esc(guardado)}</b> guardado", ""))

    out = ["<div class=toasts role=status aria-live=polite>"]
    for texto, clase in fuera:
        out.append(f"<div class='toast {clase}'>{texto}</div>")
    out.append("</div>")
    out.append(f"<script>{SCRIPT}</script></body></html>")
    return out


def render_server(guardado=None, errors=(), avisos=()):
    """
    La vista del servidor: la dirección y el User-Agent, y nada más.

    En su propia página y no en la portada porque son cosas de ritmos distintos. Las casas se tocan
    a menudo —alguien cambia de contraseña, entra un sobrino nuevo— y esto se toca el día que el
    proveedor mueve el servidor, que es una vez al año. Tenerlo arriba del todo de la portada era
    poner lo que casi nunca cambia delante de lo que se viene a hacer.
    """
    shared = server()

    # El enlace nombra su destino, y su destino ya no se llama «Casas».
    out = shell("Servidor", "<a class=ir href='./'>← Videoclub</a>")
    avisar = alerts(errors=errors, avisos=avisos)

    out.append("<fieldset><legend>Proveedor</legend>")
    out.append("<form method=post action='servidor' class=seccion><div class='card shared'>")
    out.append("<label for=url>Dirección</label>")
    out.append(
        f"<input type=text id=url name=url value='{esc(shared.get('url'))}' "
        "placeholder='http://servidor.com:8080' autocapitalize=off autocorrect=off spellcheck=false>"
    )
    out.append("<div style='margin-top:1.1rem'><label for=userAgent>User-Agent</label>")
    out.append(
        f"<input type=text id=userAgent name=userAgent value='{esc(shared.get('userAgent'))}' "
        "placeholder='SimpleTV/1.0' autocapitalize=off autocorrect=off spellcheck=false></div>"
    )
    out.append("<div class=acciones><button class=save type=submit>Guardar servidor</button></div>")
    out.append("</div></form></fieldset>")

    # Cierra el <div class=wrap> que abrió la cabecera.
    out.append("</div>")
    out += tail(guardado, avisar)
    return "".join(out)


def render(guardado=None, errors=(), note=None, avisos=(), alta=()):
    casas = houses()

    out = shell("Videoclub", "<a class=ir href='servidor'>Servidor →</a>")
    avisar = alerts(errors=errors, note=note, avisos=avisos)

    out.append("<fieldset><legend>Casas</legend>")
    if not casas:
        out.append(
            "<div class=card><p class=hint>Ninguna todavía.</p></div>"
        )
    else:
        # Las fichas van detrás de la rejilla y no dentro de ella: son <dialog>, y sin JavaScript
        # salen desplegadas en línea — dentro de un grid quedarían repartidas por las columnas.
        tarjetas, fichas = zip(*(render_house(casa) for casa in casas))
        out.append("<div class=casas>" + "".join(tarjetas) + "</div>")
        out.extend(fichas)
    out.append("</fieldset>")

    boton, ficha = render_alta(alta)
    # Sin rótulo de sección encima: el botón ya dice lo que hace, y «Añadir casa» dos veces
    # seguidas es una de las dos de más.
    out.append("<fieldset>")
    out.append(boton)
    out.append("</fieldset>")
    # Fuera del <fieldset> y de la rejilla, como las de las casas: es un <dialog>, y sin JavaScript
    # sale desplegado en línea.
    out.append(ficha)
    # Cierra el <div class=wrap> que abrió la cabecera.
    out.append("</div>")
    out += tail(guardado, avisar)
    return "".join(out)


def render_alta(errors=()):
    """
    Dar de alta una casa: el botón que se ve y la ficha que abre, igual que una casa cualquiera.

    Ocupaba media portada estando debajo de todo y usándose tres veces en la vida. Ahora es una
    línea, y los campos viven donde viven los de las demás — que además arregla de paso el botón
    «VER»: la regla `.alta button` pintaba *todos* los botones de aquella rejilla, así que el que
    va dentro del campo de contraseña salía grande y verde en vez de la pastilla gris de siempre.

    Los errores se pintan dentro y no en la banda de arriba: con la ficha abierta, el velo tapa la
    página, así que un aviso ahí arriba es un aviso que nadie ve.
    """
    campos = [
        f"<dialog class=ficha id=alta{' data-abrir' if errors else ''}>",
        "<div class=cabeza><h2>Añadir casa</h2>"
        "<button type=button class=cerrar data-cerrar aria-label=Cerrar "
        "title='Cerrar (Esc)'>✕</button></div>",
        "<form method=post action='crear'>",
        "<div class=hoja>",
    ]
    if errors:
        campos.append(
            "<div class='msg bad'>No se ha creado:<ul>"
            + "".join(f"<li>{esc(e)}</li>" for e in errors)
            + "</ul></div>"
        )
    campos.append("<div class=grid>")
    campos.append(
        "<div><label for=nombre>Nombre</label>"
        "<input type=text id=nombre name=nombre placeholder='Ej.: Casa del pueblo' required></div>"
    )
    # Sin opción preseleccionada: cuál de las dos aplicaciones corre en esa casa decide qué campos
    # tendrá y qué APK hay que llevarle, y no es algo que deba salir elegido por orden alfabético.
    campos.append("<div><label for=app>Aplicación</label><select id=app name=app required>")
    campos.append("<option value='' selected disabled>elige…</option>")
    for key, meta in APPS.items():
        campos.append(f"<option value='{esc(key)}'>{esc(meta['label'])}</option>")
    campos.append("</select></div>")
    campos.append(
        "<div><label for=nuevo-user>Usuario</label>"
        "<input type=text id=nuevo-user name=username required "
        "autocapitalize=off autocorrect=off spellcheck=false></div>"
    )
    campos.append(
        "<div><label for=nuevo-pass>Contraseña</label><div class=pw>"
        "<input type=password id=nuevo-pass name=password required "
        "autocapitalize=off autocorrect=off spellcheck=false>"
        "<button type=button>VER</button></div></div>"
    )
    campos.append("</div>")
    campos.append("</div>")
    campos.append("<div class=acciones><button class=save type=submit>Crear casa</button></div>")
    campos.append("</form></dialog>")

    boton = (
        "<button type=button class=nueva data-abrir-alta>"
        "<span class=sig>+</span><span>Añadir casa</span></button>"
    )
    return boton, "".join(campos)


def render_house(casa):
    """
    Una casa, en dos piezas: la tarjeta que se ve y la ficha que se abre al pulsarla.

    Se parten porque se leen en momentos distintos. Lo que se viene a mirar —quién está viendo algo
    ahora— cabe en tres líneas y tiene que estar a la vista de las cuatro casas a la vez; lo que se
    viene a cambiar —una contraseña, un sobrino nuevo— es de una casa sola y no debería obligar a
    bajar por encima de las otras tres para llegar.

    La ficha se devuelve entera y ya escrita, no se pide después: son los mismos campos de siempre,
    ya están en el servidor que dibuja la página, y una petición más sólo añadiría una espera y una
    forma nueva de que esto no funcione.
    """
    doc, mtime = read_provider(casa["provider"])
    broken = doc is None
    if broken:
        doc = {}
    ident = esc(casa["id"])
    prefix = ident + "."
    app = APPS.get(casa["app"], {"label": casa["app"]})

    aviso = ""
    if adult_recently(casa["id"]):
        aviso = (
            f" <span class=cerdo title='Contenido para adultos en los últimos "
            f"{ADULTO_DIAS} días'>🐷</span>"
        )

    # --------------------------------------------------------------------------------- la tarjeta
    # Debajo del nombre iba el de la aplicación. Sobra desde que sólo hay una: repetir «videoclub»
    # en todas las tarjetas no distingue ninguna de ninguna. En ese hueco va ahora la última vez que
    # se supo de la casa, que sí las distingue — y sólo cuando se sabe, porque una casa recién dada
    # de alta no tiene ninguna y un «nunca» ahí se leería como una avería.
    usada = ""
    cuando = last_used(casa["id"])
    if cuando:
        usada = (
            f"<p class=que title='Última vez que se vio esta casa en uso'>"
            f"{esc(fecha_corta(cuando))}</p>"
        )

    # La bolita nace gris: lo que hay o no hay en marcha lo contesta el proveedor, y eso llega
    # después de que la página se dibuje. Gris es «todavía no lo sé», que es verdad al abrir.
    tarjeta = (
        f"<article class=tarjeta tabindex=0 role=button data-casa='{ident}' "
        f"aria-haspopup=dialog aria-label='{esc(casa['nombre'])}'>"
        f"<h2><span class='bolita gris' id='bolita-{ident}'></span>{esc(casa['nombre'])}{aviso}</h2>"
        f"{usada}"
        f"<div class=hoja id='estado-{ident}'></div>"
        "</article>"
    )

    # ----------------------------------------------------------------------------------- la ficha
    # Las dos pestañas que dependen de si la casa va en modo simple. Se sabe aquí, leyendo su
    # documento, sin esperar a nadie.
    #
    # **Mandar un canal es sólo para las simples.** En una casa con videoclub, quien esté delante
    # tiene mando y menús para cambiar de canal él mismo, y puede estar viendo una película — a la
    # que una orden de sintonizar le caería encima sin venir a cuento. La caja simple es lo
    # contrario: una tele que sólo hace canales y a la que hay que llegar desde fuera.
    #
    # **Y las personas son sólo para las que no lo son.** El modo simple no tiene selector de
    # personas ni «Seguir viendo»: editar ahí una lista que nadie va a mirar es ofrecer un ajuste
    # que no ajusta nada.
    es_simple = bool(doc.get("simple"))

    canales_de_la_casa = read_lineup(casa["id"]) if es_simple else []

    # Dos condiciones para poder mandarle un canal, y las dos se saben aquí: que la casa haya dicho
    # qué canales tiene, y que su app esté despierta ahora mismo. Ver [apps_awake].
    despiertas = apps_awake() if es_simple else {}
    ruta_doc = urllib.parse.urlsplit(casa.get("url") or "").path
    if despiertas is None:
        # El registro no se deja leer. Eso es un problema nuestro, no una respuesta sobre esta casa:
        # se deja pasar, como con la bolita gris cuando el proveedor no contesta.
        despierta, motivo_dormida = True, ""
    else:
        visto_en = despiertas.get(ruta_doc, 0)
        despierta = time.time() - visto_en < APP_ALIVE_SECONDS
        motivo_dormida = "La app de esta casa no está abierta ahora mismo"

    if not canales_de_la_casa:
        apagada = (" disabled data-sin-lista"
                   " title='Esta casa todavía no ha dicho qué canales tiene'")
    elif not despierta:
        apagada = f" disabled title='{esc(motivo_dormida)}'"
    else:
        apagada = ""
    pestana_poner = (
        f"<button type=button class=pes data-hoja='poner' "
        f"data-tab-poner='{ident}'{apagada}>Poner canal</button>"
    ) if es_simple else ""

    pestana_perfiles = (
        "<button type=button class=pes data-hoja='perfiles' disabled"
        " title='Una casa en modo simple no tiene selector de personas'>Perfiles</button>"
        if es_simple
        else "<button type=button class=pes data-hoja='perfiles'>Perfiles</button>"
    )

    out = [
        f"<dialog class=ficha id='casa-{ident}' open>",
        # El formulario de borrar va primero y aparte, nunca dentro del de guardar: HTML no admite
        # formularios anidados — el navegador se come el interior y el botón acaba enviando el
        # otro, que es como «Retirar» acabó guardando.
        f"<form method=post action='borrar' id='borrar-{ident}'>"
        f"<input type=hidden name=casa value='{ident}'></form>",
        # Igual que el de borrar, y por lo mismo: HTML no admite formularios anidados, así que vive
        # aquí fuera y los controles de dentro se le atan con `form=`.
        f"<form method=post action='poner' id='mandar-{ident}'>"
        f"<input type=hidden name=casa value='{ident}'></form>",
        f"<div class=cabeza><h2>{esc(casa['nombre'])}{aviso}</h2>"
        f"<button type=button class=cerrar data-cerrar aria-label=Cerrar title='Cerrar (Esc)'>✕</button></div>",
        f"<div class=pestanas data-casa='{ident}'>"
        "<button type=button class='pes activa' data-hoja='cuenta'>Cuenta</button>"
        f"{pestana_perfiles}"
        "<button type=button class=pes data-hoja='uso'>Qué ve</button>"
        f"{pestana_poner}"
        "</div>",
        "<form method=post action='casa' class=seccion>",
        f"<input type=hidden name=casa value='{ident}'>",
    ]

    # --- pestaña: cuenta
    out.append(f"<div class=hoja id='cuenta-{ident}'>")
    if broken:
        out.append(
            "<p class='hint' style='color:var(--alarm)'>JSON roto: al guardar se reescribe entero.</p>"
        )
    out.append("<div class=grid>")
    out.append(
        f"<div class=full><label for='{prefix}casa-nombre'>Nombre de la casa</label>"
        f"<input type=text id='{prefix}casa-nombre' name='{prefix}casa.nombre' "
        f"value='{esc(casa['nombre'])}'></div>"
    )
    out.append(
        f"<div><label for='{prefix}username'>Usuario</label>"
        f"<input type=text id='{prefix}username' name='{prefix}username' "
        f"value='{esc(doc.get('username'))}' autocapitalize=off autocorrect=off spellcheck=false></div>"
    )
    out.append(
        f"<div><label for='{prefix}password'>Contraseña</label><div class=pw>"
        f"<input type=password id='{prefix}password' name='{prefix}password' "
        f"value='{esc(doc.get('password'))}' placeholder='vacía' "
        "autocapitalize=off autocorrect=off spellcheck=false>"
        "<button type=button>VER</button></div></div>"
    )
    if casa["app"] == "simpletv":
        out.append(
            f"<div class=full><label for='{prefix}name'>Nombre</label>"
            f"<input type=text id='{prefix}name' name='{prefix}name' value='{esc(doc.get('name'))}' "
            "placeholder='Papá'></div>"
        )
    else:
        # Sólo la tele en directo, como SimpleTV: sin videoclub, sin pestañas, sin selector de
        # personas. Ausente en el documento significa videoclub completo, así que desmarcarla borra
        # la clave en vez de escribir `false` — coherente con cómo se leen el resto de campos.
        checked = " checked" if doc.get("simple") else ""
        out.append(
            f"<div class=full><label class=nino>"
            f"<input type=checkbox name='{prefix}simple'{checked}> Modo simple "
            "(sólo televisión en directo, como SimpleTV)</label></div>"
        )
    out.append("</div>")
    # Lo que contesta el proveedor sobre la cuenta. Se rellena solo, después de dibujar la página,
    # y aquí y no en la tarjeta: la caducidad es un dato de la cuenta y se mira el día que se va a
    # renovar, no cada vez que se abre el panel a ver quién está viendo algo.
    out.append(f"<div class=cuentaestado id='vence-{ident}'></div>")
    out.append(f"<p class=url>{esc(casa['url'])}<br>Última escritura · {esc(when(mtime))}</p>")
    out.append("</div>")

    # --- pestaña: perfiles
    out.append(f"<div class=hoja id='perfiles-{ident}'>")
    if casa["app"] == "simpletv":
        out.append("<p class=hint>SimpleTV no tiene perfiles.</p>")
    else:
        out.append("<div class=gente><label>Quién ve la tele</label>")
        for person in doc.get("perfiles") or []:
            pid = int(person.get("id", 0))
            checked = " checked" if person.get("infantil") else ""
            out.append("<div class=persona>")
            out.append(f"<input type=hidden name='{prefix}perfil.id' value='{pid}'>")
            out.append(
                f"<input type=text name='{prefix}perfil.{pid}.nombre' "
                f"value='{esc(person.get('nombre'))}'>"
            )
            out.append(
                f"<label class=nino><input type=checkbox name='{prefix}perfil.{pid}.infantil'"
                f"{checked}>infantil</label>"
            )
            out.append("</div>")
        # Un hueco vacío de salida, y un botón que crea los que hagan falta. Con uno solo había que
        # guardar entre una persona y la siguiente, que es justo lo que parecía «no me deja añadir
        # más». El contenedor existe para que el botón sepa dónde meterlas.
        out.append("<div class=nuevas><div class=persona>")
        out.append(f"<input type=text name='{prefix}perfil.nuevo.0' placeholder='añadir persona…'>")
        out.append(
            f"<label class=nino><input type=checkbox name='{prefix}perfil.nuevo.0.infantil'>infantil</label>"
        )
        out.append("</div></div>")
        out.append(f"<button type=button class=mas data-prefijo='{prefix}'>+ otra persona</button>")
        # Lo único que queda escrito: borrar a alguien se lleva su historial y no se deshace.
        out.append("<p class=hint>Sin nombre = fuera, con su historial.</p></div>")
    out.append("</div>")

    # --- pestaña: qué ve
    # Dentro del formulario aunque no tenga campos, para que «Guardar» siga estando al pie de la
    # ficha mire uno la pestaña que mire. Se rellena al abrirla y no antes: es la única parte del
    # panel que enseña lo que alguien ha estado viendo.
    # `data-sin-guardar` en las hojas que no guardan nada: es lo que le dice al guion que esconda el
    # botón de «Guardar» al abrirlas. Antes ese comportamiento estaba escrito para la pestaña de
    # «Qué ve» por su nombre, y añadir la de «Poner canal» lo habría dejado corto.
    out.append(f"<div class='hoja visto' id='uso-{ident}' data-sin-guardar></div>")

    # --- pestaña: poner un canal (sólo en las casas simples)
    #
    # Se escribe sólo si arriba se le puso pestaña. El guion esconde las hojas que conoce por sus
    # botones, así que una hoja sin botón no se escondería nunca: se quedaría a la vista, debajo de
    # la pestaña activa, en todas las casas que no son simples.
    if es_simple:
        out.append(f"<div class=hoja id='poner-{ident}' data-sin-guardar>")
        if canales_de_la_casa:
            opciones = "".join(
                f"<option value='{esc(c)}'>{esc(c)}</option>" for c in canales_de_la_casa
            )
            out.append(
                "<div class=grid><div class=full>"
                f"<label for='poner-canal-{ident}'>Canal</label>"
                f"<select id='poner-canal-{ident}' name=canal form='mandar-{ident}'>"
                f"{opciones}</select>"
                "<p class=hint>La tele lo coge en un par de minutos. La orden caduca a los diez.</p>"
                "</div></div>"
                f"<div class=acciones>"
                f"<button class=save type=submit form='mandar-{ident}'>Enviar</button></div>"
            )
        else:
            # No se llega aquí con la pestaña encendida —sin lista nace apagada— pero la hoja se
            # escribe igual: si algún día se enciende por otra vía, mejor que explique que que esté
            # en blanco.
            out.append(
                "<p class=hint>Esta casa todavía no ha dicho qué canales tiene. Los manda la app al "
                "abrir la televisión, así que hace falta que su aparato tenga una versión reciente y "
                "haya entrado ahí al menos una vez.</p>"
            )
        out.append("</div>")

    # `pie` lo distingue de la fila de acciones que ahora tiene también la pestaña de «Poner canal»:
    # el guion busca ésta para esconderla, y `querySelector('.acciones')` le habría dado la otra,
    # que aparece antes en el documento.
    out.append("<div class='acciones pie'>")
    out.append("<button class=save type=submit>Guardar</button>")
    # Atado por `form=` al formulario de borrar que se escribió arriba, fuera de éste.
    # Un icono y no una palabra, pero con `title` y `aria-label`: el dibujo dice de qué va y el
    # texto dice qué hace exactamente, que en el único botón irreversible de la página importa.
    #
    # El aviso va en un `onclick` y no colgado desde el guion, para que siga preguntando aunque el
    # guion no llegue: sin él, la única cosa de esta página que no se puede deshacer se haría de
    # una pulsación. Y el mensaje se arma con `json.dumps`, que es lo que lo hace seguro — antes se
    # metía el nombre entre comillas simples a pelo, y una casa llamada «O'Brien» partía la cadena
    # de JavaScript en dos y dejaba el botón borrando sin preguntar nada.
    aviso = json.dumps(
        f"¿Borrar «{casa['nombre']}» del panel?\n\n"
        "Su documento se queda en el servidor a propósito, así que el aparato de esa casa sigue "
        "funcionando; lo que se pierde es esta ficha."
    )
    out.append(
        f"<button class=retirar type=submit form='borrar-{ident}' "
        f"title='Borrar «{esc(casa['nombre'])}» del panel' aria-label='Borrar casa' "
        f"onclick=\"return confirm({esc(aviso)})\">"
        f"{PAPELERA}</button>"
    )
    out.append("</div>")
    out.append("</form></dialog>")
    return tarjeta, "".join(out)


# ------------------------------------------------------------------------------------- the server

class Handler(BaseHTTPRequestHandler):
    server_version = "simpletv-admin"

    def _send(self, body, status=200, head_only=False):
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        # This page renders credentials. It must not sit in a proxy, a history entry, or a
        # back-button restore.
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, private")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if not head_only:
            self.wfile.write(payload)

    def _redirect(self, query="", to="./"):
        """
        Answer a POST with a redirect, never with a page.

        Without this, the browser's own reload button re-submits the form that got here — and for
        «Crear casa» that means a second household, a second directory and a second token, from a
        gesture that means «show me that again». One of those got made today.

        [to] is where to land, relative to the page the form was on: the default is the portada, and
        the server form asks to come back to itself so that saving does not throw you out of the
        view you were working in.
        """
        self.send_response(303)
        self.send_header("Location", to + query)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _form(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        return parse_qs(self.rfile.read(length).decode("utf-8"), keep_blank_values=True)

    def do_HEAD(self):
        self._send("", head_only=True)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        if path.endswith("/salud"):
            return self._send("ok")
        if path.endswith("/estado"):
            try:
                return self._send_json(all_status())
            except Exception:
                return self._send_json({})
        if path.endswith("/sync"):
            return self._sync_pull()
        if path.endswith("/historial"):
            pedida = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            house_id = pedida.get("casa", [""])[0]
            if not any(c["id"] == house_id for c in houses()):
                return self._send_json({"error": "no"}, status=404)
            try:
                return self._send_json(watch_history(house_id))
            except Exception:
                return self._send_json({"canales": [], "series": [], "pelis": []})
        if path.endswith("/casas"):
            # For the build machine: name, app and URL. Never the credentials — the APK does not
            # need them, and this is the one endpoint a script leaves in a shell history.
            try:
                return self._send_json({"casas": [
                    {"id": c["id"], "nombre": c["nombre"], "app": c["app"], "url": c["url"]}
                    for c in houses()
                ]})
            except Exception:
                return self._send_json({"casas": []})
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        note, guardado = None, None
        if "guardado" in query:
            # Lo que llega es «servidor» o el id de una casa. Se resuelve contra lo que hay, así
            # que el aviso nunca puede decir algo que no venga de aquí dentro.
            que = query["guardado"][0]
            if que == "servidor":
                guardado = "Servidor"
            else:
                casa = next((c for c in houses() if c["id"] == que), None)
                guardado = casa["nombre"] if casa else "Cambios"
        if "borrada" in query:
            note = "Casa borrada"
        if "puesto" in query:
            # Deliberadamente «mandado» y no «puesto»: el panel no sabe si la tele estaba encendida
            # ni si llegó a hacer caso. Prometer lo segundo sería mentir la mitad de las veces.
            note = "Canal mandado · la tele lo coge en un par de minutos si está encendida"
        if "nueva" in query:
            casa = next((c for c in houses() if c["id"] == query["nueva"][0]), None)
            if casa:
                # La URL ya no se escribe aquí: la recoge `./sync-casas.sh`, que es como se hace.
                note = f"<b>{esc(casa['nombre'])}</b> creada · ./sync-casas.sh"
        # Después de los avisos, porque esta vista también enseña el suyo al guardar. Va aquí abajo
        # y no con el resto de rutas por eso mismo: necesita el `guardado` que se acaba de resolver.
        if path.endswith("/servidor"):
            try:
                return self._send(render_server(guardado=guardado))
            except Exception as error:
                return self._send(f"<pre>No se pudo leer la configuración: {esc(error)}</pre>", 500)
        try:
            self._send(render(guardado=guardado, note=note))
        except Exception as error:
            self._send(f"<pre>No se pudo leer la configuración: {esc(error)}</pre>", 500)

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        if path.endswith("/informe"):
            return self._report()
        if path.endswith("/sync"):
            return self._sync_push()
        try:
            if path.endswith("/crear"):
                form = self._form()
                casa, problem = create_house(
                    form.get("nombre", [""])[0],
                    form.get("app", [""])[0],
                    form.get("username", [""])[0],
                    form.get("password", [""])[0],
                )
                if problem:
                    return self._send(render(alta=[problem]))
                return self._redirect("?nueva=" + urllib.parse.quote(casa["id"]))
            if path.endswith("/borrar"):
                if not delete_house(self._form().get("casa", [""])[0]):
                    return self._send(render(errors=["Esa casa ya no está en la lista."]))
                return self._redirect("?borrada=1")
            if path.endswith("/poner"):
                form = self._form()
                problem = send_channel(
                    form.get("casa", [""])[0], form.get("canal", [""])[0]
                )
                if problem:
                    return self._send(render(errors=[problem]))
                return self._redirect("?puesto=1")

            # Cada sección guarda por su lado. Los errores se quedan en la página, porque son de
            # un formulario que sigue en pantalla; un guardado correcto redirige, para que recargar
            # después vuelva a leer en vez de volver a guardar.
            if path.endswith("/servidor"):
                saved, errors, avisos = apply_server(self._form())
                if saved and not avisos:
                    return self._redirect("?guardado=servidor", to="servidor")
                return self._send(
                    render_server(
                        guardado="Servidor" if saved else None, errors=errors, avisos=avisos
                    )
                )
            if path.endswith("/casa"):
                form = self._form()
                house_id = form.get("casa", [""])[0]
                saved, errors = apply_house(house_id, form)
                if saved:
                    # Con el ancla, para que la ficha se vuelva a abrir sola: guardar no debería
                    # costar tres pulsaciones para volver a donde estabas.
                    q = urllib.parse.quote(house_id)
                    return self._redirect(f"?guardado={q}#casa-{q}")
                return self._send(render(errors=errors))

            # Cualquier otro POST se rechaza en vez de tratarse como un formulario: llegaría sin
            # campos, se leería como «todo vacío» y contestaría con un muro de errores de
            # validación sobre un formulario que nadie ha enviado.
            return self._send_json({"error": "no"}, status=404)
        except Exception as error:
            self._send(render(errors=[f"{error.__class__.__name__}"]))

    def _casa_del_token(self):
        """La casa que hay detrás del token, o None. La misma credencial que usa /informe."""
        offered = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        return house_for_token(offered)

    def _sync_pull(self):
        """Lo que la casa ha visto en otros aparatos desde la última vez que éste preguntó."""
        casa = self._casa_del_token()
        if not casa:
            return self._send_json({"error": "no"}, status=401)
        pedida = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        try:
            desde = int(pedida.get("desde", ["0"])[0])
        except ValueError:
            desde = 0
        try:
            return self._send_json(sync_pull(casa["id"], max(0, desde)))
        except Exception:
            return self._send_json({"error": "disco"}, status=500)

    def _sync_push(self):
        """
        Lo que este aparato ha visto, y de vuelta lo que se ha perdido.

        Las dos mitades en una petición y no en dos: un aparato que acaba de ver algo quiere
        guardarlo y ponerse al día en el mismo gesto, y hacerlo por separado deja una ventana en la
        que lo que acaba de mandar no vuelve con lo demás.
        """
        casa = self._casa_del_token()
        if not casa:
            return self._send_json({"error": "no"}, status=401)

        length = int(self.headers.get("Content-Length", "0") or 0)
        if length < 0 or length > MAX_SYNC_BYTES:
            return self._send_json({"error": "cuerpo"}, status=400)

        try:
            doc = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
            filas = doc.get("progreso") or []
            guardadas = doc.get("lista") or []
            desde = int(doc.get("desde") or 0)
            if not isinstance(filas, list) or not isinstance(guardadas, list):
                raise ValueError
        except Exception:
            return self._send_json({"error": "cuerpo"}, status=400)

        try:
            sync_push(casa["id"], filas)
            sync_list_push(casa["id"], guardadas)
            return self._send_json(sync_pull(casa["id"], max(0, desde)))
        except Exception:
            return self._send_json({"error": "disco"}, status=500)

    def _report(self):
        """The television saying what it settled on. Terse on purpose — nothing here is a page."""
        offered = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        casa = house_for_token(offered)
        if not casa:
            return self._send_json({"error": "no"}, status=401)

        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0 or length > MAX_REPORT_BYTES:
            return self._send_json({"error": "cuerpo"}, status=400)

        try:
            doc = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return self._send_json({"error": "cuerpo"}, status=400)

        # Por la misma puerta y con la misma credencial llegan dos cosas distintas: «estoy viendo
        # esto», que es lo de siempre, y «éstos son mis canales», que es la lista con la que el panel
        # arma el desplegable de mandar un canal. Se distinguen por el campo, no por la ruta, para no
        # tener que tocar nginx ni el documento de ninguna casa ya instalada.
        if "canales" in doc:
            try:
                canales = [
                    str(c).strip()[:120] for c in doc.get("canales") or []
                    if str(c).strip()
                ][:MAX_LINEUP]
            except Exception:
                return self._send_json({"error": "cuerpo"}, status=400)
            if not canales:
                return self._send_json({"error": "cuerpo"}, status=400)
            try:
                write_json(lineup_path(casa["id"]), {"canales": canales,
                                                     "cuando": int(time.time())})
            except Exception:
                return self._send_json({"error": "disco"}, status=500)
            return self._send_json({"ok": True})

        try:
            canal = str(doc["canal"]).strip()[:120]
            # SimpleTV sólo tiene canales y no manda el campo. Videoclub, cuando informe, dirá si
            # es serie o película. El nombre del campo sigue siendo `canal` para no romper lo que
            # ya está instalado.
            tipo = str(doc.get("tipo") or "canal").strip().lower()
        except Exception:
            return self._send_json({"error": "cuerpo"}, status=400)
        if not canal or tipo not in ("canal", "serie", "pelicula"):
            return self._send_json({"error": "cuerpo"}, status=400)

        try:
            # The timestamp is the server's. The clock in a cheap set-top box is not something to
            # rest a «hace 12 min» on.
            record_watch(casa["id"], canal, tipo)
        except Exception:
            return self._send_json({"error": "disco"}, status=500)
        return self._send_json({"ok": True})

    def log_message(self, fmt, *args):
        # The default access log would record query strings. Nothing here needs a request log badly
        # enough to risk one that quotes a form post.
        pass


if __name__ == "__main__":
    # Loopback only, always. nginx terminates TLS and asks for the password; this process must not
    # be reachable without going through it.
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
