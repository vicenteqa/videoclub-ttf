#!/usr/bin/env python3
"""
Migra la casa de Papá de SimpleTV a Videoclub en modo simple, en su sitio.

Guion de una vez, no una función del panel: cambia el campo `app` de esa casa de `simpletv` a
`videoclub` **conservando su `id`**, regenera el segmento de la URL para el nuevo `app`, escribe un
`provider.json` nuevo bajo `/srv/videoclub/<segmento>/` con `simple: true` y el mismo `reportToken`
y usuario/contraseña que ya tenía, y actualiza `casas.json`.

Por qué no simplemente borrar la casa y volver a crearla bajo Videoclub: `delete_house` llama a
`sync_forget(house_id)`, que borra de `progreso.db` el historial de «Seguir viendo» y de «Mi lista»
de esa casa. Ese camino se lleva el historial de Papá por delante sin avisar. Este guion no borra
nada — ni la casa vieja de `casas.json` (la reemplaza en el sitio, mismo `id`) ni el directorio
viejo bajo `/srv/simpletv/…` (se queda ahí a propósito: es el mismo criterio que usa el panel al
borrar una casa, para que el box siga respondiendo mientras no se confirme que el nuevo APK
arranca).

Se ejecuta en la máquina donde vive el panel (donde están `/srv/simpletv`, `/srv/videoclub` y
`/var/lib/simpletv-admin`), no en el portátil de desarrollo:

    ssh <usuario>@<tu-vps>
    cd /ruta/al/panel/server/admin
    sudo python3 migrar_papa.py              # dry-run: enseña lo que haría, no escribe nada
    sudo python3 migrar_papa.py --confirmar  # lo hace de verdad

Después de correrlo:
    1. Coger la URL nueva que imprime y ponerla en `local.properties` como
       `casa.papa.remoteConfig.url=…` (sustituyendo la que hubiera).
    2. `./sync-casas.sh` y compilar el flavour `papa` de Videoclub.
    3. Sideload en el box de Papá. Su URL ha cambiado, así que hace falta APK nuevo sí o sí.
    4. Sólo cuando esté confirmado que arranca: quitar `simpletv` de `APPS` en el panel y archivar
       el proyecto SimpleTV.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import sys


def cargar_admin():
    """El módulo del panel, con el mismo truco que usan sus propios scripts: el nombre lleva un
    guion, así que no se puede `import simpletv-admin` sin más."""
    aqui = os.path.dirname(os.path.abspath(__file__))
    ruta = os.path.join(aqui, "simpletv-admin.py")
    spec = importlib.util.spec_from_file_location("simpletv_admin", ruta)
    modulo = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modulo)
    return modulo


def migrar(m, house_id: str, confirmar: bool) -> int:
    casas = m.houses()
    casa = next((c for c in casas if c["id"] == house_id), None)
    if casa is None:
        print(f"No hay ninguna casa con id «{house_id}». Nada que hacer.", file=sys.stderr)
        return 1
    if casa["app"] != "simpletv":
        print(
            f"«{house_id}» ya es «{casa['app']}», no «simpletv». "
            "¿Ya se migró? No se toca nada.",
            file=sys.stderr,
        )
        return 1

    doc, _ = m.read_provider(casa["provider"])
    if doc is None:
        print(f"El documento de «{house_id}» en {casa['provider']} no se puede leer. Para.",
              file=sys.stderr)
        return 1
    if not doc.get("username") or not doc.get("password"):
        print(f"«{house_id}» no tiene usuario/contraseña en su documento actual. Para.",
              file=sys.stderr)
        return 1

    nuevo_segmento = m.segment_for("videoclub", house_id)
    nueva_ruta = os.path.join(m.APPS["videoclub"]["root"], nuevo_segmento, "provider.json")
    nueva_url = f"{m.PUBLIC_BASE}{m.APPS['videoclub']['web']}/{nuevo_segmento}/provider.json"

    if os.path.exists(nueva_ruta):
        print(
            f"Ya existe un documento en {nueva_ruta}. Esto no debería pasar en una migración "
            "nueva — revísalo a mano antes de seguir. Para.",
            file=sys.stderr,
        )
        return 1

    nuevo_doc = dict(doc)
    nuevo_doc.pop("name", None)  # campo de SimpleTV; Videoclub no lo lee, no aporta nada
    nuevo_doc["simple"] = True
    if not nuevo_doc.get("perfiles"):
        nuevo_doc["perfiles"] = [{"id": 0, "nombre": casa["nombre"]}]
        nuevo_doc.setdefault("siguientePerfilId", 1)

    print(f"Casa: {casa['nombre']} (id={house_id})")
    print(f"  documento viejo (se queda donde está): {casa['provider']}")
    print(f"  documento nuevo:                       {nueva_ruta}")
    print(f"  URL nueva:                             {nueva_url}")
    print(f"  simple: true, perfiles: {nuevo_doc['perfiles']}")

    if not confirmar:
        print("\nDry-run: no se ha escrito nada. Repite con --confirmar para aplicarlo.")
        return 0

    m.write_json(nueva_ruta, nuevo_doc, mode=0o644)
    os.chmod(os.path.dirname(nueva_ruta), 0o755)

    for c in casas:
        if c["id"] == house_id:
            c["app"] = "videoclub"
            c["provider"] = nueva_ruta
            c["url"] = nueva_url
    m.save_houses(casas)

    print(f"\nHecho. El directorio viejo sigue en {os.path.dirname(casa['provider'])} — "
          "no lo borres hasta confirmar que el box arranca con el APK nuevo.")
    print(f"Añade a local.properties: casa.{house_id}.remoteConfig.url={nueva_url}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--casa", default="papa", help="id de la casa a migrar (por defecto: papa)")
    parser.add_argument(
        "--confirmar", action="store_true",
        help="escribe los cambios de verdad; sin esto sólo enseña lo que haría",
    )
    args = parser.parse_args()

    m = cargar_admin()
    with m.HOUSES_LOCK:
        return migrar(m, args.casa, args.confirmar)


if __name__ == "__main__":
    raise SystemExit(main())
