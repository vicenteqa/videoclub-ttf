#!/usr/bin/env python3
"""
Corre el banco de pruebas contra los aparatos de casa.

    python3 tests/e2e/run.py [--aparato tv|tablet] [--escalon T5 T8] [--destructivo]

Deja el informe y las capturas en `tests/e2e/artefactos/<fecha>/`. Restaura al salir lo que haya
tocado en cada aparato.
"""
from __future__ import annotations

import argparse
import datetime
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cases import CASOS, Saltar  # noqa: E402
from device import Aparato  # noqa: E402

APARATOS = {
    "tv": Aparato("tv", "192.168.1.189:5555", "dpad"),
    "tablet": Aparato("tablet", "192.168.1.73:46281", "toque"),
}

VERDE, ROJO, GRIS, AMARILLO, FIN = "\033[32m", "\033[31m", "\033[90m", "\033[33m", "\033[0m"


def main() -> int:
    trozos = argparse.ArgumentParser()
    trozos.add_argument("--aparato", nargs="*", choices=sorted(APARATOS), default=sorted(APARATOS))
    trozos.add_argument("--escalon", nargs="*", default=None)
    trozos.add_argument("--destructivo", action="store_true")
    args = trozos.parse_args()

    elegidos = {n: APARATOS[n] for n in args.aparato}
    sello = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    salida = os.path.join(os.path.dirname(os.path.abspath(__file__)), "artefactos", sello)
    os.makedirs(salida, exist_ok=True)

    contexto = {"version": {}, "audio": {}, "converge": {}}
    resultados: list[tuple[str, str, str, str, float, str]] = []

    for aparato in elegidos.values():
        print(f"\n{'=' * 72}\n  {aparato.nombre}  ({aparato.serial})\n{'=' * 72}")
        if not (aparato.vivo() or aparato.reconectar()):
            print(f"{ROJO}  no contesta por ADB; se salta entero{FIN}")
            resultados.append(("T0", "T0.1", aparato.nombre, "FALLO", 0.0, "sin ADB"))
            continue
        # First of all, before touching anything else: if the previous run died halfway, this device
        # still has its animations off and no screensaver.
        aparato.rescatar()
        aparato.preparar()
        contexto["pareja"] = next(
            (o for n, o in elegidos.items() if n != aparato.nombre), aparato
        )
        try:
            correr(aparato, contexto, args, salida, resultados)
        finally:
            aparato.restaurar()

    informe(resultados, contexto, salida)
    return 1 if any(r[3] == "FALLO" for r in resultados) else 0


def correr(aparato, contexto, args, salida, resultados) -> None:
    preflight_ok = True
    for caso in CASOS:
        if aparato.nombre not in caso.aparatos:
            continue
        if args.escalon and caso.escalon not in args.escalon:
            continue
        if caso.destructivo and not args.destructivo:
            resultados.append((caso.escalon, caso.ident, aparato.nombre, "OMITIDO", 0.0,
                               "destructivo; hace falta --destructivo"))
            print(f"{GRIS}  · {caso.ident} {caso.titulo} — omitido (destructivo){FIN}")
            continue
        if caso.en_pareja and contexto["pareja"] is aparato:
            resultados.append((caso.escalon, caso.ident, aparato.nombre, "OMITIDO", 0.0,
                               "hace falta el otro aparato"))
            continue
        if not preflight_ok and caso.escalon != "T0":
            resultados.append((caso.escalon, caso.ident, aparato.nombre, "OMITIDO", 0.0,
                               "el vuelo previo falló"))
            continue

        arranque = time.time()
        try:
            caso.fn(aparato, contexto)
            estado, nota = "BIEN", ""
        except Saltar as motivo:
            estado, nota = "SALTADO", str(motivo)
        except Exception as fallo:  # noqa: BLE001 — cualquier cosa es un fallo del caso
            estado, nota = "FALLO", f"{type(fallo).__name__}: {fallo}"
            if caso.escalon == "T0":
                preflight_ok = False
            evidencia(aparato, salida, caso.ident, fallo)
        tardanza = time.time() - arranque
        resultados.append((caso.escalon, caso.ident, aparato.nombre, estado, tardanza, nota))

        color = {"BIEN": VERDE, "FALLO": ROJO, "SALTADO": AMARILLO}.get(estado, GRIS)
        print(f"{color}  {estado:<8}{FIN} {caso.ident:<6} {caso.titulo}"
              f" {GRIS}({tardanza:.0f}s){FIN}" + (f"\n           {GRIS}{nota}{FIN}" if nota else ""))


def evidencia(aparato, salida, ident, fallo) -> None:
    """Una captura y el árbol de la ventana, para poder mirar un fallo sin repetirlo."""
    base = os.path.join(salida, f"{ident}-{aparato.nombre}")
    try:
        aparato.captura(base + ".png")
        with open(base + ".xml", "w", encoding="utf-8") as destino:
            destino.write(aparato.volcado())
        with open(base + ".log", "w", encoding="utf-8") as destino:
            destino.write("".join(traceback.format_exception(fallo))[-4000:])
            destino.write("\n\n--- logcat ---\n")
            destino.write(aparato.registro()[-40000:])
    except Exception:
        pass


def informe(resultados, contexto, salida) -> None:
    print(f"\n{'=' * 72}")
    cuenta = {}
    for _, _, _, estado, _, _ in resultados:
        cuenta[estado] = cuenta.get(estado, 0) + 1
    resumen = "  ".join(f"{k}: {v}" for k, v in sorted(cuenta.items()))
    print(f"  {resumen}")
    print(f"  artefactos en {salida}")

    lineas = ["# Banco de pruebas — resultados", "",
              f"Fecha: {datetime.datetime.now():%Y-%m-%d %H:%M}", ""]
    for nombre, version in contexto.get("version", {}).items():
        lineas.append(f"- `{nombre}`: {version}")
    lineas += ["", f"**{resumen}**", "",
               "| Escalón | Caso | Aparato | Estado | Tiempo | Nota |",
               "|---|---|---|---|---|---|"]
    for escalon, ident, aparato, estado, tardanza, nota in resultados:
        lineas.append(f"| {escalon} | {ident} | {aparato} | {estado} | {tardanza:.0f}s | {nota} |")
    with open(os.path.join(salida, "informe.md"), "w", encoding="utf-8") as destino:
        destino.write("\n".join(lineas) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
