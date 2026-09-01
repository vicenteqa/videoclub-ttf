# Banco de pruebas de Videoclub

Pruebas de caja negra contra los aparatos de verdad — el Xiaomi TV Box del salón y la Galaxy Tab —
por ADB sobre wifi. No hay simulador: el catálogo es el del proveedor, la cuenta es la de casa y el
servidor de sincronización es el VPS. Lo que pasa aquí es lo que pasa una noche cualquiera.

## Por qué así y no con tests instrumentados

Un `androidTest` de Compose corre *dentro* del proceso de la app, y la mitad de lo que hay que
comprobar aquí no está dentro: si el descodificador de audio llegó a arrancar, si la fila salió
hacia el VPS, si el D-pad puede alcanzar un chip. Eso se ve desde fuera — `dumpsys`, `logcat`, la
base del panel — y desde fuera es donde se mira.

La contrapartida es que estas pruebas necesitan aparatos encendidos y red. No sustituyen a los tests
unitarios de `TitleNaming`, `Genres` o `SourceAgreement`, que son los que dicen si la lógica está
bien; éstas dicen si el aparato hace lo que la lógica promete.

## Cómo se afirma

Por orden de preferencia, y nunca por captura de pantalla:

| Fuente | Qué demuestra |
|---|---|
| `uiautomator dump` | El texto que hay en pantalla. Compose expone su semántica a accesibilidad, así que «Mi lista» o «Temporada 4» son nodos consultables. **Requiere las animaciones a 0**, o el volcado muere en `could not get idle state`. |
| `logcat` | Que un renderer se cargó, que la sincronización mandó N filas, que no hubo `FATAL EXCEPTION` ni `ANR`. |
| `dumpsys audio` / `media.audio_flinger` | Que hay una pista de audio viva y por qué camino sale. |
| `dumpsys activity` | Qué actividad está arriba y si el proceso sigue existiendo. |
| La base del VPS | Que lo que se decidió en un aparato llegó al servidor, con su perfil y su lápida. |

Las capturas se guardan igualmente, pero como prueba documental para un humano, no como aserción.

## Matriz de aparatos

| Nombre | Modelo | Entrada | Papel |
|---|---|---|---|
| `tv` | Xiaomi MiTV-AFMU0, Android 14 | D-pad, y también toque | El aparato del salón: descodificador DTS por hardware, tele estéreo |
| `tablet` | Samsung SM-P610, Android 16 (Lineage) | Toque | El aparato sin DTS ni AC3: el que prueba que FFmpeg entró |

Los dos llevan la casa `vicente`, con los perfiles Vicente, Laura y Emma. Eso es lo que permite
probar la sincronización de verdad: se decide en uno y se comprueba en el otro.

## Escalones

Se corren en orden. Un escalón que falla no detiene a los siguientes, pero un fallo en **T0** sí,
porque todo lo demás mentiría.

### T0 · Vuelo previo
Que el aparato está, que la app instalada es la que se acaba de compilar, que hay catálogo y que las
animaciones están a 0. Deja constancia de la versión probada.

### T1 · Humo
Arrancar, elegir persona, y que cada pestaña dibuje algo. Es el escalón que dice «la app no está
rota», y el único cuyo fallo hace inútil leer el resto.

### T2 · Navegación
Las pestañas por toque y por D-pad, la pila de vuelta atrás, la ficha, el buscador, el reproductor.
Aquí es donde se cae una app de televisión: no en la lógica, sino en que el cursor se quede en un
sitio del que no se puede salir.

### T3 · Personas
Cambiar de persona y comprobar que «Mi lista» y «Seguir viendo» son de esa persona y no de la
anterior. Una casa con tres perfiles que comparten lista es un fallo que sólo se ve en casa.

### T4 · Catálogo y ficha
Que el selector de calidad enseña lo que debe. Incluye el caso que motivó el guardia de duración:
**Ben-Hur no debe ofrecer «HD»**, porque esa copia es otra película.

### T5 · Reproducción
Que arranca, que hay audio y por qué camino sale. El box debe seguir sacando DTS en crudo por HDMI;
la tablet debe cargar `FfmpegAudioRenderer` y tener un `AudioTrack` vivo. Los dos aparatos, el mismo
fichero, dos caminos distintos y los dos correctos.

### T6 · Sincronización
Se guarda en un aparato, se comprueba en el VPS y se recoge en el otro. Ida, vuelta y lápida, y que
el perfil viaja con la fila.

### T7 · Construcción del catálogo *(destructivo)*
Con una sincronización en marcha, las cuatro pestañas de catálogo y la lupa deben estar apagadas y
la TV alcanzable. Exige borrar los datos de un aparato para forzarla, y por eso va aparte y bajo
bandera: sólo corre con `--destructivo`.

### T8 · Casos locos
Lo que nadie hace a propósito y todo el mundo acaba haciendo. Machacar el botón de atrás, matar el
proceso, quedarse sin red a mitad de película, dar veinte veces a «Mi lista» seguidas, escribir
basura en el buscador. La aserción de fondo es siempre la misma: **el proceso sigue vivo, no hay
`FATAL EXCEPTION` y no hay ANR**; y cuando el caso lo permite, que el estado converge.

## Qué no cubre, a propósito

- **La imagen y el sonido.** Ninguna máquina de aquí puede decir si se oye. Se comprueba que la
  cadena de audio existe y por dónde sale; oírlo sigue siendo cosa de una persona.
- **El proveedor.** Si el catálogo cambia bajo los pies —una copia nueva de Ben-Hur, un título
  retirado— algunos casos de T4 dejan de aplicar. Los que dependen de un título concreto lo dicen y
  se saltan en vez de fallar.
- **Las otras casas.** Manel y Pedro no tienen aparato aquí.

## Cómo se corre

```
export PATH=$PATH:~/Android/Sdk/platform-tools
python3 tests/e2e/run.py                      # todo lo no destructivo, los dos aparatos
python3 tests/e2e/run.py --aparato tablet     # sólo uno
python3 tests/e2e/run.py --escalon T5 T8      # sólo esos
python3 tests/e2e/run.py --destructivo        # incluye T7, que borra los datos de un aparato
```

Deja `tests/e2e/artefactos/<fecha>/` con el informe, las capturas y los volcados de cada fallo.
Restaura al salir lo que haya tocado en el aparato: las escalas de animación y el tiempo de pantalla.
