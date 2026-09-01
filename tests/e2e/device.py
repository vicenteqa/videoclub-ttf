"""
Un aparato de casa, visto desde el portátil que lo prueba.

Todo pasa por `adb`. Nada de esto sabe qué es Videoclub: son pulsaciones, volcados y registros.
Lo que significan está en `cases.py`.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass

PAQUETE = "com.videoclub.app"
ACTIVIDAD = f"{PAQUETE}/.MainActivity"

# Las tres escalas se ponen a 0 mientras dura la sesión: sin eso `uiautomator dump` se queda
# esperando a que la ventana quede quieta y una pared de carteles nunca lo está del todo.
ESCALAS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")


@dataclass(frozen=True)
class Nodo:
    texto: str
    desc: str
    clase: str
    clicable: bool
    caja: tuple[int, int, int, int]

    @property
    def centro(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.caja
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def etiqueta(self) -> str:
        return self.texto or self.desc


class Aparato:
    def __init__(self, nombre: str, serial: str, entrada: str):
        self.nombre = nombre
        self.serial = serial
        # "dpad" o "toque". Sólo cambia cómo navegan las pruebas que no están probando la entrada.
        self.entrada = entrada
        self._escalas_previas: dict[str, str] = {}
        self._ajustes_previos: dict[str, str] = {}

    # ------------------------------------------------------------------ adb

    def adb(self, *args: str, timeout: int = 120, check: bool = False) -> str:
        # `errors="replace"` y no decodificación estricta: el registro del box trae bytes que no son
        # UTF-8 —los mete algún servicio de Amlogic— y una tanda entera no puede caerse porque una
        # línea que a nadie le importa esté mal codificada.
        proc = subprocess.run(
            ["adb", "-s", self.serial, *args],
            capture_output=True, text=True, errors="replace", timeout=timeout,
        )
        if check and proc.returncode != 0:
            raise RuntimeError(f"{self.nombre}: adb {' '.join(args)} → {proc.stderr.strip()}")
        return proc.stdout

    def sh(self, orden: str, timeout: int = 120) -> str:
        return self.adb("shell", orden, timeout=timeout)

    def vivo(self) -> bool:
        return "device" in self.adb("get-state").strip()

    def reconectar(self) -> bool:
        """El box es DHCP y se duerme; una reconexión cuesta menos que abandonar la tanda."""
        subprocess.run(["adb", "connect", self.serial], capture_output=True, text=True, timeout=40)
        time.sleep(1.5)
        return self.vivo()

    # ------------------------------------------------------------------ sesión

    @property
    def _rescate(self) -> str:
        """Dónde queda apuntado lo que hay que devolver si la tanda se muere a mitad."""
        return os.path.join(os.path.dirname(os.path.abspath(__file__)), f".pendiente-{self.nombre}")

    def preparar(self) -> None:
        for clave in ESCALAS:
            self._escalas_previas[clave] = self.sh(f"settings get global {clave}").strip()
            self.sh(f"settings put global {clave} 0")
        # El salvapantallas del box se pone delante de la app a los pocos minutos, y una tanda dura
        # más que eso: sin esto, la mitad de los casos afirman sobre el tiempo y la previsión del
        # tiempo en vez de sobre el videoclub. Se apaga aquí y se devuelve al salir.
        for clave, valor in (("screensaver_enabled", "0"), ("screensaver_activate_on_sleep", "0")):
            self._ajustes_previos[clave] = self.sh(f"settings get secure {clave}").strip()
            self.sh(f"settings put secure {clave} {valor}")
        self._ajustes_previos["screen_off_timeout"] = self.sh(
            "settings get system screen_off_timeout"
        ).strip()
        self.sh("settings put system screen_off_timeout 1800000")
        # Apuntado en disco antes de tocar nada, no sólo en memoria: si la tanda se interrumpe —una
        # señal, un Ctrl-C, un portátil que se suspende— el proceso muere sin llegar a `restaurar` y
        # el aparato se queda con las animaciones apagadas y sin salvapantallas. Con esto, la
        # siguiente tanda lo devuelve al arrancar aunque nadie se acuerde.
        with open(self._rescate, "w", encoding="utf-8") as apunte:
            json.dump({"escalas": self._escalas_previas, "ajustes": self._ajustes_previos}, apunte)
        self.despertar()

    def rescatar(self) -> None:
        """Devuelve lo que dejó a medias una tanda anterior que no llegó a terminar."""
        if not os.path.exists(self._rescate):
            return
        try:
            with open(self._rescate, encoding="utf-8") as apunte:
                guardado = json.load(apunte)
        except (OSError, ValueError):
            os.remove(self._rescate)
            return
        self._escalas_previas = guardado.get("escalas", {})
        self._ajustes_previos = guardado.get("ajustes", {})
        self.restaurar()

    def restaurar(self) -> None:
        for clave, valor in self._escalas_previas.items():
            # `null` es lo que contesta un ajuste que nunca se había escrito: se quita en vez de
            # escribir la cadena "null", que es lo que dejaría el aparato peor que como estaba.
            if valor in ("", "null"):
                self.sh(f"settings delete global {clave}")
            else:
                self.sh(f"settings put global {clave} {valor}")
        for clave, valor in self._ajustes_previos.items():
            espacio = "system" if clave == "screen_off_timeout" else "secure"
            if valor in ("", "null"):
                self.sh(f"settings delete {espacio} {clave}")
            else:
                self.sh(f"settings put {espacio} {clave} {valor}")
        if os.path.exists(self._rescate):
            os.remove(self._rescate)

    def despertar(self) -> None:
        """
        Despierto de verdad, no sólo encendido.

        `Dreaming` es el salvapantallas: la pantalla está encendida, el aparato responde a ADB y la
        app sigue viva detrás, así que comprobar sólo `Asleep` deja pasar el caso en el que lo que
        se va a volcar es el reloj de Xiaomi.
        """
        for _ in range(4):
            estado = self.sh("dumpsys power | grep -m1 mWakefulness=")
            if "Awake" in estado:
                return
            self.tecla("WAKEUP")
            time.sleep(1.0)
            self.tecla("DPAD_DOWN" if self.entrada == "dpad" else "WAKEUP")
            time.sleep(1.5)

    def version(self) -> str:
        salida = self.sh(f"dumpsys package {PAQUETE} | grep -m1 versionName")
        return salida.strip() or "(sin instalar)"

    def instalado(self) -> bool:
        return PAQUETE in self.sh(f"pm list packages {PAQUETE}")

    def pid(self) -> int | None:
        salida = self.sh(f"pidof {PAQUETE}").strip()
        return int(salida.split()[0]) if salida else None

    def arrancar(self, limpio: bool = True) -> None:
        self.despertar()
        if limpio:
            self.sh(f"am force-stop {PAQUETE}")
        self.sh(f"am start -n {ACTIVIDAD}")

    def parar(self) -> None:
        self.sh(f"am force-stop {PAQUETE}")

    def en_primer_plano(self) -> bool:
        return PAQUETE in self.sh("dumpsys window | grep -m1 mCurrentFocus")

    # ------------------------------------------------------------------ entrada

    def tecla(self, nombre: str) -> None:
        self.sh(f"input keyevent KEYCODE_{nombre}")

    def teclas(self, *nombres: str, pausa: float = 0.35) -> None:
        for nombre in nombres:
            self.tecla(nombre)
            time.sleep(pausa)

    def tocar(self, x: int, y: int) -> None:
        self.sh(f"input tap {x} {y}")

    def escribir(self, texto: str) -> None:
        # `input text` no admite espacios ni comillas: van escapados o por `%s`.
        seguro = texto.replace("%", "%%").replace(" ", "%s").replace("'", "")
        self.sh(f"input text '{seguro}'")

    # ------------------------------------------------------------------ pantalla

    def volcado(self, intentos: int = 4) -> str:
        """El árbol de la ventana. Reintenta: la primera vez casi siempre pilla algo animándose."""
        for intento in range(intentos):
            self.sh("uiautomator dump /sdcard/e2e.xml")
            xml = self.adb("exec-out", "cat", "/sdcard/e2e.xml")
            if xml.lstrip().startswith("<?xml"):
                return xml
            time.sleep(1 + intento)
        return ""

    def nodos(self, xml: str | None = None) -> list[Nodo]:
        xml = self.volcado() if xml is None else xml
        if not xml:
            return []
        try:
            raiz = ET.fromstring(xml)
        except ET.ParseError:
            return []
        salida: list[Nodo] = []
        for elemento in raiz.iter("node"):
            caja = re.findall(r"-?\d+", elemento.get("bounds", ""))
            if len(caja) != 4:
                continue
            salida.append(
                Nodo(
                    texto=elemento.get("text", ""),
                    desc=elemento.get("content-desc", ""),
                    clase=elemento.get("class", ""),
                    clicable=elemento.get("clickable") == "true",
                    caja=tuple(int(n) for n in caja),  # type: ignore[arg-type]
                )
            )
        return salida

    def textos(self, xml: str | None = None) -> list[str]:
        return [n.etiqueta for n in self.nodos(xml) if n.etiqueta]

    def buscar(self, aguja: str, xml: str | None = None) -> Nodo | None:
        aguja = aguja.casefold()
        for nodo in self.nodos(xml):
            if aguja in nodo.etiqueta.casefold():
                return nodo
        return None

    def esperar(self, aguja: str, segundos: float = 20, intervalo: float = 1.5) -> Nodo | None:
        limite = time.time() + segundos
        while time.time() < limite:
            nodo = self.buscar(aguja)
            if nodo:
                return nodo
            time.sleep(intervalo)
        return None

    def pulsar(self, aguja: str, segundos: float = 20) -> bool:
        """Toca el nodo que lleve ese texto. Sirve igual en la tele: acepta toque además de D-pad."""
        nodo = self.esperar(aguja, segundos)
        if not nodo:
            return False
        self.tocar(*nodo.centro)
        return True

    def captura(self, destino: str) -> None:
        with open(destino, "wb") as salida:
            salida.write(
                subprocess.run(
                    ["adb", "-s", self.serial, "exec-out", "screencap", "-p"],
                    capture_output=True, timeout=90,
                ).stdout
            )

    # ------------------------------------------------------------------ registro

    def limpiar_registro(self) -> None:
        self.adb("logcat", "-c")

    def registro(self) -> str:
        return self.adb("logcat", "-d", timeout=120)

    def desastres(self, registro: str | None = None) -> list[str]:
        """Lo que nunca es aceptable, mire lo que mire la prueba que esté corriendo."""
        texto = self.registro() if registro is None else registro
        malas = []
        for linea in texto.splitlines():
            if "FATAL EXCEPTION" in linea:
                malas.append(linea.strip())
            elif "ANR in" in linea and PAQUETE in linea:
                malas.append(linea.strip())
            elif "Force finishing activity" in linea and PAQUETE in linea:
                malas.append(linea.strip())
        return malas
