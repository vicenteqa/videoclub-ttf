# Videoclub

Un videoclub y una televisión en directo para la familia, repartidos en varias casas: una app de
Android que se instala en el televisor de cada uno, y un panel en un VPS desde el que se lleva todo
sin tener que ir a ninguna de esas casas.

---

> # ⚠️ ESTO ESTÁ *VIBE CODED* ⚠️
>
> **Prácticamente todo el código de este repositorio —la app, el panel, los guiones, los tests y
> esta misma documentación— lo ha escrito un modelo de lenguaje**, conversando, a lo largo de
> muchas sesiones. No hay aquí una revisión línea a línea hecha por una persona.
>
> Lo que eso significa en la práctica:
>
> - **Funciona, y está probado en aparatos de verdad**, pero «probado» quiere decir que alguien lo
>   vio funcionar, no que exista una red de seguridad que avise cuando se rompa.
> - **Hay decisiones que parecen deliberadas y lo son sólo a medias.** Los comentarios explican por
>   qué se hizo cada cosa; créetelos como intención, no como verdad verificada.
> - **No lo uses como referencia de cómo se hacen las cosas.** Es un proyecto doméstico que
>   resuelve un problema doméstico.
> - **Si vas a tocarlo, lee lo que toques.** Especialmente el panel, del que dependen televisores
>   de otras personas que no pueden arreglarlo ellas.
>
> Dicho esto: lleva meses dando servicio a varias casas sin que nadie haya tenido que llamar por
> teléfono. Vale para lo que vale.

---

## Qué es cada cosa

```
app/                 La aplicación Android (Kotlin + Compose, media3/ExoPlayer)
server/admin/        El panel: un servidor HTTP en Python de la biblioteca estándar
server/setup-vps.sh  La preparación del VPS, una vez
tests/e2e/           Pruebas contra aparatos reales, por ADB
deploy.sh            Compilar e instalar en el televisor de una casa
sync-casas.sh        Traerse del panel la lista de casas a local.properties
```

### La app

Dos mitades que comparten cuenta y conexión:

- **El videoclub**: catálogo de películas y series del proveedor, con perfiles por persona,
  «Seguir viendo» y «Mi lista» sincronizados entre los aparatos de la misma casa.
- **La televisión en directo**: una lista de canales curada a mano a partir de los ~2.000 que sirve
  el proveedor, con guía, cadena de respaldo por canal y zapeo.

Y un **modo simple**: una casa puede configurarse para que la app sea sólo la televisión —arranca
sintonizando, sin pestañas, sin catálogo y sin selector de personas—. Es para quien quiere una tele
y no un menú. Se enciende con una casilla en el panel.

### El panel

Vive en el VPS, en `https://<host>/panel/`. Desde ahí se da de alta una casa, se le cambia la
contraseña del proveedor, se ve qué está viendo cada televisor, y —en las casas simples— se le puede
mandar un canal a distancia.

No es una aplicación web moderna: es un fichero de Python con la biblioteca estándar, sin
dependencias, sirviendo HTML detrás de nginx. Está así a propósito — el VPS tiene otras cosas
funcionando y esto no debía añadirle un ecosistema que mantener.

## La idea que lo sostiene todo

**Nada de la cuenta está compilado en el APK.** El servidor del proveedor, el usuario, la
contraseña, las personas de la casa, si va en modo simple: todo eso vive en un documento JSON en el
VPS, uno por casa, y la app lo lee al arrancar y cada dos minutos mientras está abierta.

La consecuencia práctica es la que importa: **cambiar una contraseña es editar un fichero en un
servidor**, no conducir hasta el salón de otra persona con un portátil.

Lo único que sí va compilado es *qué documento leer*, porque es lo único que dos casas no comparten.
De ahí que haya un APK por casa: Gradle genera un *flavour* por cada `casa.<id>.remoteConfig.url`
de `local.properties`, que a su vez las trae del panel `./sync-casas.sh`.

Esa URL **es la credencial**: el documento lleva la contraseña en claro y no hay ningún login. Por
eso el segmento aleatorio del path, y por eso `local.properties` no se sube.

## Empezar

```bash
cp local.properties.example local.properties   # y rellenarlo
./build-ffmpeg-decoder.sh                      # el descodificador, una vez
./sync-casas.sh                                # trae las casas desde el panel
./gradlew :app:assembleVicenteDebug            # o la casa que sea
```

Hace falta **JDK 17** (versiones más nuevas las rechaza el plugin de Android) y el SDK de Android.

Para instalar en un televisor, ver [DESPLIEGUE.md](DESPLIEGUE.md).
Para el VPS, el documento alojado y el panel, ver [server/README.md](server/README.md).

## Firma

Los release se firman con `keystore/videoclub-release.p12`, que **no está en el repositorio**.
Android sólo acepta una actualización firmada con la misma clave que el APK instalado, así que
perder esa clave significa no poder volver a actualizar ningún televisor sin ir a desinstalar a
mano. El fichero y su contraseña van en un gestor de contraseñas, no en este disco.

El `versionCode` se genera solo a partir de la fecha (`AAMMDDHH`), porque un contador que hay que
acordarse de subir se olvida — y el olvido no se ve al compilar, se ve semanas después en un
aparato que no se actualiza.

## De dónde viene

Esto empezó como dos proyectos: **SimpleTV**, una app que era sólo televisión en directo, y
**Videoclub**, que además llevaba el catálogo. Compartían la mitad del código en dos copias, así que
cada arreglo había que hacerlo dos veces — y alguna vez se hizo sólo una.

En septiembre de 2026 se fusionaron: Videoclub aprendió el «modo simple» y SimpleTV se retiró. El
panel viene de aquel proyecto, y de ahí que su fichero y su servicio en el VPS todavía se llamen
`simpletv-admin`: renombrar un servicio del que dependen seis casas es una tarea aparte, con su
propio riesgo, y no se hace de pasada.
