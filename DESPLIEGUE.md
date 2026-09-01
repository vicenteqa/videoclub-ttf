# Instalar la app en un televisor

Hay dos maneras de cambiar lo que hace una casa, y cubren cosas distintas.

**Cambios de datos** — la cuenta del proveedor caducó, hay que añadir una persona, encender el modo
simple, mandar un canal. Se hacen desde el panel y la app los recoge sola: al arrancar, al encender
la tele y cada dos minutos mientras está abierta. **No hace falta ni compilar ni tocar el aparato**,
y es lo que vas a usar el 90 % de las veces. Está en [server/README.md](server/README.md).

**Cambios de código** — un fallo del reproductor, un canal nuevo en la curación, una pantalla nueva.
Requieren un APK, y eso es lo que documenta este fichero.

---

## Lo normal: `./deploy.sh`

```bash
./deploy.sh --casa papa              # compila, instala y reinicia
./deploy.sh --casa papa --logs       # el logcat de la app, en vivo
./deploy.sh --casa papa --no-build   # instala el APK que ya está en build-out/
```

El transporte es ADB sobre Tailscale, porque las cajas están detrás del router de otra casa y no hay
puerto que abrir. El televisor de cada casa se declara aparte en `local.properties`
(`casa.<id>.tv.adb.host`) y **no se hereda**: heredarlo significa que un `--casa` despistado instala
en el televisor de tu padre, y eso no lo arregla ningún mensaje de error posterior.

Para probar en un cacharro tuyo sin tocar el de nadie:

```bash
./deploy.sh --casa papa --host mi-tablet
```

## El montaje inicial de una caja, con el televisor delante

Los pasos 1–3 exigen tener la pantalla a mano; del 4 en adelante todo es desde aquí. Reserva media
hora en la próxima visita.

### 1. Opciones de desarrollador

Ajustes → Información del dispositivo → pulsar siete veces sobre **Número de compilación**. Vuelve
atrás, entra en **Opciones de desarrollador** y activa **Depuración USB** y **Depuración por red**
(o *Depuración inalámbrica*). Anota lo que ponga en pantalla: las cajas tipo AOSP escuchan en el
puerto **5555**, y Android 11+ con *Depuración inalámbrica* exige emparejar antes con un puerto y un
código de seis cifras.

### 2. Tailscale

Instálalo en el televisor e inicia sesión. En la consola de Tailscale, renombra esa máquina a algo
estable —`videoclub-salon`— y **desactívale la caducidad de la clave**. Si no, dentro de unos meses
la tele se cae del tailnet sola y vuelves a necesitar una visita.

### 3. La huella RSA

Con la tele todavía delante:

```bash
./deploy.sh --pair <host:puerto> <codigo>   # sólo Android 11+
./deploy.sh --casa <id>
```

En la tele sale un diálogo con la huella de este portátil. Marca **Permitir siempre desde este
ordenador**. Ese «siempre» es lo que hace que no haya que volver.

### 4. La firma

Si en esa caja hay una versión firmada con otra clave —por ejemplo una compilada antes de que
existiera la keystore de release—, Android **no la deja actualizar**. Hay que desinstalar una vez:

```bash
adb -s <host>:5555 uninstall com.videoclub.app
./deploy.sh --casa <id>
```

Desinstalar duele menos de lo que parece: el progreso y «Mi lista» viven en el VPS y vuelven solos
al reinstalar, y el catálogo se reconstruye. Se pierde la caché local y poco más.

A partir de ahí, todas las actualizaciones son directas y para siempre — **mientras conserves
`keystore/videoclub-release.p12` y su contraseña**.

## Cuando falle

**No conecta.** Por orden: `tailscale status` para ver si la tele está en línea; después, opciones
de desarrollador. Muchas cajas **apagan la depuración por red al reiniciarse** — es la causa más
común y la debilidad real de esta vía. Si hay un corte de luz, hace falta que alguien de esa casa
vuelva a activar el interruptor.

**`unauthorized`.** Se borró la autorización RSA. Hay que aceptar el diálogo en pantalla otra vez.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** Falta el paso 4: hay instalada una build con otra firma.

**`INSTALL_FAILED_VERSION_DOWNGRADE`.** El `versionCode` sale de la fecha, así que esto sólo pasa si
el reloj de esta máquina va atrasado respecto a cuando se compiló lo que hay instalado.

## Lo que hay que guardar

`keystore/videoclub-release.p12` y su contraseña. Ninguno de los dos está en el repositorio. Si el
disco de este portátil muere sin una copia, la única forma de volver a actualizar un televisor es ir
a desinstalar la app a mano, en el salón de cada casa.
