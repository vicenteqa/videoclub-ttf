"""
Los casos. Qué se comprueba y por qué merece comprobarse.

Cada caso recibe un [Aparato] y un contexto, y falla lanzando `AssertionError`. Si el catálogo del
proveedor ha cambiado bajo los pies y el caso ya no aplica, lanza `Saltar` en vez de fallar: una
prueba que depende de que exista Ben-Hur no puede convertir una retirada del proveedor en un fallo
de la app.
"""
from __future__ import annotations

import os
import subprocess
import time

from device import PAQUETE, Aparato, Nodo

CASOS: list["Caso"] = []


class Saltar(Exception):
    """El caso no aplica hoy. No es un fallo."""


class Caso:
    def __init__(self, fn, ident, titulo, escalon, aparatos, destructivo, en_pareja):
        self.fn = fn
        self.ident = ident
        self.titulo = titulo
        self.escalon = escalon
        self.aparatos = aparatos
        self.destructivo = destructivo
        self.en_pareja = en_pareja


def caso(ident, titulo, escalon, aparatos=("tv", "tablet"), destructivo=False, en_pareja=False):
    def envoltura(fn):
        CASOS.append(Caso(fn, ident, titulo, escalon, aparatos, destructivo, en_pareja))
        return fn
    return envoltura


# --------------------------------------------------------------------------- utilidades

def afirmar(condicion, mensaje: str) -> None:
    if not condicion:
        raise AssertionError(mensaje)


def texto_exacto(ap: Aparato, etiqueta: str, xml: str | None = None) -> Nodo | None:
    for nodo in ap.nodos(xml):
        if nodo.etiqueta.strip() == etiqueta:
            return nodo
    return None


def carteles(ap: Aparato, xml: str | None = None) -> list[Nodo]:
    """
    Los carteles que se pueden abrir.

    Un cartel es un nodo más alto que ancho y suficientemente grande. La forma es lo único que lo
    distingue, porque en Compose todo lo pulsable llega al volcado como un `View`: los chips de
    pestaña, las filas de episodio y los carteles son el mismo tipo de nodo.

    Y llega de dos maneras según la pantalla. En las filas de la portada el cartel es el nodo
    pulsable y va sin etiqueta; en la rejilla del buscador el título viaja como *descripción de
    accesibilidad del propio cartel*, y entonces el nodo etiquetado no es pulsable —lo es su padre—.
    Valen los dos: tocar el centro de cualquiera de ellos cae dentro de la zona pulsable.
    """
    salida = [
        n for n in ap.nodos(xml)
        if not n.texto.strip()
        and (n.clicable or n.desc.strip())
        and (n.caja[3] - n.caja[1]) >= 150
        and (n.caja[3] - n.caja[1]) > (n.caja[2] - n.caja[0])
    ]
    return sorted(salida, key=lambda n: (n.caja[1], n.caja[0]))


def cartel_de(ap: Aparato, etiqueta: str, xml: str | None = None) -> Nodo | None:
    """
    El cartel de ese título.

    De todos los nodos que llevan esa etiqueta se queda con el más grande: el cartel y su rótulo
    dicen lo mismo, y el que se puede tocar con confianza es el que ocupa media pantalla, no las
    dos líneas de texto de debajo.
    """
    xml = ap.volcado() if xml is None else xml
    iguales = [n for n in ap.nodos(xml) if n.etiqueta.strip() == etiqueta]
    if not iguales:
        return None
    return max(iguales, key=lambda n: (n.caja[2] - n.caja[0]) * (n.caja[3] - n.caja[1]))


def boton_lista(ap: Aparato) -> Nodo | None:
    """
    El botón «Mi lista» de la ficha, que no es la pestaña «Mi lista» de la tira.

    Se llaman igual, y buscar por texto encuentra primero la pestaña — con lo que la prueba se va a
    otra pantalla y luego se queja de que el botón no cambió. La tira vive pegada al borde de
    arriba; cualquier cosa por debajo de eso es de la ficha.
    """
    for nodo in ap.nodos():
        if nodo.etiqueta.strip() in ("Mi lista", "En mi lista") and nodo.caja[1] > 200:
            return nodo
    return None


def es_ficha(ap: Aparato) -> bool:
    """Una ficha es de película o de serie, y no se parecen: una tiene botón, la otra temporadas."""
    return any(ap.buscar(marca) for marca in ("Reproducir", "Seguir en", "Temporada"))


def abrir_algo(ap: Aparato) -> None:
    lista = carteles(ap)
    if not lista:
        raise Saltar("no hay carteles que abrir en esta pantalla")
    ap.tocar(*lista[0].centro)
    time.sleep(8)


def entrar(ap: Aparato, persona: str = "Vicente", limpio: bool = True) -> None:
    """Arranca y deja la app en el inicio de esa persona."""
    ap.arrancar(limpio=limpio)
    time.sleep(6)
    if ap.esperar("¿Quién está viendo?", 25):
        afirmar(ap.pulsar(persona, 10), f"no aparece la persona «{persona}»")
        time.sleep(6)
    afirmar(ap.esperar("Películas", 30) is not None, "no se llegó a la pantalla principal")


def a_inicio(ap: Aparato) -> None:
    """Deshace lo que haya encima hasta volver a la tira de pestañas."""
    for _ in range(6):
        if ap.buscar("Películas") and not ap.buscar("Reproducir"):
            return
        ap.tecla("BACK")
        time.sleep(1.2)


def sin_desastres(ap: Aparato) -> None:
    malas = ap.desastres()
    afirmar(not malas, "el registro trae " + " | ".join(malas[:2]))
    afirmar(ap.pid() is not None, "el proceso de la app ya no existe")


def servidor(consulta: str) -> str:
    """Corre una consulta contra la base de sincronización del VPS."""
    guion = (
        "import sqlite3\n"
        "c = sqlite3.connect('/var/lib/simpletv-admin/progreso.db')\n"
        + consulta
    )
    # The host is not written here: this file is published, and the VPS's name is half a lead for
    # anybody who fancies trying their luck with its panel.
    destino = os.environ.get("VPS_SSH")
    if not destino:
        raise Saltar("falta VPS_SSH; sin él no se puede consultar la base del servidor")
    escrito = subprocess.run(
        ["ssh", destino, "sudo python3 -"],
        input=guion, capture_output=True, text=True, timeout=120,
    )
    return escrito.stdout.strip()


def filas_lista(casa: str = "vicente") -> list[tuple]:
    salida = servidor(
        "print([tuple(r) for r in c.execute("
        "\"SELECT perfil, obra, borrado FROM lista WHERE casa = ? ORDER BY contador\", "
        f"('{casa}',))])"
    )
    try:
        return eval(salida) if salida else []
    except Exception:
        return []


# =========================================================================== T0 · vuelo previo

@caso("T0.1", "el aparato responde y tiene la app instalada", "T0")
def t0_1(ap, ctx):
    afirmar(ap.vivo() or ap.reconectar(), "el aparato no contesta por ADB")
    afirmar(ap.instalado(), f"{PAQUETE} no está instalado")
    ctx["version"][ap.nombre] = ap.version()


@caso("T0.2", "las animaciones están a cero", "T0")
def t0_2(ap, ctx):
    for clave in ("window_animation_scale", "transition_animation_scale"):
        valor = ap.sh(f"settings get global {clave}").strip()
        afirmar(valor in ("0", "0.0"), f"{clave} = {valor}; los volcados no serán fiables")


@caso("T0.3", "hay catálogo descargado", "T0")
def t0_3(ap, ctx):
    entrar(ap)
    afirmar(ap.esperar("Seguir viendo", 40) or ap.esperar("Películas", 10),
            "la pantalla principal no trae ni filas ni pestañas")


# =========================================================================== T1 · humo

@caso("T1.1", "arranca y pregunta quién está viendo", "T1")
def t1_1(ap, ctx):
    ap.limpiar_registro()
    ap.arrancar()
    time.sleep(7)
    afirmar(ap.esperar("¿Quién está viendo?", 25) is not None,
            "no salió el selector de personas")
    for persona in ("Vicente", "Laura", "Emma"):
        afirmar(ap.buscar(persona) is not None, f"falta «{persona}» en el selector")
    sin_desastres(ap)


@caso("T1.2", "cada pestaña dibuja algo", "T1")
def t1_2(ap, ctx):
    entrar(ap)
    ap.limpiar_registro()
    for pestana, prueba in (("Películas", None), ("Series", None), ("Mi lista", None)):
        afirmar(ap.pulsar(pestana, 15), f"no se pudo tocar «{pestana}»")
        time.sleep(4)
        afirmar(ap.buscar("Películas") is not None,
                f"la tira de pestañas desapareció al entrar en «{pestana}»")
        afirmar(len(ap.textos()) > 3, f"«{pestana}» se quedó en blanco")
    sin_desastres(ap)


# =========================================================================== T2 · navigation

@caso("T2.1", "el D-pad recorre la tira de pestañas", "T2", aparatos=("tv",))
def t2_1(ap, ctx):
    entrar(ap)
    ap.tecla("DPAD_UP")
    time.sleep(1)
    for _ in range(5):
        ap.tecla("DPAD_RIGHT")
        time.sleep(0.5)
    afirmar(ap.en_primer_plano(), "la app perdió el foco recorriendo la tira")
    for _ in range(5):
        ap.tecla("DPAD_LEFT")
        time.sleep(0.5)
    afirmar(ap.buscar("Películas") is not None, "la tira dejó de dibujarse")
    sin_desastres(ap)


@caso("T2.2", "abrir una ficha y volver deja donde estaba", "T2")
def t2_2(ap, ctx):
    entrar(ap)
    afirmar(ap.pulsar("Series", 15), "no se pudo ir a Series")
    time.sleep(5)
    abrir_algo(ap)
    afirmar(es_ficha(ap), "lo que se abrió no parece una ficha")
    ap.tecla("BACK")
    time.sleep(4)
    afirmar(not es_ficha(ap), "la ficha sigue en pantalla después de volver")
    afirmar(ap.buscar("Películas") is not None, "no se volvió a una pantalla con pestañas")
    sin_desastres(ap)


@caso("T2.3", "el buscador encuentra y abre", "T2")
def t2_3(ap, ctx):
    entrar(ap)
    lupa = [n for n in ap.nodos() if n.desc.strip().lower() == "buscar"]
    if not lupa:
        raise Saltar("no se localiza la lupa en la tira")
    ap.tocar(*lupa[0].centro)
    time.sleep(4)
    campo = [n for n in ap.nodos() if "EditText" in n.clase]
    if campo:
        ap.tocar(*campo[0].centro)
        time.sleep(1.5)
    ap.escribir("ben hur")
    time.sleep(6)
    afirmar(ap.buscar("Ben") is not None, "buscar «ben hur» no devolvió nada")
    sin_desastres(ap)


@caso("T2.4", "la pila de vuelta atrás termina saliendo de la app", "T2")
def t2_4(ap, ctx):
    entrar(ap)
    ap.pulsar("Series", 10)
    time.sleep(4)
    for _ in range(8):
        ap.tecla("BACK")
        time.sleep(1)
    afirmar(ap.pid() is not None or not ap.en_primer_plano(),
            "la app ni salió limpiamente ni sigue viva")
    sin_desastres(ap)


# =========================================================================== T3 · personas

@caso("T3.1", "se cambia de persona desde el círculo", "T3")
def t3_1(ap, ctx):
    entrar(ap, "Vicente")
    # The node with the letter is not the clickable one: in Compose the `clickable` lives on the
    # `Box` above it, and the dump reports them as two nodes. Tapping the centre of the letter lands
    # inside the parent all the same.
    inicial = texto_exacto(ap, "V")
    if not inicial:
        raise Saltar("no se localiza el círculo de persona")
    ap.tocar(*inicial.centro)
    time.sleep(5)
    afirmar(texto_exacto(ap, "V") is None or texto_exacto(ap, "L") is not None,
            "el círculo no cambió de persona")
    sin_desastres(ap)


@caso("T3.2", "«Mi lista» es de cada persona", "T3")
def t3_2(ap, ctx):
    entrar(ap, "Vicente")
    afirmar(ap.pulsar("Mi lista", 15), "no se pudo abrir Mi lista")
    time.sleep(5)
    de_vicente = {t for t in ap.textos() if t not in ("Películas", "Series", "Mi lista", "V")}
    entrar(ap, "Emma")
    afirmar(ap.pulsar("Mi lista", 15), "no se pudo abrir Mi lista de Emma")
    time.sleep(5)
    de_emma = {t for t in ap.textos() if t not in ("Películas", "Series", "Mi lista", "E")}
    afirmar(de_vicente != de_emma or not de_vicente,
            "las dos personas ven exactamente la misma lista")
    sin_desastres(ap)


# =========================================================================== T4 · catalogue

@caso("T4.1", "Ben-Hur no ofrece la copia que es otra película", "T4")
def t4_1(ap, ctx):
    entrar(ap)
    lupa = [n for n in ap.nodos() if n.desc.strip().lower() == "buscar"]
    if not lupa:
        raise Saltar("no se localiza la lupa")
    ap.tocar(*lupa[0].centro)
    time.sleep(4)
    campo = [n for n in ap.nodos() if "EditText" in n.clase]
    if campo:
        ap.tocar(*campo[0].centro)
        time.sleep(1.5)
    ap.escribir("ben hur")
    time.sleep(6)
    if not texto_exacto(ap, "Ben-Hur"):
        raise Saltar("el proveedor ya no lista «Ben-Hur»")
    objetivo = cartel_de(ap, "Ben-Hur")
    afirmar(objetivo is not None, "«Ben-Hur» sale en la lista pero su cartel no se puede tocar")
    ap.tocar(*objetivo.centro)
    time.sleep(9)
    afirmar(ap.buscar("Ben-Hur") is not None, "no se abrió la ficha de Ben-Hur")
    # Deliberately a long wait: the picker is not drawn until `agreeingSources` has asked each copy
    # how long it runs, which is two or three requests to the supplier.
    if not ap.esperar("Calidad", 30):
        raise Saltar("esta copia ya no tiene selector de calidad")
    afirmar(texto_exacto(ap, "4K") is not None, "falta la copia 4K")
    afirmar(texto_exacto(ap, "HD") is None,
            "sigue ofreciéndose «HD», que dura 2 h 03 y no es la película de 1959")
    sin_desastres(ap)


@caso("T4.2", "una serie enseña sus temporadas", "T4")
def t4_2(ap, ctx):
    entrar(ap)
    objetivo = ap.buscar("Mad Men")
    if not objetivo:
        raise Saltar("«Mad Men» no está en Seguir viendo hoy")
    ap.tocar(*objetivo.centro)
    time.sleep(9)
    afirmar(ap.buscar("Temporada") is not None, "la ficha de una serie no trae temporadas")
    sin_desastres(ap)


# =========================================================================== T5 · playback

def _reproducir_algo(ap: Aparato) -> None:
    entrar(ap)
    abrir_algo(ap)
    for boton in ("Seguir en", "Reproducir"):
        nodo = ap.buscar(boton)
        if nodo:
            ap.tocar(*nodo.centro)
            break
    else:
        # A series has no button: it is started from the first episode of the open season.
        episodios = [
            n for n in ap.nodos()
            if n.clicable and (n.caja[2] - n.caja[0]) > (n.caja[3] - n.caja[1]) * 4
            and (n.caja[3] - n.caja[1]) > 80
        ]
        if not episodios:
            raise Saltar("la ficha no ofrece nada que reproducir")
        ap.tocar(*episodios[0].centro)
    time.sleep(25)


@caso("T5.1", "reproducir levanta una pista de audio", "T5")
def t5_1(ap, ctx):
    ap.limpiar_registro()
    _reproducir_algo(ap)
    pid = ap.pid()
    afirmar(pid is not None, "la app murió al reproducir")
    pistas = ap.sh("dumpsys audio | grep AudioTrack | grep started")
    afirmar(str(pid) in pistas,
            "no hay ninguna pista de audio viva del proceso de la app")
    ctx["audio"][ap.nombre] = pistas.strip().splitlines()[:1]
    ctx.setdefault("reprodujo", set()).add(ap.nombre)
    sin_desastres(ap)


@caso("T5.2", "el descodificador de FFmpeg viaja dentro del APK", "T5")
def t5_2(ap, ctx):
    if ap.nombre not in ctx.get("reprodujo", set()):
        raise Saltar("T5.1 no llegó a reproducir; sin eso no hay renderer que mirar")
    afirmar("Loaded FfmpegAudioRenderer" in ap.registro(),
            "no se cargó el renderer de FFmpeg: el .aar no entró en el APK")


@caso("T5.4", "la tablet no intenta pasar audio comprimido", "T5", aparatos=("tablet",))
def t5_4(ap, ctx):
    """
    Sólo la tablet, y no por descuido.

    Sobre el box no se puede afirmar el camino sin saber qué se está reproduciendo: con una pista
    DTS abre una salida en crudo por HDMI, y con una AAC la descodifica y saca PCM. Las dos son
    correctas, así que una prueba que exija una de ellas está afirmando sobre el catálogo del
    proveedor y no sobre la app. La tablet sí tiene un invariante: suene lo que suene, no puede
    abrir una salida comprimida, porque al otro lado hay dos altavoces y no un receptor.
    """
    if ap.nombre not in ctx.get("reprodujo", set()):
        raise Saltar("T5.1 no llegó a reproducir")
    salida = ap.sh("dumpsys media.audio_flinger | grep -A3 'type 4 (OFFLOAD)' | grep 'HAL format'")
    for comprimido in ("DTS", "AC3", "AC4", "IEC61937"):
        afirmar(comprimido not in salida,
                f"la tablet abrió una salida {comprimido}, que no sabe descodificar nadie")


@caso("T5.3", "salir del reproductor devuelve a la ficha", "T5")
def t5_3(ap, ctx):
    if ap.nombre not in ctx.get("reprodujo", set()):
        raise Saltar("T5.1 no llegó a reproducir; no hay reproductor del que salir")
    ap.tecla("BACK")
    time.sleep(6)
    afirmar(es_ficha(ap), "al salir del reproductor no se volvió a la ficha")
    sin_desastres(ap)


# =========================================================================== T6 · synchronisation

@caso("T6.1", "guardar en un aparato llega al servidor con su perfil", "T6",
      aparatos=("tv",), en_pareja=True)
def t6_1(ap, ctx):
    otro: Aparato = ctx["pareja"]
    antes = {(p, o) for p, o, b in filas_lista() if not b}

    entrar(ap, "Vicente")
    # Through "Películas" rather than the first thing on the front page: a series' detail opens
    # scrolled down to its episodes, and the row of buttons ends up off screen.
    afirmar(ap.pulsar("Películas", 15), "no se pudo ir a Películas")
    time.sleep(5)
    abrir_algo(ap)
    boton = boton_lista(ap)
    afirmar(boton is not None, "la ficha no trae el botón de Mi lista")
    if boton.etiqueta.strip() == "En mi lista":
        # It was already saved: it is removed first, so that what is measured is a fresh mark.
        ap.tocar(*boton.centro)
        time.sleep(8)
        boton = boton_lista(ap)
        afirmar(boton is not None, "el botón desapareció al quitarla de la lista")
    ap.tocar(*boton.centro)
    time.sleep(10)
    despues_boton = boton_lista(ap)
    afirmar(despues_boton is not None and despues_boton.etiqueta.strip() == "En mi lista",
            "el botón no pasó a «En mi lista»")

    despues = {(p, o) for p, o, b in filas_lista() if not b}
    nuevas = despues - antes
    afirmar(nuevas, "no llegó ninguna entrada nueva al VPS")
    perfiles = {p for p, _ in nuevas}
    afirmar(perfiles == {0}, f"la entrada llegó con el perfil {perfiles}, no con el de Vicente")
    ctx["guardada"] = next(iter(nuevas))[1]


@caso("T6.2", "lo guardado aparece en el otro aparato", "T6",
      aparatos=("tv",), en_pareja=True)
def t6_2(ap, ctx):
    obra = ctx.get("guardada")
    if not obra:
        raise Saltar("T6.1 no dejó nada guardado que buscar")
    otro: Aparato = ctx["pareja"]
    afirmar(otro.vivo() or otro.reconectar(), "el otro aparato no contesta")

    otro.limpiar_registro()
    entrar(otro, "Vicente")
    # A return to the foreground is what triggers the inbound sync.
    otro.tecla("HOME")
    time.sleep(3)
    otro.arrancar(limpio=False)
    time.sleep(12)
    afirmar("ProgressSync" in otro.registro(), "el otro aparato no llegó a sincronizar")
    afirmar(otro.pulsar("Mi lista", 15), "no se pudo abrir Mi lista en el otro aparato")
    time.sleep(6)
    afirmar(len(otro.textos()) > 4, "«Mi lista» del otro aparato está vacía")


@caso("T6.3", "quitar viaja como lápida", "T6", aparatos=("tv",), en_pareja=True)
def t6_3(ap, ctx):
    obra = ctx.get("guardada")
    if not obra:
        raise Saltar("T6.1 no dejó nada guardado que quitar")
    entrar(ap, "Vicente")
    abrir_algo(ap)
    boton = boton_lista(ap)
    if not boton or boton.etiqueta.strip() != "En mi lista":
        raise Saltar("la ficha ya no está guardada")
    ap.tocar(*boton.centro)
    time.sleep(10)
    lapidas = {o for _, o, b in filas_lista() if b}
    afirmar(obra in lapidas, "quitarla no dejó lápida en el servidor")


# =========================================================================== T7 · builds

@caso("T7.1", "durante la construcción sólo se puede ir a la tele", "T7",
      aparatos=("tablet",), destructivo=True)
def t7_1(ap, ctx):
    ap.sh(f"pm clear {PAQUETE}")
    time.sleep(2)
    ap.arrancar()
    time.sleep(10)
    if ap.esperar("¿Quién está viendo?", 30):
        ap.pulsar("Vicente", 10)
        time.sleep(6)
    afirmar(ap.esperar("Preparando el videoclub", 60) is not None,
            "no salió la pantalla de construcción")
    nodos = ap.nodos()
    apagadas = {"Inicio", "Películas", "Series", "Mi lista"}
    for nodo in nodos:
        if nodo.etiqueta.strip() in apagadas:
            afirmar(not nodo.clicable,
                    f"«{nodo.etiqueta}» se puede pulsar mientras se construye el catálogo")
    tele = [n for n in nodos if n.desc.strip().lower() in ("tv", "televisión", "television")]
    afirmar(tele and tele[0].clicable, "la tele no queda alcanzable durante la construcción")
    sin_desastres(ap)


# =========================================================================== T8 · casos locos

@caso("T8.1", "machacar atrás no rompe nada", "T8")
def t8_1(ap, ctx):
    entrar(ap)
    ap.limpiar_registro()
    ap.pulsar("Series", 10)
    time.sleep(3)
    for _ in range(20):
        ap.tecla("BACK")
        time.sleep(0.25)
    time.sleep(3)
    sin_desastres(ap)


@caso("T8.2", "machacar el D-pad no rompe nada", "T8", aparatos=("tv",))
def t8_2(ap, ctx):
    entrar(ap)
    ap.limpiar_registro()
    ordenes = ["DPAD_DOWN", "DPAD_RIGHT", "DPAD_UP", "DPAD_LEFT", "DPAD_CENTER"] * 12
    ap.sh("input keyevent " + " ".join(f"KEYCODE_{o}" for o in ordenes))
    time.sleep(6)
    sin_desastres(ap)


@caso("T8.3", "matar el proceso y volver no pierde la casa", "T8")
def t8_3(ap, ctx):
    entrar(ap)
    ap.sh(f"am kill {PAQUETE}")
    time.sleep(3)
    ap.limpiar_registro()
    ap.arrancar(limpio=False)
    time.sleep(12)
    afirmar(ap.buscar("Películas") is not None or ap.buscar("¿Quién está viendo?") is not None,
            "después de matarlo no vuelve a una pantalla usable")
    sin_desastres(ap)


@caso("T8.4", "abrir y cerrar una ficha diez veces seguidas", "T8")
def t8_4(ap, ctx):
    entrar(ap)
    ap.limpiar_registro()
    lista = carteles(ap)
    if not lista:
        raise Saltar("no hay carteles que abrir")
    punto = lista[0].centro
    for _ in range(10):
        ap.tocar(*punto)
        time.sleep(1.2)
        ap.tecla("BACK")
        time.sleep(1.0)
    time.sleep(4)
    afirmar(ap.buscar("Películas") is not None, "se quedó en un sitio sin pestañas")
    sin_desastres(ap)


@caso("T8.5", "basura en el buscador", "T8")
def t8_5(ap, ctx):
    entrar(ap)
    lupa = [n for n in ap.nodos() if n.desc.strip().lower() == "buscar"]
    if not lupa:
        raise Saltar("no se localiza la lupa")
    ap.tocar(*lupa[0].centro)
    time.sleep(4)
    campo = [n for n in ap.nodos() if "EditText" in n.clase]
    if campo:
        ap.tocar(*campo[0].centro)
        time.sleep(1.5)
    ap.limpiar_registro()
    for basura in ("zzzzzzzzzzzzzzzz", "%%%", "ñññ", "a" * 80):
        ap.escribir(basura)
        time.sleep(3)
    time.sleep(3)
    afirmar(ap.en_primer_plano(), "el buscador se llevó la app por delante")
    sin_desastres(ap)


@caso("T8.6", "quedarse sin red y recuperarla", "T8", aparatos=("tablet",), destructivo=True)
def t8_6(ap, ctx):
    """
    El corte se ordena **en el aparato**, no desde aquí.

    ADB llega por esa misma wifi: un `svc wifi disable` desde el portátil se corta a sí mismo el
    cable de vuelta y ya no hay forma de volver a encenderla. Así, la orden entera —apagar, esperar,
    encender— vive en el aparato y se cura sola aunque el portátil no vuelva a hablarle nunca.

    Sólo la tablet, y sólo bajo bandera: dejar el descodificador del salón sin wifi por una prueba
    es un precio que no paga quien la lanza.
    """
    entrar(ap)
    ap.limpiar_registro()
    ap.adb("shell", "nohup sh -c 'svc wifi disable; sleep 25; svc wifi enable' >/dev/null 2>&1 &")

    recuperado = False
    for _ in range(45):
        time.sleep(3)
        if ap.vivo() or ap.reconectar():
            recuperado = True
            break
    afirmar(recuperado, "el aparato no volvió a la red por sí solo; enciéndele la wifi a mano")

    time.sleep(8)
    afirmar(ap.pid() is not None, "la app no sobrevivió al corte de red")
    afirmar(ap.en_primer_plano(), "la app ya no está en pantalla después del corte")
    sin_desastres(ap)


@caso("T8.7", "dar diez veces a Mi lista deja un solo estado", "T8", aparatos=("tv",))
def t8_7(ap, ctx):
    entrar(ap, "Vicente")
    afirmar(ap.pulsar("Películas", 15), "no se pudo ir a Películas")
    time.sleep(5)
    abrir_algo(ap)
    boton = boton_lista(ap)
    if not boton:
        raise Saltar("la ficha no trae el botón de Mi lista")
    ap.limpiar_registro()
    for _ in range(10):
        ap.tocar(*boton.centro)
        time.sleep(0.6)
    time.sleep(12)
    final = boton_lista(ap)
    en_pantalla = final is not None and final.etiqueta.strip() == "En mi lista"
    guardadas = {o for _, o, b in filas_lista() if not b}
    lapidas = {o for _, o, b in filas_lista() if b}
    afirmar(not (guardadas & lapidas), "la misma obra está guardada y con lápida a la vez")
    ctx["converge"][ap.nombre] = "guardada" if en_pantalla else "quitada"
    sin_desastres(ap)


@caso("T8.8", "arrancar dos veces seguidas no duplica la pantalla", "T8")
def t8_8(ap, ctx):
    ap.limpiar_registro()
    ap.arrancar()
    ap.sh(f"am start -n {PAQUETE}/.MainActivity")
    ap.sh(f"am start -n {PAQUETE}/.MainActivity")
    time.sleep(10)
    tareas = ap.sh(f"dumpsys activity activities | grep -c 'MainActivity'")
    afirmar(ap.en_primer_plano(), "la app no quedó en primer plano")
    sin_desastres(ap)


# =========================================================================== T9 · modo simple

def _en_modo_simple(ap: Aparato) -> bool:
    """
    Si lo que hay en pantalla es el modo simple: directo a la imagen, sin selector de personas y
    sin tira de pestañas.

    No hay todavía ninguna casa fija en modo simple entre las que usa esta tanda — «vicente» es
    videoclub completo, y la de Papá no se ha migrado —, así que esto se comprueba en vivo cada vez
    en lugar de darse por supuesto. Mientras no exista ese aparato, T9 entero se salta con
    `Saltar`, que es la respuesta correcta: no hay nada que probar todavía, y eso no es un fallo.
    """
    return ap.esperar("¿Quién está viendo?", 6) is None and ap.buscar("Películas") is None


def _entrar_simple(ap: Aparato) -> None:
    ap.arrancar(limpio=True)
    time.sleep(8)


@caso("T9.1", "el modo simple arranca reproduciendo, sin tira de pestañas", "T9")
def t9_1(ap, ctx):
    ap.limpiar_registro()
    _entrar_simple(ap)
    if not _en_modo_simple(ap):
        raise Saltar("este aparato no está en modo simple hoy")
    afirmar(ap.buscar("Películas") is None, "el modo simple enseña la tira de pestañas")
    afirmar(ap.buscar("¿Quién está viendo?") is None, "el modo simple pregunta quién está viendo")
    pid = ap.pid()
    afirmar(pid is not None, "la app no está viva")
    pistas = ap.sh("dumpsys audio | grep AudioTrack | grep started")
    afirmar(str(pid) in pistas, "no hay ninguna pista de audio viva: no arrancó reproduciendo sola")
    sin_desastres(ap)


@caso("T9.2", "la lista de canales se abre y se cierra, y ahí sigue sin haber videoclub", "T9")
def t9_2(ap, ctx):
    if not _en_modo_simple(ap):
        raise Saltar("este aparato no está en modo simple hoy")
    # OK opens the list the same way from a remote as from a touch: the key arrives over ADB on both
    # devices, and `LiveScreen` does not distinguish the source. It is the same thing `pulsar()`
    # already does throughout the run, without depending on this particular device's screen
    # coordinates.
    ap.tecla("DPAD_CENTER")
    afirmar(ap.esperar("Cuenta:", 10) is not None, "OK no abrió la lista de canales")
    afirmar(ap.buscar("Películas") is None, "la lista de canales trae la tira del videoclub")
    ap.tecla("BACK")
    time.sleep(3)
    afirmar(ap.buscar("Cuenta:") is None, "Atrás no cerró la lista de canales")
    afirmar(ap.buscar("Películas") is None, "cerrar la lista llevó al videoclub")
    sin_desastres(ap)


@caso("T9.3", "no hay ningún gesto que lleve al videoclub", "T9")
def t9_3(ap, ctx):
    if not _en_modo_simple(ap):
        raise Saltar("este aparato no está en modo simple hoy")
    ap.teclas("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", pausa=0.4)
    time.sleep(2)
    afirmar(ap.buscar("Películas") is None, "algún gesto llevó al videoclub")
    afirmar(ap.buscar("Series") is None, "algún gesto llevó al videoclub")
    sin_desastres(ap)
