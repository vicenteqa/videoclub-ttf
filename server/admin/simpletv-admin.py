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

# The public base of the URLs this panel hands to the build. Only ever prepended to paths it
# generated itself.
#
# Set with `SIMPLETV_ADMIN_BASE` in the systemd unit. The default is a placeholder on purpose: if
# somebody forgets to set it, the URLs come out visibly wrong instead of subtly wrong, which is the
# kind of mistake discovered weeks later in an APK that never found its document.
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

# Local channels no supplier carries, offered as a checkbox per household — see [extra_channels].
# Hardcoded rather than editable from the panel: there are few of these, they change rarely, and a
# form to type one in is one more place a URL gets fat-fingered into a household's document.
CANALES_EXTRA = [
    {
        "id": "penedes-tv",
        "nombre": "Penedès TV",
        "url": "https://liveingesta118.cdnmedia.tv/rtvvilafrancalive/smil:live.smil/playlist.m3u8",
        "logo": "https://graph.facebook.com/rtvvilafranca/picture?width=200&height=200",
        "userAgent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/135.0.0.0 Safari/537.36 CrKey/1.44.191160"
        ),
    },
]

# A report bigger than this is not a report. The body is two short fields.
MAX_REPORT_BYTES = 1024

# How many watched things are remembered per household. Two hundred short text entries are a few
# kilobytes and cover months of television: the limit exists so the file does not grow without a
# ceiling, not because anything needs squeezing.
WATCH_HISTORY = 200

# Reporting is read-modify-write, and it arrives the same way as everything else: one thread per
# request. Without this, two reports in a row from the same household can lose one of the two.
WATCH_LOCK = threading.Lock()

# The same for progress: reading the counter, writing the rows and saving the new counter has to be
# one single thing, or two devices syncing at once are handed the same number.
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


def extra_channels():
    """The catalogue of local channels no supplier carries — see [CANALES_EXTRA]. Only checked on
    or off per household from here; see [apply_house]'s `canal_extra` handling."""
    return CANALES_EXTRA


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
    document["casa"] = nombre
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
    # Progress does go, unlike the document: the document stays so that a device which has not been
    # updated yet still has credentials, and that argument does not apply to where somebody got to in
    # a series. Creating the household again under the same name starts from zero.
    try:
        sync_forget(house_id)
    except Exception:
        pass
    return True


# --------------------------------------------------------------------------------------- the form

def apply_server(form):
    """
    Saves the Servidor section, and spreads it across the households.

    Kept apart from each household's form deliberately: they are two different things touched at
    different times. The address changes the day the supplier moves its server, and that day it has
    to change everywhere; a password changes for one household alone. A single button for both meant
    everything had to be right before anything could be fixed.

    Returns (saved, errors, warnings). The warnings are households that could not be updated: the
    server itself was saved, so it is not a failure of the form on screen.
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
            # That household's fields are not here, so rewriting its whole document would leave it
            # with no credentials. It is left as it is, and that is said out loud.
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
    Writes into a household's document that it should tune to a channel. Returns the problem, or None.

    It is a dated errand rather than a setting: the app obeys once and the order expires by itself
    after ten minutes. Hence writing `cuando` — without it the box would jump to that channel every
    time it re-read the document, forever.

    Arriving late or not arriving at all is within normal: the box looks at the document every two
    minutes while it is switched on, and while it is off it learns nothing. This does not promise
    delivery, it offers a convenience.
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
    Saves one household and only one. Returns (saved, errors).

    The server is not read from this form even when it arrives in it: it is read from its own view,
    which is where it lives. That way saving one household cannot take the address of all the others
    down with it.
    """
    casa = next((c for c in houses() if c["id"] == house_id), None)
    if not casa:
        return False, ["Esa casa ya no está en la lista."]

    existing, _ = read_provider(casa["provider"])
    # A file that does not parse is rewritten whole rather than merged into: merging onto rubbish is
    # how half a credential survives a repair.
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

    # The name of record lives in `casas.json`, keyed by `id` — and only the name ever changes there,
    # never the `id` itself, which is where its URL's secret segment, its Gradle flavour and its
    # `local.properties` key all come from. Renaming a household must never force its APK to be
    # rebuilt or its living room revisited. What *is* mirrored into the document below (`doc["casa"]`)
    # is this same name, so that the television can show it too — see [_report]'s neighbour, the
    # `AccountFooter` on the app's side: it used to fall back to the immutable flavour name for lack
    # of anything better, which is how "suegros" ended up on a screen that should have said "Manel".
    # A rename here reaches an already-installed box on its next poll, no rebuild involved.
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
    if casa["app"] == "videoclub":
        doc["casa"] = casa["nombre"]

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

        # Which of the hardcoded catalogue this household has checked on — see [CANALES_EXTRA].
        catalogo = {c["id"]: c for c in extra_channels()}
        marcados = set(form.get(prefix + "canal_extra", []))

        seleccion = []
        for cid in marcados:
            entry = catalogo.get(cid)
            if not entry:
                continue
            canal = {"nombre": entry["nombre"], "url": entry["url"]}
            if entry.get("logo"):
                canal["logo"] = entry["logo"]
            if entry.get("userAgent"):
                canal["userAgent"] = entry["userAgent"]
            seleccion.append(canal)
        if seleccion:
            doc["canales"] = seleccion
        else:
            doc.pop("canales", None)

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

    # New rows arrive numbered — `perfil.nuevo.0`, `.1`, … — because the page allows adding several
    # at once. The number only pairs each name with its "children only" checkbox and preserves the
    # order they were typed in; the real id is assigned here.
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


# ------------------------------------------------------ where each person got to, and in what

# Each person's progress, shared between the devices of their household.
SYNC_DB = os.path.join(STATE_DIR, "progreso.db")

# A body larger than this is not a sync. A household with years of television behind it sends a few
# hundred rows the first time and one or two after that.
MAX_SYNC_BYTES = 512 * 1024

# The most rows a `GET` will answer with. A device switched off for months catches up over several
# rounds rather than in one multi-megabyte answer.
SYNC_PAGE = 500


def sync_db():
    """
    The progress database, created on the fly the first time anybody uses it.

    ## Why the row does not carry the title's identifier

    Each device numbers its own catalogue: `title_id` is an `AUTOINCREMENT` handed out in whatever
    order the supplier's listings arrived that day. A tablet's 4711 and a phone's 4711 are different
    films. Syncing on that number would scatter "continue watching" marks across films at random.

    What *is* the same on both is `merge_key` — the thing that melts sixty Blade Runner listings into
    one work — plus the season and episode numbers. That is what travels, and each device translates
    it into its own numbering on arrival.

    ## Why rows are never deleted

    Removing something from "Continue watching" is a decision, and it has to reach the other device
    exactly as having watched half a film does. A deleted row cannot be sent, so it is marked.

    ## Why "Mi lista" is a separate table sharing one counter

    It is a different thing — saving for later has no position and no episode — but it is the same
    ledger: both are read with a single cursor per device, so the two tables share the household's
    `contador` sequence. A counter per table would force the device to carry two cursors and to
    advance both correctly on their own, which is twice as many places to lose a row.

    `perfil` is in both for the same reason: one household, one catalogue, and one list per person.
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
    """What has changed in that household after the `desde` counter: progress and list."""
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

    # The counter the device should ask from next time. If either page filled up, it is that page's
    # last row rather than the ceiling: what is missing is collected on the next round. And if both
    # filled up, the lower of the two, because there is one cursor and it cannot go further than the
    # ledger that is furthest behind.
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
                # Every row's counter and not only the final one: the receiving device may not be
                # able to place a title yet — its catalogue is still downloading — and needs to be
                # able to say "I got this far" instead of treating everything as read.
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
    Stores what a device sends. The most recent stamp wins, not the furthest along.

    It looks as though the furthest position ought to win — if you watched half a film on the phone,
    the television should not send you back to the start. But that makes watching anything again
    impossible: starting a series you finished last year from scratch would always be overruled by
    the final episode. The writing device's clock decides, which is what makes "the last thing I did"
    the thing that counts.

    Returns how many rows were applied; older ones are dropped silently, which is normal when two
    devices catch up at the same time.
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
    Stores what a device has added to or removed from "Mi lista". The most recent stamp wins.

    The same rule as [sync_push] and for the same reason: removing something has to be able to beat
    having saved it, and saving it again has to be able to beat having removed it. It shares the
    household's `contador` sequence, so both ledgers are read with one cursor.
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
    """Takes a household's progress and list with it when the household is deleted from the panel."""
    with SYNC_LOCK, sync_db() as conn:
        conn.execute("DELETE FROM progreso WHERE casa = ?", (house_id,))
        conn.execute("DELETE FROM lista WHERE casa = ?", (house_id,))
        conn.execute("DELETE FROM secuencia WHERE casa = ?", (house_id,))


# --------------------------------------------------------- when the account was last used

ACTIVITY_PATH = os.path.join(STATE_DIR, "actividad.json")
ACTIVITY_LOCK = threading.Lock()

# ---------------------------------------------------- how long a connection has been open

CONNECTION_PATH = os.path.join(STATE_DIR, "conexion.json")
CONNECTION_LOCK = threading.Lock()


MAX_LINEUP = 300

ACCESS_LOG = "/var/log/nginx/access.log"
# How recently an app has to have been seen to count as awake. The check runs every two minutes, so
# five lets one go missing without pronouncing a perfectly healthy household dead.
APP_ALIVE_SECONDS = 5 * 60
# Only the tail of the log. With six households checking every two minutes that is a few tens of
# kilobytes an hour: reading the whole file on every page load would be paying for a history that is
# of no use to this question.
ACCESS_LOG_TAIL = 256 * 1024

LOG_LINE = re.compile(
    r"\[(?P<t>[^\]]+)\]\s+\"GET (?P<ruta>/[^\s\"?]+)"
)


def apps_awake():
    """
    Which household documents have been asked for recently, by path: `{path: epoch}`.

    This is the only honest signal that a household's app is awake and **would find out** about an
    errand. The supplier's `active_cons` is not: it says the account has a stream open, which could
    be anybody's player — and that one does not read our document — while it also falls short for an
    app that is open but not playing, which does.

    It has a property that comes for free, too: an old APK only asks for its document at launch and
    when the television is switched on, so it does not appear here and its household shows as
    disabled. Which is the truth — that one would not obey the order even if it were sent.

    Returns None when the log cannot be read. That is not the same as "nobody is awake", and the
    caller tells the two apart.
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
            # The log's timestamp carries its own offset, and the server's need not be the same:
            # without subtracting it, two time zones apart is two hours of a "sleeping" app.
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
    The channels this household says it has, or an empty list.

    The app sends them, and it is the only honest source: the panel knows the supplier's two thousand
    raw names, not the labels the app's curation produces. Writing a copy of those rules here would
    mean holding them in two languages and watching them drift apart.
    """
    try:
        with open(lineup_path(house_id), encoding="utf-8") as handle:
            doc = json.load(handle)
        canales = doc.get("canales")
        return [str(c) for c in canales] if isinstance(canales, list) else []
    except Exception:
        return []


def version_path(house_id):
    return os.path.join(WATCH_DIR, f"{house_id}.version.json")


def read_running_version(house_id):
    """
    What the app itself last said it is running, or None. See `_report`'s "version" branch.

    `owner` travels with `version` and not apart from it, because the two answer one question
    together: an old version on a device that is not the owner is exactly what is expected until
    somebody visits with it in hand, and showing the number alone would read as a fault instead.
    """
    try:
        with open(version_path(house_id), encoding="utf-8") as handle:
            doc = json.load(handle)
        if not isinstance(doc, dict) or not isinstance(doc.get("version"), int):
            return None
        return doc
    except Exception:
        return None


def publish_release(house_id, version, sha256, filename):
    """
    Writes directly into a household's document that a new release is ready. The APK itself is
    already in place — `publish.sh` uploaded it by scp before calling this — so all that is left is
    for the household to find out. Returns the problem, or None.

    There is no longer a separate "staged, not yet sent" step: the device itself decides when to
    ask, via the icon beside `TV` or, in simple mode, by holding OK over the channel list — so
    holding a release back after it is uploaded protects nobody.

    Unlike [send_channel], this is not an errand with a short fuse: a release stays offered until a
    newer one replaces it, because a box that is off for a week should still find it waiting when it
    comes back — [Updater] on the app's side re-reads the document until it does.
    """
    casa = next((c for c in houses() if c["id"] == house_id), None)
    if not casa:
        return "Esa casa ya no está en la lista."
    try:
        version = int(version)
    except (TypeError, ValueError):
        return "Versión inválida."
    if version <= 0:
        return "Versión inválida."
    filename = (filename or "").strip()
    if not filename:
        return "Falta el nombre del fichero."

    doc, error = read_provider(casa["provider"])
    if doc is None:
        return error or "No se puede leer el documento de esa casa."

    segment = os.path.basename(os.path.dirname(casa["provider"]))
    web = APPS.get(casa["app"], {}).get("web", "/videoclub")
    doc["apk"] = {
        "version": version,
        "url": f"{PUBLIC_BASE}{web}/{segment}/{filename}",
        "sha256": (sha256 or "").strip(),
        "cuando": int(time.time()),
    }
    try:
        write_json(casa["provider"], doc, mode=0o644)
    except Exception:
        return "No se ha podido escribir el documento."
    return None


def read_activity():
    """When each household was last seen in use: `{household: epoch}`."""
    try:
        with open(ACTIVITY_PATH, encoding="utf-8") as handle:
            doc = json.load(handle)
        return doc if isinstance(doc, dict) else {}
    except Exception:
        return {}


def note_activity(house_id):
    """
    Notes that this household has just been seen with a connection open.

    The source is the supplier's `active_cons`, and that is the point: it works **whether or not our
    app is involved**. A household on an old APK that reports nothing, or somebody watching the
    account from another player, count just the same. The supplier publishes no "last seen" of its
    own — only `created_at` and `exp_date` — so if that date is wanted it has to be recorded.

    What this is **not**: a complete record. It is only looked at when somebody opens the panel, so a
    week without opening it is a week without entries. The date coming out of here is a floor — "it
    was in use at least until then" — and never a "unused since". It is written at most once a
    minute, to avoid rewriting the file on every page load.
    """
    ahora = int(time.time())
    with ACTIVITY_LOCK:
        doc = read_activity()
        if ahora - int(doc.get(house_id) or 0) < 60:
            return
        doc[house_id] = ahora
        write_json(ACTIVITY_PATH, doc)


def read_connections():
    try:
        with open(CONNECTION_PATH, encoding="utf-8") as handle:
            doc = json.load(handle)
        return doc if isinstance(doc, dict) else {}
    except Exception:
        return {}


def connection_started(house_id, activas):
    """
    Since when this household's connection has been open without a gap, or None while it is closed.

    Sampled the same way [note_activity] is — only when somebody has the panel open, at most once
    every couple of minutes — so this is a floor too: a connection open for an hour before anybody
    first looked today reads as "just started". What it fixes is worse: without remembering
    anything, every page load would have nothing truthful to say about how long a connection already
    open has lasted, because the only timestamp on hand — when the current channel or title was
    settled on — answers "what", not "since when connected", and the two drift apart the moment
    someone leaves a film running for an hour without touching anything.
    """
    with CONNECTION_LOCK:
        doc = read_connections()
        if activas > 0:
            if not doc.get(house_id):
                doc[house_id] = int(time.time())
                write_json(CONNECTION_PATH, doc)
            return doc.get(house_id)
        if doc.get(house_id):
            doc.pop(house_id, None)
            write_json(CONNECTION_PATH, doc)
        return None


def fecha_corta(epoch):
    """
    A date to be read at a glance, not to be calculated with.

    Resolved on the server rather than in the browser — the opposite of the status line's "hace
    3 min" — because the card is drawn whole here, and a value that is already known is not worth
    making wait for JavaScript to run. The clock is the server's, which is the same rule as always.
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
    The most recent thing known about this household, wherever it came from, or None if nothing is.

    Two sources with opposite virtues: what the app reports is exact but only exists if the household
    runs an APK that reports, and what the panel sees works for any device but is only recorded when
    somebody looks. The more recent of the two beats either on its own.
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


def _read_watch_raw(house_id):
    """The watch file exactly as stored, historial included — unlike [read_watch], which answers
    None once nothing is playing right now (see [record_stopped])."""
    try:
        with open(watch_path(house_id), encoding="utf-8") as handle:
            doc = json.load(handle)
        return doc if isinstance(doc, dict) else {}
    except Exception:
        return {}


def read_watch(house_id):
    doc = _read_watch_raw(house_id)
    return doc if doc.get("canal") else None


def record_watch(house_id, que, tipo="canal"):
    """
    Notes what a household says it is watching, without forgetting what came before.

    It used to keep only the latest thing, which is all the "Viendo" line needs. With a list it can
    also answer what this household usually watches, which is a different and more useful question
    when the device is switched off. It fits easily: two hundred lines of short text.

    Repetir lo mismo no crea una entrada nueva, sólo mueve su hora: la app ya no vuelve a informar
    de un canal mientras siga puesto, así que un repetido seguido sólo pasa al reiniciarse.
    """
    ahora = int(time.time())
    with WATCH_LOCK:
        previo = _read_watch_raw(house_id)
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


def record_stopped(house_id):
    """
    Videoclub saying it is no longer the one playing anything here — sent when it goes to the
    background, which on a box with one screen usually means somebody switched to a different
    client on the same account.

    Sin esto, "Viendo Drácula" se quedaba en la ficha hasta doce horas después de que alguien
    hubiera cambiado a otra app: el aviso de que un informe viejo ya no describe lo que hay puesto
    ahora (ver `fresco` en el script) solo protegía frente al tiempo, no frente a un cambio de
    cliente el mismo rato. Esto lo cierra de raíz en vez de acortar esa ventana a ciegas: se borra
    la línea de "ahora mismo" (para que [read_watch] vuelva a contestar None) sin tocar el
    historial, así que si la cuenta sigue en uso el panel pasa a "Cliente desconocido" en vez de
    seguir dando crédito a Videoclub.
    """
    with WATCH_LOCK:
        historial = _read_watch_raw(house_id).get("historial") or []
        write_json(watch_path(house_id), {"historial": historial})


# How Xtream suppliers name their adult channels. Two lists rather than one because not every word
# can be searched for the same way: "BRAZZERS" inside a name is nothing but that, whereas a bare
# "SEX" turns up inside words that have nothing to do with it, so those are matched whole.
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

# How many days back the warning on the household card looks.
ADULTO_DIAS = 10


def looks_adult(nombre):
    """
    Whether a channel's name looks like adult content.

    A heuristic on the name and nothing more: there is no category in what gets recorded, only the
    label the supplier gave the channel. It will be wrong occasionally in both directions, which is
    why what it shows is an emoji on a card rather than an accusation.
    """
    limpio = unicodedata.normalize("NFKD", nombre).encode("ascii", "ignore").decode()
    return bool(ADULTO.search(limpio.upper()))


def adult_recently(house_id, dias=ADULTO_DIAS):
    """Whether anything recorded in the last `dias` days looks like it."""
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
    What was recorded, grouped: the latest of each thing, and how many times.

    "The latest" and "what they watch most" are two readings of the same list and neither is
    redundant — one says what is popular in that household this week and the other what they always
    put on — so every row carries both: when it last happened and how many times it appears.
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
        activas = status.get("activas") or 0
        # An open connection is the household being used, whether with our app or with anything
        # else. It is noted in passing, which is the only way to have a last-used date that does not
        # depend on the APK.
        if activas > 0:
            note_activity(casa["id"])
        # Two different questions, both answered here so the card does not have to guess which one
        # it is looking at: "since when has this been open" only means anything while it is open,
        # and "when was it last seen" only means anything once it is not.
        status["conectado_desde"] = connection_started(casa["id"], activas)
        status["ultima_vez"] = last_used(casa["id"])
        # Only the latest, never the whole list. The page asks for this by itself every time it is
        # opened; what somebody has been watching is sent when "Qué ve" is pressed and not before.
        visto = read_watch(casa["id"])
        status["visto"] = (
            {"canal": visto["canal"], "desde": visto.get("desde")} if visto else None
        )
        return casa["id"], status

    with ThreadPoolExecutor(max_workers=min(8, len(casas))) as pool:
        return dict(pool.map(one, casas))


# --------------------------------------------------------------------------------------- the page

# Drawn here rather than fetched from anywhere: it is the only one on the page, it is six strokes,
# and a whole icon sheet — or a request to a CDN — for this would be paying a lot for very little.
PAPELERA = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v5M14 11v5'/></svg>"
)

# Same treatment: hand-drawn, not fetched from anywhere. The two ways off this page, drawn rather
# than named in text, so both fit next to the title without turning the header into a sentence.
CASA = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M3 11l9-7 9 7M5 10v9h14v-9M10 19v-6h4v6'/></svg>"
)
SERVIDOR = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M4 4h16v4H4zM4 10h16v4H4zM4 16h16v4H4zM7 6h.01M7 12h.01M7 18h.01'/></svg>"
)
# The third way off the page: an arrow into a tray, the usual pictogram for "download" — same
# treatment as the two above, hand-drawn rather than fetched.
DESCARGAS = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M12 3v12m0 0l-4.5-4.5M12 15l4.5-4.5M4 19h16'/></svg>"
)

# The usual open-eye / crossed-out-eye pair for "ver la contraseña" — drawn once here, toggled with
# `hidden` in JS rather than swapped in and out of the DOM, so there is nothing for the button's own
# click handler to build.
OJO = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z'/><circle cx='12' cy='12' r='3'/></svg>"
)
OJO_CERRADO = (
    "<svg viewBox='0 0 24 24' width=15 height=15 fill=none stroke=currentColor stroke-width=1.8 "
    "stroke-linecap=round stroke-linejoin=round aria-hidden=true>"
    "<path d='M3 3l18 18M9.9 5.2A10.4 10.4 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4M6.5 6.7C4 8.4 2 12 2 12s3.6 7 10 7c1.3 0 2.5-.2 3.6-.7'/>"
    "<path d='M9.5 9.6A3 3 0 0 0 14.4 13.4'/></svg>"
)
BOTON_VER = (
    f"<button type=button title=Ver aria-label=Ver>"
    f"<span class=ojo-abierto>{OJO}</span>"
    f"<span class=ojo-cerrado hidden>{OJO_CERRADO}</span></button>"
)

STYLE = r"""
:root{
  --ink:#03070a; --ink-2:#070d12; --panel:#0b141b; --line:#16242e;
  --phos:#5ef2a0; --phos-dim:#2f8f61; --amber:#ffc16b; --alarm:#ff6b6b;
  --text:#c8dbe4; --mute:#617785;
  --mono:ui-monospace,"SF Mono","JetBrains Mono","Cascadia Mono",Menlo,Consolas,"Roboto Mono",monospace;

  /* Three radii and five sizes, and nothing outside this list. There used to be thirteen font sizes
     and six radii set by eye, each two hundredths from its neighbour — differences nobody sees on
     their own but which together make a page look assembled from parts. */
  --r-sm:3px;    /* what gets pressed or typed into: buttons, fields */
  --r-md:6px;    /* what informs: notices */
  --r-lg:14px;   /* what contains: cards, dialogs */

  --t-xs:.64rem; /* labels and captions */
  --t-sm:.72rem; /* texto secundario */
  --t-md:.8rem;  /* filas de datos */
  --t-lg:.94rem; /* campos y nombres */
  --t-xl:1rem;   /* headings */

  /* One focus ring for the whole page. Without this each control inherited the browser's, which on a
     dark theme is white and looks like nothing else around it. */
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

/* Inside `:where()` so it carries no weight: any rule further down still wins on every other
   property, and this only puts a ring where there was none. */
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
/* The two ways off this page, always both present, one of them already where you are. Links, not
   buttons: navigation, not an action, so they must survive JavaScript failing to load. */
.paginas{display:flex;gap:.5rem}
.icono{
  display:inline-flex;align-items:center;justify-content:center;
  width:2rem;height:2rem;background:transparent;border:1px solid var(--line);color:var(--mute);
  border-radius:var(--r-sm);transition:color .16s,border-color .16s;
}
.icono:hover{color:var(--phos);border-color:var(--phos-dim)}
.icono.activo{color:var(--phos);border-color:var(--phos-dim);background:rgba(94,242,160,.07)}
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
  display:flex;align-items:center;justify-content:center;
  width:1.9rem;height:1.9rem;padding:0;line-height:0;
  background:transparent;border:1px solid var(--line);color:var(--mute);
  border-radius:var(--r-sm);cursor:pointer;transition:color .16s,border-color .16s;
}
.pw button:hover{color:var(--phos);border-color:var(--phos-dim)}
/* The page-wide `button:active{transform:translateY(1px)}` (below) does not add to this button's
   own `translateY(-50%)` — a `transform` from one rule replaces the whole property, it does not
   compose with another rule's — so pressing it was discarding the centering entirely and the button
   jumped to the top of `.pw`. Folding the same 1px nudge into the centering value keeps the press
   feedback without losing it. */
.pw button:active:not(:disabled){transform:translateY(calc(-50% + 1px))}
/* The two icons swapping which one is `hidden` must never change the button's own box — it is
   `position:absolute; top:50%` inside `.pw`, so any change to its height re-centers it and reads as
   the button sliding. `display:contents` takes the `<span>` wrappers out of layout entirely, so only
   whichever single SVG is visible ever has a box, at the button's own fixed size. */
.pw button span{display:contents}
/* `[hidden]` on its own lost to the rule above — same specificity fight `.acciones[hidden]` already
   had to win once elsewhere on this page — so it needs the extra class here too. */
.pw button span[hidden]{display:none}

.gente{margin-top:1.1rem}
.persona{display:flex;align-items:center;gap:.7rem;margin-bottom:.5rem}
.persona input[type=text]{flex:1}
.nino{display:flex;align-items:center;gap:.4rem;font-size:var(--t-xs);letter-spacing:.12em;
  text-transform:uppercase;color:var(--mute);white-space:nowrap;margin:0}
.nino input{accent-color:var(--phos-dim);width:16px;height:16px}
.canalesextra{display:flex;flex-direction:column;gap:.5rem;margin-top:.5rem}
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

/* --- The household grid ------------------------------------------------------------------------
   One card per household and nothing more than fits at a glance: the name, whether it is running and
   what is on. Everything else — the account, the people, the history — lives inside and opens when
   the card is pressed. */
.casas{display:grid;gap:.75rem;grid-template-columns:repeat(auto-fill,minmax(190px,1fr))}
.tarjeta{
  background:var(--panel);border:1px solid var(--line);border-radius:var(--r-lg);
  padding:.85rem 1rem .9rem;position:relative;overflow:hidden;cursor:pointer;
  transition:border-color .16s,transform .06s;
}
.tarjeta::before{content:"";position:absolute;inset:0 auto 0 0;width:2px;background:var(--phos-dim);opacity:.55}
.tarjeta:hover{border-color:var(--phos-dim)}
.tarjeta:focus-visible{outline:0;border-color:var(--phos);box-shadow:0 0 0 3px rgba(94,242,160,.09)}
/* Capitalised rather than upper-cased: these are people's and households' names, not labels. */
.tarjeta h2{margin:0 0 .1rem;font-size:var(--t-lg);letter-spacing:.02em;color:var(--text)}
.tarjeta .que{font-size:var(--t-xs);color:#465a67;margin:0 0 .55rem;letter-spacing:.12em;text-transform:uppercase}
/* One reserved row: the status arrives after the page is drawn, and without this the whole grid
   jumps when the suppliers answer. It is one line now — the "viendo" one — because the expiry date
   moved into the dialog, so only one line's worth of space is held. */
.tarjeta .hoja{padding-top:0;min-height:1.6rem}
/* On the card the status is a loose line rather than label + value. With three cards to a row the
   two columns do not fit — "VIENDO" takes a third of the width and breaks the text over two lines —
   and the label was not needed anyway: whether it is running is what the dot says. */
.tarjeta .linea{font-size:var(--t-sm);color:var(--mute);line-height:1.35}
.tarjeta .linea.bien{color:var(--phos)}
.tarjeta .linea.mal{color:var(--alarm)}
/* The label on one line and the value on as many as it needs. The other way round — which is the
   default — "Lo último" breaks in two and the card grows a line over a two-word title. */
.tarjeta .fila{align-items:baseline}
.tarjeta .k{white-space:nowrap}

/* --- The dialog ---------------------------------------------------------------------------------
   A real <dialog>: the browser already knows how to close on Esc, trap focus inside and paint the
   backdrop. They come from the server with `open` on purpose, and it is JavaScript that closes them
   on load — so if the script never arrives, the page is left with every dialog expanded inline,
   which is ugly but still usable. A panel where a password cannot be changed is no use at all. */
/* Pinned to the top rather than centred. Centred, every tab with a different height moved the whole
   dialog — header, tabs and all — and the next tab had to be chased with the mouse, having just
   slid out from under the pointer. With the top margin fixed, whatever grows or shrinks does so
   downwards and nothing you press moves. */
dialog.ficha{
  width:min(620px,calc(100vw - 2rem));max-height:calc(100vh - 12vh);
  margin:6vh auto auto;
  padding:0;overflow:auto;border-radius:var(--r-lg);
  background:var(--panel);color:var(--text);border:1px solid var(--line);
}
dialog.ficha::backdrop{background:rgba(3,7,10,.78)}
/* On opening, focus lands on the dialog itself and the browser paints its white ring around the
   entire frame. The ring is useful, but around the control that has focus, not the box containing
   it: inside there are fields and buttons that bring their own. */
dialog.ficha:focus,dialog.ficha:focus-visible{outline:none}
dialog.ficha[open]:not(:modal){
  position:static;margin:0 0 1rem;width:auto;max-height:none;
}
.cabeza{display:flex;align-items:center;gap:.9rem;padding:1.15rem 1.15rem 0}
.cabeza h2{margin:0;flex:1;font-size:var(--t-xl);letter-spacing:.02em;color:var(--text)}
.cerrar{
  background:transparent;border:1px solid var(--line);color:var(--mute);font:inherit;
  /* 2.4rem ≈ 38px. The guidelines ask for 44 for a finger; in a 3rem header that leaves the cross
     touching the edges, so it stays at 38 with dead space around it that competes with nothing
     else pressable. */
  font-size:var(--t-md);line-height:1;width:2.4rem;height:2.4rem;border-radius:50%;cursor:pointer;
  display:flex;align-items:center;justify-content:center;
}
.cerrar:hover{color:var(--text);border-color:var(--phos-dim)}
/* Reserves the expiry line before it arrives, so the "Cuenta" sheet is measured with it in place
   and the dialog does not grow when the supplier answers. */
.cuentaestado{min-height:1.7rem;margin-top:.4rem}
dialog.ficha .pestanas{margin:1rem 1.15rem 0}
dialog.ficha .hoja{padding:1.15rem}
dialog.ficha .acciones{margin:0 1.15rem 1.15rem}
/* `.acciones.pie` sits beside the `.hoja` divs, so this margin is what lines it up with their own
   padding. A tab-specific row like "Poner canal"'s "Enviar" lives *inside* its `.hoja` on purpose —
   so it hides along with the tab — which already carries that padding; adding the same margin again
   doubled the inset and left the button narrower than the field above it. */
dialog.ficha .hoja .acciones{margin-left:0;margin-right:0;margin-bottom:0}
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
/* A tab that leads nowhere looks switched off from the bar, without opening it. The `title` says
   why, which is what the grey alone does not tell. `:hover` handled separately, or it would keep
   lighting up under the pointer and promising what it will not do. */
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
/* Red from the start rather than only on hover: it is the page's one button that cannot be undone,
   and an icon with no word beside it needs the colour to say what it is about. */
.retirar{
  background:transparent;border:1px solid var(--line);color:var(--alarm);
  display:flex;align-items:center;justify-content:center;
  /* `stretch` rather than padding set by eye: the two buttons in this row have different typography
     — one carries text and the other a drawing — so matching their heights with `padding` goes
     wrong the moment a font changes. Let the row decide. */
  align-self:stretch;padding:0 .85rem;border-radius:var(--r-sm);cursor:pointer;opacity:.72;
  transition:opacity .16s,border-color .16s,background .16s;
}
.retirar:hover{opacity:1;border-color:var(--alarm);background:rgba(255,107,107,.08)}

.acciones{
  display:flex;align-items:center;gap:.7rem;margin-top:1.2rem;
  padding-top:1.05rem;border-top:1px dashed var(--line);
}
/* `display:flex` beats the `display:none` the browser puts on anything with `hidden`, so hiding
   this row on the read-only tab did absolutely nothing. */
.acciones[hidden]{display:none}
button.save{
  flex:1;padding:.8rem;background:var(--phos);color:#04150c;border:0;border-radius:var(--r-sm);
  font:inherit;font-weight:700;font-size:var(--t-md);letter-spacing:.22em;text-transform:uppercase;cursor:pointer;
  transition:filter .16s, transform .06s;
}
button.save:hover{filter:brightness(1.12)}
/* Everything pressable sinks by a pixel. Only "Guardar" and the cards used to, so half the page
   answered to a finger and the other half looked dead. */
button:active:not(:disabled),.tarjeta:active{transform:translateY(1px)}
/* `not-allowed` rather than `default`: the button changes colour and its text to "Sin cambios", but
   it is the cursor that says so before anyone gets as far as reading it. */
button.save:disabled{
  background:transparent;color:var(--mute);border:1px solid var(--line);
  cursor:not-allowed;filter:none;transform:none;
}

/* The "saved" notice is not something to read: it is something to see and forget. It used to be a
   band at the very top that pushed the form down right after it had been touched, and on a phone it
   left the field just edited off the screen. Errors are still .msg, because those do have to be read
   and must not leave on their own. */
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
/* The red ones do not leave on their own: they have to be read. They are dismissed by pressing
   them, and for that they need the pointer events the notice stack otherwise lets straight through.
   */
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

/* Adding a household happens three times in a lifetime, so on the front page it is one line rather
   than a form. Dashed, and with the same corner radius as the cards: it reads as the gap where the
   next one would go. */
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
// Channel and film names are written by the supplier, arrive as JSON and end up in innerHTML.
// Escaping here is the only barrier anywhere along that path.
function escapar(t){
  return String(t == null ? "" : t).replace(/[&<>"]/g, function(c){
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];
  });
}

// The clock is the server's, not the device's: the clock in a thirty-euro TV box is not something
// to rest a "12 minutes ago" on.
function hace(desde){
  var mins = Math.max(0, Math.round((Date.now()/1000 - desde) / 60));
  if (mins < 60) return 'hace ' + mins + ' min';
  if (mins < 60 * 20) return 'hace ' + Math.round(mins / 60) + ' h';
  return new Date(desde * 1000).toLocaleString('es-ES',
    {day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit'});
}

// Same graduated read as the server's own `fecha_corta` — minutes, then hours, then "ayer", then a
// handful of days, then a bare date — kept in step by hand because it answers a question
// (`ultima_vez`) that can only be settled once `/estado` has come back, unlike everything else
// `fecha_corta` renders straight into the page.
function fechaCorta(epoch){
  if (!epoch) return '';
  var minutos = Math.max(0, Math.floor((Date.now()/1000 - epoch) / 60));
  if (minutos < 60) return 'hace ' + minutos + ' min';
  var horas = Math.floor(minutos / 60);
  if (horas < 24) return 'hace ' + horas + ' h';
  var dias = Math.floor(horas / 24);
  if (dias === 1) return 'ayer';
  if (dias < 7) return 'hace ' + dias + ' días';
  var f = new Date(epoch * 1000);
  return String(f.getDate()).padStart(2, '0') + '/' + String(f.getMonth() + 1).padStart(2, '0') +
    '/' + f.getFullYear();
}

// Deliberately its own wording rather than `fechaCorta` reused: "ayer" or a bare date read fine for
// something that is over, but "ayer" here reads as still connected since yesterday, which for a
// live stream nobody believes and nobody should — a connection that has survived a whole day is
// worth showing exactly as many days, not folding into the same "sounds distant" bucket as a
// household nobody has checked on in a week.
function conectadoDesde(epoch){
  if (!epoch) return '';
  var minutos = Math.max(0, Math.floor((Date.now()/1000 - epoch) / 60));
  if (minutos < 60) return 'hace ' + minutos + ' min';
  var horas = Math.floor(minutos / 60);
  if (horas < 24) return 'hace ' + horas + ' h';
  var dias = Math.floor(horas / 24);
  return 'hace ' + dias + ' día' + (dias === 1 ? '' : 's');
}

// Save only when there is something to save. It starts enabled in the HTML and this disables it:
// the other way round, a JavaScript failure would leave a page where nothing can be saved.
document.querySelectorAll('form.seccion').forEach(function(form){
  // The one in the footer, not "whichever comes first": the "Poner canal" tab has its own green
  // button, and since it comes earlier in the document that is the one this used to find — leaving
  // it disabled and labelled "Sin cambios", which is exactly what it does not do: it saves nothing,
  // it sends an errand.
  var boton = form.querySelector('.acciones.pie button.save');
  if (!boton) return;
  var titulo = boton.textContent;
  var campos = [], inicial = [];

  function valor(i){ return i.type === 'checkbox' ? i.checked : i.value; }
  function revisar(){
    boton.disabled = !campos.some(function(i, n){ return valor(i) !== inicial[n]; });
    boton.textContent = boton.disabled ? 'Sin cambios' : titulo;
  }
  // Fields are registered one at a time rather than all at once, because person rows can appear
  // later: a freshly created row starts with its current value as its baseline, so adding one and
  // typing nothing does not count as an unsaved change.
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
  var abierto = b.querySelector('.ojo-abierto');
  var cerrado = b.querySelector('.ojo-cerrado');
  b.addEventListener('click', function(){
    var i = b.parentNode.querySelector('input');
    var shown = i.type === 'text';
    i.type = shown ? 'password' : 'text';
    abierto.hidden = !shown;
    cerrado.hidden = shown;
    b.title = shown ? 'Ver' : 'Ocultar';
    b.setAttribute('aria-label', b.title);
  });
});

// "Qué ve": the history is fetched on press and not before. It is the only part of the page that
// reads what somebody has been watching, and there is no reason for it to be on show to anyone who
// opens the panel to change a password.
// A red notice stays until it is read. Pressing it dismisses it.
document.querySelectorAll('.toast.mal').forEach(function(t){
  t.addEventListener('click', function(){ t.remove(); });
  t.title = 'Pulsa para descartar';
});

// The dialogs. They come from the server open so that the page stays usable without JavaScript;
// the first thing this does is close them, and from then on the card is what opens them.
(function(){
  var fichas = document.querySelectorAll('dialog.ficha');
  if (!fichas.length) return;

  // Measure before closing. The dialogs come from the server open and inline, so this is the only
  // moment when all the sheets are in place and can be measured: giving them all the height of the
  // tallest means switching tabs no longer moves the dialog's footer. The "Qué ve" one is still
  // empty — it is filled when opened — so the minimum comes from the two that carry fields.
  fichas.forEach(function(f){
    var hojas = f.querySelectorAll('.hoja');
    var alto = 0;
    hojas.forEach(function(h){ alto = Math.max(alto, h.offsetHeight); });
    if (alto) hojas.forEach(function(h){ h.style.minHeight = alto + 'px'; });
  });

  fichas.forEach(function(f){ f.close(); });

  // showModal where it exists: it is what traps focus inside, paints the backdrop and makes Esc
  // close without anyone writing that. A bare `open` is the fallback where it does not.
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
    // The card is an <article role=button>, so the keyboard has to be handled by hand: a real
    // button cannot wrap the status block without breaking the HTML.
    t.addEventListener('keydown', function(e){
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); abrir(id); }
    });
  });

  fichas.forEach(function(f){
    var cerrar = f.querySelector('[data-cerrar]');
    if (cerrar) cerrar.addEventListener('click', function(){ f.close(); });
    // Pressing the backdrop. The <dialog> receives the backdrop's click as its own, so what tells
    // "outside" from "inside" is whether the pressed point falls outside its rectangle.
    f.addEventListener('click', function(e){
      if (e.target !== f) return;
      var c = f.getBoundingClientRect();
      var dentro = e.clientX >= c.left && e.clientX <= c.right &&
                   e.clientY >= c.top  && e.clientY <= c.bottom;
      if (!dentro) f.close();
    });
  });

  // The add button opens its own dialog, which is one more of these.
  var boton = document.querySelector('.nueva');
  var alta = document.getElementById('alta');
  if (boton && alta) boton.addEventListener('click', function(){ abrirFicha(alta); });

  // And if adding failed, the server marks it to reopen with the error inside: with the backdrop
  // up, a notice in the band at the top of the page is a notice nobody sees.
  var marcada = document.querySelector('dialog.ficha[data-abrir]');
  if (marcada) abrirFicha(marcada);

  // On returning from a save. The server redirects with #casa-<id> to leave you where you were.
  if (location.hash.indexOf('#casa-') === 0) abrir(location.hash.slice(6));
})();

// A dialog's tabs. Each button says which sheet it shows and the sheet is `<sheet>-<household>`, so
// adding a tab is adding a button and a div, without touching this.
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

  // "Qué ve" has not one field: it is a list to be read. Leaving the save button there offered an
  // action that does not go with what is being looked at.
  var ficha = barra.closest('dialog');
  var acciones = ficha ? ficha.querySelector('.acciones.pie') : null;

  function ensenar(cual){
    Object.keys(hojas).forEach(function(nombre){
      if (hojas[nombre]) hojas[nombre].hidden = nombre !== cual;
    });
    // By the sheet's attribute rather than by its name: that way a new tab that saves nothing —
    // "Poner canal" is the second — only has to say so in its own div, which is where it is known.
    var hoja = hojas[cual];
    if (acciones) acciones.hidden = !!(hoja && hoja.hasAttribute('data-sin-guardar'));
  }

  // The starting state is set here and not by the server, for the same reason the dialogs come out
  // open: without JavaScript the sheets are seen one below another, which is ugly and usable. With
  // it, the ones not in play are hidden here before anybody has time to see them.

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
      // Only when looked at. It is the only part of the panel that shows what somebody has been
      // watching, and there is no reason for it to be on show to whoever opens this to change a
      // password.
      if (cual === 'uso') pedirHistorial();
    });
  });
});

// The status is fetched separately from the render: asking the suppliers takes as long as their
// servers take, and the form has no reason to wait for them to appear.
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

  // What goes on the card: one line and no more. See `.tarjeta .linea`.
  function linea(v, clase){
    return '<div class="linea ' + (clase || '') + '">' + v + '</div>';
  }


  fetch('estado', {cache: 'no-store'}).then(function(r){ return r.json(); }).then(function(todo){
    var vivas = 0, total = 0, viendo = 0;
    Object.keys(todo).forEach(function(id){
      var d = todo[id];
      total++;

      // These two only exist on the casas page — a household has no card at all on /servidor — but
      // the counters above and below do not depend on either: an account being active or not is a
      // fact from the supplier, not a fact about which page happens to be open right now.
      var caja = document.getElementById('estado-' + id);
      var vence = document.getElementById('vence-' + id);

      if (d.error) {
        // Neither green nor red: it is not that nobody is watching, it is that we do not know. And
        // for the same reason the send-a-channel button is left as it was: refusing an action
        // because we could not ask would turn a supplier's failure into a limitation of ours.
        pinta(id, 'gris');
        if (caja) caja.innerHTML = linea(escapar(d.error), 'mal');
        if (vence) vence.innerHTML = '';
        return;
      }

      var vivo = d.auth && d.estado === 'Active';
      if (vivo) vivas++;
      var enUso = d.activas > 0;
      if (enUso) viendo++;

      // Whether somebody is watching fits in a dot, and that is where it goes: it is what gets
      // glanced at from across the table, and it used to take a whole row of text.
      pinta(id, enUso ? 'viendo' : 'parada');

      // The heading's own line, resolved for real now that the supplier has answered: while
      // connected it says how long, which local files alone could never say; once it is not, it
      // falls back to the last-seen date the heading was rendered with initially.
      var usadaEl = document.getElementById('usada-' + id);
      if (usadaEl) {
        usadaEl.textContent = enUso
          ? conectadoDesde(d.conectado_desde)
          : fechaCorta(d.ultima_vez);
      }

      // Nothing left to paint here on /servidor, where no household has a card — but everything
      // above (the aggregate counters, the dot, the heading line) has already happened by now.
      if (!caja) return;

      var html = '';

      // Videoclub clears `visto` itself the moment it goes to the background (see
      // `record_stopped`), so this is a fallback for the one case that misses — a report that
      // never arrived, a box that lost power mid-film — not the main defence any more: after a few
      // hours an entry that is still there only serves as "the last thing that was watched".
      var fresco = d.visto && (Date.now()/1000 - d.visto.desde) < 12 * 3600;

      // The "what" only while it is happening, and only the what: the dot already says whether it
      // is running, and "hace 20 min" turned out to tell nobody anything they needed — the account
      // being active plus the current content was the whole question. Offline, this says nothing at
      // all: "cuándo" already lives in the card's own heading (see `fecha_corta` / `last_used`), and
      // what was last watched is not asked for once nobody is watching it.
      if (enUso && fresco) {
        html += linea(escapar(d.visto.canal), 'bien');
      } else if (enUso) {
        // The supplier says the account is in use and our own app has nothing recent to say about
        // it: somebody is watching, just not through here. Worth a line of its own rather than
        // silence, which used to read as "nothing is happening" — the opposite of what is true.
        html += linea('Cliente desconocido', 'bien');
      }

      // A rejected account does stay on the card: it is an alarm, not a datum, and the dot does not
      // tell it apart from an idle household — both are red.
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


def shell(activo):
    """
    Everything before the content, which is identical in both views.

    The status bar lives here rather than only on the front page deliberately: what it checks is
    whether the suppliers answer, which is exactly what the server view is about.

    `activo` is "casas", "servidor" or "apks": every destination is always shown, next to the title,
    and the current one carries `aria-current` and its own style — not a single "go to the other
    page" link, which only ever pointed one way. The `<h1>` no longer needs to say which one that is
    — the icons already do — and keeping it fixed is what stops the header reflowing by a few pixels
    on every navigation, which "Videoclub" ⇄ "Servidor" used to do.
    """
    nav = (
        "<nav class=paginas>"
        f"<a class='icono{' activo' if activo == 'casas' else ''}' href='./' "
        f"{'aria-current=page ' if activo == 'casas' else ''}title=Videoclub>{CASA}</a>"
        f"<a class='icono{' activo' if activo == 'servidor' else ''}' href='servidor' "
        f"{'aria-current=page ' if activo == 'servidor' else ''}title=Servidor>{SERVIDOR}</a>"
        f"<a class='icono{' activo' if activo == 'apks' else ''}' href='apks' "
        f"{'aria-current=page ' if activo == 'apks' else ''}title=Descargas>{DESCARGAS}</a>"
        "</nav>"
    )
    titulo_pestana = {"servidor": "Servidor", "apks": "Descargas"}.get(activo, "Videoclub")
    return [
        "<!doctype html><html lang=es><head><meta charset=utf-8>",
        "<meta name=viewport content='width=device-width,initial-scale=1'>",
        "<meta name=color-scheme content=dark>",
        f"<title>{esc(titulo_pestana)} · Control</title>",
        f"<style>{STYLE}</style></head><body><div class=wrap>",
        "<header><h1>Videoclub</h1>",
        nav,
        "<span class=tag>panel de control</span>",
        "<span class=live><span class='dot gris' id=punto></span>",
        "<span id=rotulo>consultando</span></span></header>",
    ]


def alerts(errors=(), note=None, avisos=()):
    """
    Everything the page has to say, as floating notices.

    None of these messages is part of the page: they are the answer to something just pressed. As
    bands above the content they pushed down the very thing that had just been touched, and on a
    phone they left the field just edited off the screen.

    The green ones leave on their own. The red ones do not: they have to be read, and they are
    dismissed by pressing them.
    """
    fuera = []
    if note:
        fuera.append((note, ""))
    fuera += [(esc(e), "mal") for e in errors]
    # Different from an error: this was saved. What could not be done was hand it out.
    fuera += [(esc(a), "mal") for a in avisos]
    return fuera


def tail(guardado=None, toasts=()):
    """
    The closing markup. Called with `<div class=wrap>` already closed, because the notices float
    above it.

    `guardado` is not text from the form — it is "servidor" or the name of a household that exists —
    but it is escaped all the same.
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


def list_apks():
    """
    The newest `.apk` sitting in each household's own secret directory, one row per household.

    Reads the same directory `publish.sh` already uploads to — deliberately no separate store to
    keep of its own: a build left there for any casa (published as the official update or not) shows
    up here for nothing, and a household renamed or deleted needs no second migration on top of the
    one `apply_house`/id changes already require. `casas.json` is the one list of truth.

    Only the latest: older builds pile up in that same directory — `publish.sh` never deletes one —
    but they are not what this view is for. Anyone reaching for an APK here wants what runs today,
    not an archive of everything that ever ran.
    """
    filas = []
    for casa in houses():
        directorio = os.path.dirname(casa["provider"])
        try:
            nombres = [n for n in os.listdir(directorio) if n.endswith(".apk")]
        except OSError:
            continue
        mejor = None
        for nombre in nombres:
            try:
                st = os.stat(os.path.join(directorio, nombre))
            except OSError:
                continue
            if mejor is None or st.st_mtime > mejor["cuando"]:
                mejor = {"archivo": nombre, "tamano": st.st_size, "cuando": int(st.st_mtime)}
        if mejor is None:
            continue
        filas.append({"id": casa["id"], "nombre": casa["nombre"], **mejor})
    filas.sort(key=lambda f: f["cuando"], reverse=True)
    return filas


def tamano_legible(bytes_):
    valor = float(bytes_)
    for unidad in ("B", "KB", "MB", "GB"):
        if valor < 1024 or unidad == "GB":
            return f"{valor:.0f} {unidad}" if unidad == "B" else f"{valor:.1f} {unidad}"
        valor /= 1024
    return f"{valor:.1f} GB"


def render_apks(guardado=None):
    """
    The latest APK for each casa, one place to fetch them from — a browser tab open on a phone in
    the room where the box lives, rather than a laptop's `adb` reaching across the house's wifi.
    See [list_apks] for where these come from and why only the newest; nothing here writes anything.
    """
    out = shell("apks")
    filas = list_apks()

    out.append("<fieldset><legend>Descargas</legend>")
    if not filas:
        out.append(
            "<p class=hint>Nada publicado todavía. <code>./publish.sh --casa &lt;id&gt;</code> "
            "deja aquí lo que sube.</p>"
        )
    else:
        out.append("<div class=visto><div class=grupo>Última versión de cada casa</div>")
        for fila in filas:
            enlace = (
                "apks/descargar?casa=" + urllib.parse.quote(fila["id"], safe="") +
                "&archivo=" + urllib.parse.quote(fila["archivo"], safe="")
            )
            out.append(
                "<div class=fila>"
                f"<a class=que href='{enlace}'>{esc(fila['nombre'])} · {esc(fila['archivo'])}</a>"
                f"<span class=cuando>{tamano_legible(fila['tamano'])} · "
                f"{when(fila['cuando'])}</span>"
                "</div>"
            )
        out.append("</div>")
    out.append("</fieldset>")

    out.append("</div>")
    out += tail(guardado)
    return "".join(out)


def render_server(guardado=None, errors=(), avisos=()):
    """
    The server view: the address and the User-Agent, and nothing else.

    On its own page rather than the front one because they move at different rhythms. Households are
    touched often — somebody changes a password, a new nephew arrives — and this is touched the day
    the supplier moves its server, which is once a year. Having it at the top of the front page put
    what almost never changes in front of what people come here to do.
    """
    shared = server()

    out = shell("servidor")
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

    out = shell("casas")
    avisar = alerts(errors=errors, note=note, avisos=avisos)

    out.append("<fieldset><legend>Casas</legend>")
    if not casas:
        out.append(
            "<div class=card><p class=hint>Ninguna todavía.</p></div>"
        )
    else:
        # The dialogs go after the grid rather than inside it: they are <dialog>s, and without
        # JavaScript they come out expanded inline — inside a grid they would be spread across the
        # columns.
        tarjetas, fichas = zip(*(render_house(casa) for casa in casas))
        out.append("<div class=casas>" + "".join(tarjetas) + "</div>")
        out.extend(fichas)
    out.append("</fieldset>")

    boton, ficha = render_alta(alta)
    # No section heading above it: the button already says what it does, and "Añadir casa" twice in
    # a row is one of the two too many.
    out.append("<fieldset>")
    out.append(boton)
    out.append("</fieldset>")
    # Outside the <fieldset> and the grid, like the households' own: it is a <dialog>, and without
    # JavaScript it comes out expanded inline.
    out.append(ficha)
    # Cierra el <div class=wrap> que abrió la cabecera.
    out.append("</div>")
    out += tail(guardado, avisar)
    return "".join(out)


def render_alta(errors=()):
    """
    Adding a household: the button you see and the dialog it opens, exactly like any other household.

    It used to take up half the front page while sitting at the bottom and being used three times in
    a lifetime. Now it is one line, and its fields live where everybody else's do — which also fixed
    the "VER" button along the way: the `.alta button` rule painted *every* button in that grid, so
    the one inside the password field came out large and green instead of the usual grey pill.

    Errors are drawn inside rather than in the band at the top: with the dialog open the backdrop
    covers the page, so a notice up there is a notice nobody sees.
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
    # No option preselected: which application runs in that household decides which fields it will
    # have and which APK has to be taken to it, and that is not something that should come out chosen
    # by alphabetical order.
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
        f"{BOTON_VER}</div></div>"
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
    One household, in two pieces: the card you see and the dialog that opens when you press it.

    They are split because they are read at different moments. What people come to look at — who is
    watching something right now — fits in three lines and has to be visible for all four households
    at once; what people come to change — a password, a new nephew — belongs to one household alone
    and should not require scrolling past the other three to reach.

    The dialog is returned whole and already written rather than fetched afterwards: they are the
    same fields as always, they are already on the server drawing the page, and one more request
    would only add a wait and a new way for this to fail.
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
    # The application's name used to sit under the household's. It is redundant now that there is
    # only one: repeating "videoclub" on every card tells none of them apart. That slot now carries
    # the last time anything was heard from the household — filled in twice. First from here, from
    # local files only, so there is something to read before the supplier has answered anything.
    # Then again from `/estado`, once it is known whether the connection is open right now: while it
    # is, "última vez" is the wrong question — [connection_started] answers the one that matters
    # then, which local files alone cannot.
    cuando = last_used(casa["id"])
    usada = f"<p class=que id='usada-{ident}'>{esc(fecha_corta(cuando)) if cuando else ''}</p>"

    # The dot starts grey: whether anything is running is answered by the supplier, and that arrives
    # after the page is drawn. Grey means "I do not know yet", which is true on opening.
    tarjeta = (
        f"<article class=tarjeta tabindex=0 role=button data-casa='{ident}' "
        f"aria-haspopup=dialog aria-label='{esc(casa['nombre'])}'>"
        f"<h2><span class='bolita gris' id='bolita-{ident}'></span>{esc(casa['nombre'])}{aviso}</h2>"
        f"{usada}"
        f"<div class=hoja id='estado-{ident}'></div>"
        "</article>"
    )

    # ----------------------------------------------------------------------------------- la ficha
    # The two tabs that depend on whether the household runs in simple mode. It is known here, from
    # reading its document, without waiting for anybody.
    #
    # **Sending a channel is for simple households only.** In a household with the video shop,
    # whoever is sitting there has a remote and menus to change channel themselves, and may well be
    # watching a film — which an order to tune would land on top of for no reason. The simple box is
    # the opposite: a television that only does channels and has to be reached from outside.
    #
    # **And people are for the ones that are not simple.** Simple mode has no profile picker and no
    # "Continue watching": editing a list nobody is going to look at there is offering a setting that
    # settles nothing.
    es_simple = bool(doc.get("simple"))

    canales_de_la_casa = read_lineup(casa["id"]) if es_simple else []

    # Two conditions for being able to send it a channel, and both are known here: that the
    # household has said which channels it has, and that its app is awake right now. See
    # [apps_awake].
    despiertas = apps_awake() if es_simple else {}
    ruta_doc = urllib.parse.urlsplit(casa.get("url") or "").path
    if despiertas is None:
        # The log will not be read. That is a problem of ours, not an answer about this household:
        # it is let through, as with the grey dot when the supplier does not answer.
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

    # SimpleTV never gets this far — see the note by [render_house]'s "Versión" block — so it is
    # exactly `casa["app"] == "videoclub"` throughout, same as the tab's content below.
    pestana_canales = (
        "<button type=button class=pes data-hoja='canales'>Canales extra</button>"
        if casa["app"] == "videoclub" else ""
    )

    out = [
        f"<dialog class=ficha id='casa-{ident}' open>",
        # El formulario de borrar va primero y aparte, nunca dentro del de guardar: HTML no admite
        # formularios anidados — el navegador se come el interior y el botón acaba enviando el
        # otro, que es como «Retirar» acabó guardando.
        f"<form method=post action='borrar' id='borrar-{ident}'>"
        f"<input type=hidden name=casa value='{ident}'></form>",
        # Like the delete one, and for the same reason: HTML does not allow nested forms, so it
        # lives out here and the controls inside are tied to it with `form=`.
        f"<form method=post action='poner' id='mandar-{ident}'>"
        f"<input type=hidden name=casa value='{ident}'></form>",
        f"<div class=cabeza><h2>{esc(casa['nombre'])}{aviso}</h2>"
        f"<button type=button class=cerrar data-cerrar aria-label=Cerrar title='Cerrar (Esc)'>✕</button></div>",
        f"<div class=pestanas data-casa='{ident}'>"
        "<button type=button class='pes activa' data-hoja='cuenta'>Cuenta</button>"
        f"{pestana_perfiles}"
        f"{pestana_canales}"
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
        f"{BOTON_VER}</div></div>"
    )
    if casa["app"] == "simpletv":
        out.append(
            f"<div class=full><label for='{prefix}name'>Nombre</label>"
            f"<input type=text id='{prefix}name' name='{prefix}name' value='{esc(doc.get('name'))}' "
            "placeholder='Papá'></div>"
        )
    else:
        # Live television only, like SimpleTV: no video shop, no tabs, no profile picker. Absent
        # from the document means the full video shop, so unticking it deletes the key rather than
        # writing `false` — consistent with how every other field is read.
        checked = " checked" if doc.get("simple") else ""
        out.append(
            f"<div class=full><label class=nino>"
            f"<input type=checkbox name='{prefix}simple'{checked}> Modo Simple</label></div>"
        )
    out.append("</div>")
    # What the supplier answers about the account. Filled in by itself after the page is drawn, and
    # here rather than on the card: the expiry date is an account detail, looked at on the day it is
    # renewed and not every time the panel is opened to see who is watching something.
    out.append(f"<div class=cuentaestado id='vence-{ident}'></div>")
    out.append(f"<p class=url>Último cambio: {esc(when(mtime))}</p>")

    # SimpleTV is being retired and never got this far; it would need a build of its own to make any
    # of this true.
    if casa["app"] == "videoclub":
        running = read_running_version(casa["id"])
        apk = doc.get("apk") or {}
        publicada = apk.get("version")

        if running:
            out.append(f"<p class=url>Versión: {running['version']}</p>")
        else:
            out.append("<p class=url>Versión: sin reportar todavía</p>")

        # Purely informational: there is no button here any more, since the device itself is what
        # decides when to catch up — the icon beside `TV`, or holding OK over the channel list in
        # simple mode.
        if publicada and running and running["version"] != publicada:
            out.append(
                f"<p class=hint>Versión publicada: {publicada} · la casa todavía no se ha puesto "
                "al día.</p>"
            )
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
        # One empty slot to start with, and a button that creates as many more as are needed. With
        # only one, a save was required between one person and the next, which is exactly what looked
        # like "it will not let me add any more". The container exists so the button knows where to
        # put them.
        out.append("<div class=nuevas><div class=persona>")
        out.append(f"<input type=text name='{prefix}perfil.nuevo.0' placeholder='añadir persona…'>")
        out.append(
            f"<label class=nino><input type=checkbox name='{prefix}perfil.nuevo.0.infantil'>infantil</label>"
        )
        out.append("</div></div>")
        out.append(f"<button type=button class=mas data-prefijo='{prefix}'>+ otra persona</button>")
        # The one thing left written down: deleting somebody takes their history and cannot be
        # undone.
        out.append("<p class=hint>Sin nombre = fuera, con su historial.</p></div>")
    out.append("</div>")

    # --- pestaña: canales extra
    #
    # A checkbox per entry in the hardcoded catalogue — see [CANALES_EXTRA]. Submits together with
    # "Guardar": there is no separate save button here, same as Perfiles just above.
    if casa["app"] == "videoclub":
        catalogo = extra_channels()
        urls_casa = {c.get("url") for c in (doc.get("canales") or []) if c.get("url")}
        out.append(f"<div class=hoja id='canales-{ident}'>")
        out.append("<div class=gente><label>Canales de esta casa, además de los del proveedor</label>")
        out.append("<div class=canalesextra>")
        for entry in catalogo:
            checked = " checked" if entry.get("url") in urls_casa else ""
            out.append(
                f"<label class=nino><input type=checkbox name='{prefix}canal_extra' "
                f"value='{esc(entry['id'])}'{checked}> {esc(entry['nombre'])}</label>"
            )
        out.append("</div></div>")
        out.append("</div>")

    # --- pestaña: qué ve
    # Inside the form even though it has no fields, so that "Guardar" stays at the foot of the
    # dialog whichever tab is being looked at. Filled when opened and not before: it is the only part
    # of the panel that shows what somebody has been watching.
    # `data-sin-guardar` on the sheets that save nothing: it is what tells the script to hide the
    # "Guardar" button when they are opened. That behaviour used to be written against the "Qué ve"
    # tab by name, and adding "Poner canal" would have left it short.
    out.append(f"<div class='hoja visto' id='uso-{ident}' data-sin-guardar></div>")

    # --- tab: send a channel (simple households only)
    #
    # Written only if a tab was created for it above. The script hides the sheets it knows about
    # through their buttons, so a sheet with no button would never be hidden: it would sit there in
    # plain sight, below the active tab, in every household that is not simple.
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
            # This is not reachable with the tab enabled — with no list it starts disabled — but
            # the sheet is written anyway: if it ever gets enabled by some other route, better that
            # it explains itself than that it is blank.
            out.append(
                "<p class=hint>Esta casa todavía no ha dicho qué canales tiene. Los manda la app al "
                "abrir la televisión, así que hace falta que su aparato tenga una versión reciente y "
                "haya entrado ahí al menos una vez.</p>"
            )
        out.append("</div>")

    # `pie` distinguishes it from the action row the "Poner canal" tab now has as well: the script
    # looks this one up in order to hide it, and `querySelector('.acciones')` would have handed it
    # the other, which comes first in the document.
    out.append("<div class='acciones pie'>")
    out.append("<button class=save type=submit>Guardar</button>")
    # Tied by `form=` to the delete form written above, outside this one.
    # An icon rather than a word, but with `title` and `aria-label`: the drawing says what it is
    # about and the text says exactly what it does, which on the page's only irreversible button
    # matters.
    #
    # The confirmation lives in an `onclick` rather than being attached by the script, so that it
    # keeps asking even if the script never arrives: without it, the one thing on this page that
    # cannot be undone would take a single press. And the message is built with `json.dumps`, which
    # is what makes it safe — it used to interpolate the name into single quotes bare, and a
    # household called "O'Brien" split the JavaScript string in two, leaving the button deleting
    # without asking anything.
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

    def _send_apk(self):
        """
        One file from [list_apks], streamed to whoever asked — nginx's Basic Auth in front of
        `/panel/` is the only gate, the same as every other view here.

        `archivo` never carries a `/`: the pattern below refuses anything that does, so there is no
        path to walk out of the household's own directory with. Checked against the directory
        listing too, rather than just against the pattern, so a name that merely *looks* like an APK
        cannot be used to read some other file sitting there.
        """
        pedida = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        casa_id = pedida.get("casa", [""])[0]
        archivo = pedida.get("archivo", [""])[0]
        casa = next((c for c in houses() if c["id"] == casa_id), None)
        if not casa or not re.fullmatch(r"[A-Za-z0-9_.-]+\.apk", archivo):
            return self._send("No encontrado.", status=404)
        directorio = os.path.dirname(casa["provider"])
        if archivo not in os.listdir(directorio):
            return self._send("No encontrado.", status=404)
        try:
            with open(os.path.join(directorio, archivo), "rb") as handle:
                payload = handle.read()
        except OSError:
            return self._send("No se pudo leer.", status=500)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Content-Disposition", f"attachment; filename=\"{archivo}\"")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

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
        if path.endswith("/apks/descargar"):
            return self._send_apk()
        if path.endswith("/apks"):
            try:
                return self._send(render_apks())
            except Exception as error:
                return self._send(f"<pre>No se pudo leer la lista: {esc(error)}</pre>", 500)
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
            # What arrives is either "servidor" or a household id. It is resolved against what
            # exists, so the notice can never say anything that did not come from in here.
            que = query["guardado"][0]
            if que == "servidor":
                guardado = "Servidor"
            else:
                casa = next((c for c in houses() if c["id"] == que), None)
                guardado = casa["nombre"] if casa else "Cambios"
        if "borrada" in query:
            note = "Casa borrada"
        if "puesto" in query:
            # Deliberately "sent" and not "tuned": the panel does not know whether the television
            # was on or whether it took any notice. Promising the latter would be a lie half the
            # time.
            note = "Canal mandado · la tele lo coge en un par de minutos si está encendida"
        if "nueva" in query:
            casa = next((c for c in houses() if c["id"] == query["nueva"][0]), None)
            if casa:
                # The URL is no longer written here: `./sync-casas.sh` collects it, which is how
                # it is done.
                note = f"<b>{esc(casa['nombre'])}</b> creada · ./sync-casas.sh"
        # After the notices, because this view shows its own on save too. It sits down here rather
        # than with the other routes for that very reason: it needs the `guardado` just resolved.
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
        if path.endswith("/liberar"):
            # For `publish.sh`, not for a browser: it has already scp'd the APK by the time this is
            # called, and answers JSON rather than a redirect for the same reason `/informe` does.
            # There is no separate "send" step any more — the device itself decides when to catch
            # up, so publishing writes the document directly.
            form = self._form()
            problem = publish_release(
                form.get("casa", [""])[0],
                form.get("version", [""])[0],
                form.get("sha256", [""])[0],
                form.get("filename", [""])[0],
            )
            if problem:
                return self._send_json({"error": problem}, status=400)
            return self._send_json({"ok": True})
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

            # Each section saves on its own. Errors stay on the page, because they belong to a form
            # still on screen; a successful save redirects, so that reloading afterwards reads again
            # instead of saving again.
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
                    # With the anchor, so the dialog reopens by itself: saving should not cost
                    # three presses to get back to where you were.
                    q = urllib.parse.quote(house_id)
                    return self._redirect(f"?guardado={q}#casa-{q}")
                return self._send(render(errors=errors))

            # Any other POST is refused rather than treated as a form: it would arrive with no
            # fields, read as "everything blank" and answer with a wall of validation errors about a
            # form nobody submitted.
            return self._send_json({"error": "no"}, status=404)
        except Exception as error:
            self._send(render(errors=[f"{error.__class__.__name__}"]))

    def _casa_del_token(self):
        """The household behind the token, or None. The same credential /informe uses."""
        offered = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        return house_for_token(offered)

    def _sync_pull(self):
        """What the household has watched on its other devices since this one last asked."""
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
        What this device has watched, and back the other way what it has missed.

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

        # Two different things arrive through the same door with the same credential: "I am
        # watching this", which is the old one, and "these are my channels", which is the list the
        # panel builds its send-a-channel dropdown from. They are told apart by the field rather than
        # by the path, so that neither nginx nor any installed household's document has to change.
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

        # A fourth thing that arrives through the same door: "I am no longer the one playing
        # anything here" — sent when Videoclub goes to the background. See [record_stopped].
        if doc.get("parado"):
            try:
                record_stopped(casa["id"])
            except Exception:
                return self._send_json({"error": "disco"}, status=500)
            return self._send_json({"ok": True})

        # A third thing that arrives through the same door: "this is the version I am running, and
        # whether I can update myself silently". See [Updater] on the app's side for `owner`.
        if "version" in doc:
            try:
                version = int(doc.get("version"))
                owner = bool(doc.get("owner"))
            except Exception:
                return self._send_json({"error": "cuerpo"}, status=400)
            if version <= 0:
                return self._send_json({"error": "cuerpo"}, status=400)
            try:
                write_json(version_path(casa["id"]), {
                    "version": version,
                    "owner": owner,
                    "cuando": int(time.time()),
                })
            except Exception:
                return self._send_json({"error": "disco"}, status=500)
            return self._send_json({"ok": True})

        try:
            canal = str(doc["canal"]).strip()[:120]
            # SimpleTV only had channels and does not send the field. Videoclub, when it reports,
            # says whether it is a series or a film. The field is still called `canal` so as not to
            # break what is already installed.
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
