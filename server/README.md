# El VPS: documentos alojados y panel

Cada casa tiene un fichero JSON en el VPS del que su app lee la cuenta del proveedor y todo lo demás
que la distingue. El panel es una página para editar esos ficheros sin escribirlos a mano.

**El VPS tiene otras cosas funcionando.** Todo lo de aquí está escrito partiendo de esa base: el
guion de preparación no aplica nada por defecto, no instala paquetes, no ejecuta certbot, no
sobrescribe ficheros sin copiarlos aparte antes, y se niega a seguir en cuanto encuentra algo
ocupando el sitio que quería. Vive en `/srv/videoclub` y no toca `/var/www`.

## El documento de una casa

**Aquí vive la cuenta entera.** El APK no lleva ninguna dentro: sin `url`, `username` y `password`
la app arranca, dice «Error de credenciales» y no pasa de ahí. El documento mínimo que funciona:

```json
{
  "url": "http://servidor.com:8080",
  "username": "usuario",
  "password": "contraseña"
}
```

| Campo               | Qué es                                                              |
|---------------------|---------------------------------------------------------------------|
| `url`               | dirección del proveedor Xtream                                      |
| `username`          | usuario de la cuenta                                                |
| `password`          | contraseña de la cuenta                                             |
| `userAgent`         | el User-Agent que exige el proveedor                                |
| `reportUrl`         | a dónde informar de lo que se ve; debe ser https                    |
| `reportToken`       | la credencial de eso; sin los dos, la app no informa de nada        |
| `perfiles`          | las personas de la casa: `[{"id": 0, "nombre": "…"}]`               |
| `siguientePerfilId` | el siguiente id a repartir, para que un borrado no lo reutilice     |
| `simple`            | `true` = sólo televisión en directo, sin videoclub                  |
| `canales`           | canales que no están en el proveedor; ver abajo                     |
| `poner`             | «pon este canal»: un recado con fecha, no un ajuste                 |

**Un campo ausente significa «deja lo que había»**, nunca «bórralo». Es lo que permite editar el
documento a mano con una sola línea sin arrastrar al resto de la casa. Un valor vacío o `null`
cuenta como ausente. La app cachea lo último que leyó, así que un VPS caído no deja a nadie sin
cuenta: sigue con lo de siempre y sólo recarga cuando el documento cambia de verdad.

### `canales`: televisiones que el proveedor no tiene

Una casa puede añadir canales por su cuenta — una televisión local, normalmente:

```json
"canales": [
  {
    "nombre": "Penedès TV",
    "url": "https://…/playlist.m3u8",
    "logo": "https://…/logo.png",
    "userAgent": "Mozilla/5.0 … Chrome/135.0.0.0 Safari/537.36"
  }
]
```

El `userAgent` es por canal y no por casa **porque tienen que serlo**: el de la casa es el que exige
el proveedor IPTV, y la CDN de una televisión local suele rechazar cualquiera que no le suene a
navegador. Son dos servidores con exigencias incompatibles.

Estos canales van **al final** de la lista, nunca delante: delante se convertirían en el canal de
arranque de esa casa, y quien añade su televisión local no está pidiendo eso.

### `poner`: mandar un canal desde el panel

```json
"poner": {"canal": "Betis TV", "cuando": 1788261454}
```

Lleva fecha, y sin ella se ignora. La marca es lo que permite **obedecerla una sola vez**: el
documento sigue trayendo la orden después de cumplirla, así que sin ella la caja saltaría a ese
canal en cada consulta, para siempre. Caduca a los diez minutos, para que lo de anoche no se cumpla
al encender por la mañana.

Sólo aparece en el panel para las casas en modo simple, y sólo cuando su app está despierta — ver
«Saber si una app está viva».

## El panel

    https://<host>/panel/

Una tarjeta por casa con lo que se mira de un vistazo —quién está viendo algo ahora, cuándo se usó
por última vez— y una ficha por casa con cuatro pestañas:

- **Cuenta**: servidor, usuario, contraseña, User-Agent, y la casilla de **modo simple**. Al pie, lo
  que dice el proveedor: si la cuenta autentica, cuándo caduca y si hay alguien viendo.
- **Perfiles**: las personas de la casa. Apagada en las casas simples, que no tienen selector.
- **Qué ve**: lo que esa casa ha estado viendo. Se pide al abrir la pestaña y no antes.
- **Poner canal**: sólo en las casas simples. Ver abajo.

La contraseña del proveedor sale ya puesta en su campo, oculta hasta pulsar **VER**. No es un
descuido: quien pasa el cuadro de contraseña de nginx puede cambiarla igualmente, así que esconderla
no protegía nada y sí impedía responder a la única pregunta para la que existe el panel — en qué
cuenta está esa tele.

### Qué se está viendo

El canal no lo sabe el proveedor —con estas credenciales no hay forma de preguntárselo—, así que lo
dice la propia app. Las dos preguntas tienen dos fuentes distintas, a propósito:

| pregunta                      | quién la contesta                  |
|-------------------------------|------------------------------------|
| ¿hay alguien viendo la tele?  | el proveedor, con `active_cons`    |
| ¿qué está viendo?             | la app, con su último canal        |

Cruzarlas es lo que evita el fallo obvio: sin la primera, un corte de luz dejaría al panel jurando
que alguien ve La 1 a las cuatro de la mañana.

**Sólo se informa de lo que se asienta.** La app espera 45 segundos en el mismo canal antes de decir
nada, así que bajar doce filas de la lista manda cero mensajes. Una tarde de televisión son tres o
cuatro peticiones. Y **está apagado salvo que se encienda**: sin `reportUrl` y `reportToken` la app
no manda nada. Es la única parte de esto que registra lo que ve una persona, y su valor por defecto
es «no».

La marca de tiempo la pone el servidor: el reloj de un set-top box de treinta euros no es algo sobre
lo que apoyar un «hace 12 min».

### Saber si una app está viva

Para mandarle un canal a una casa hace falta que su app vaya a enterarse. La señal **no** es
`active_cons`: eso dice que la cuenta tiene un stream abierto, que puede ser cualquier reproductor
de cualquiera —y ése no lee nuestro documento— y a la vez se queda corto con una app abierta sin
reproducir, que sí lo lee.

La señal buena ya existe y no cuesta una petición nueva: **el registro de nginx**. La app pide su
documento cada dos minutos, cada casa por una ruta distinta, así que «esta casa pidió su documento
hace menos de cinco minutos» es exactamente «se enteraría de la orden». Tiene además una propiedad
que sale gratis: un APK viejo sólo lo pide al arrancar, así que no aparece y su casa sale apagada —
que es la verdad, porque ése no obedecería.

### Cuándo se usó por última vez

Dos fuentes, y se enseña la más reciente: lo que informa la app (exacto, pero sólo si lleva un APK
que informe) y lo que ve el panel cuando el proveedor dice que hay una conexión abierta (vale para
cualquier aparato, incluso uno con la app vieja).

Lo segundo **sólo se apunta cuando alguien abre el panel**, así que una semana sin mirarlo es una
semana sin apuntes. Esa fecha es un suelo —«al menos hasta entonces se usó»— y nunca un «no se usa
desde».

## Preparar el VPS, una vez

Copia el guion y **míralo antes de aplicar nada**:

```bash
scp server/setup-vps.sh tu-vps:/tmp/
ssh tu-vps
sudo /tmp/setup-vps.sh              # sólo informa: no cambia nada
sudo /tmp/setup-vps.sh --apply
```

Te dirá qué ficheros crearía, qué encontró ya montado y dónde se negaría a seguir.

## Cómo está montado

| Pieza                 | Dónde                                                     |
|-----------------------|-----------------------------------------------------------|
| El programa           | `/opt/simpletv-admin/simpletv-admin.py`                   |
| Su configuración      | `/etc/simpletv-admin.json` (modo 0600: lleva rutas secretas) |
| El servicio           | `simpletv-admin.service`, como `ubuntu`, en `127.0.0.1:8791` |
| Su estado             | `/var/lib/simpletv-admin/`                                |
| Los documentos        | `/srv/videoclub/<segmento>/provider.json`                 |
| TLS y contraseña      | nginx, `location /panel/`                                 |
| Usuarios del panel    | `/etc/nginx/simpletv-admin.htpasswd`                      |

**El nombre `simpletv-admin` es histórico**: el panel viene del proyecto SimpleTV, que se retiró.
Renombrar un servicio del que dependen seis casas es una tarea aparte y no se hace de pasada.

El programa **no** vive en `/srv/videoclub`, y no es capricho: ese directorio está publicado por
`alias` en nginx, así que cualquier cosa dentro es descargable por web.

El proceso escucha sólo en loopback y no sabe nada de TLS ni de contraseñas — de eso se encarga
nginx, que ya tenía las dos. Es biblioteca estándar pura: ni virtualenv, ni índice de paquetes, ni
una dependencia más que parchear en un servidor que tiene otras cosas en marcha.

### Desplegar un cambio del panel

No hay automatismo, y son seis casas: hazlo mirando.

```bash
ssh <vps> "sudo cp /opt/simpletv-admin/simpletv-admin.py /opt/simpletv-admin/simpletv-admin.py.bak-$(date +%Y%m%d-%H%M%S)"
scp server/admin/simpletv-admin.py <vps>:/tmp/admin.new
ssh <vps> "python3 -m py_compile /tmp/admin.new && sudo install -o root -g root -m 755 /tmp/admin.new /opt/simpletv-admin/simpletv-admin.py && sudo systemctl restart simpletv-admin"
```

El `py_compile` antes de instalar es el que evita dejar el panel de seis casas con un error de
sintaxis. Si algo va mal: `systemctl status simpletv-admin`, `journalctl -u simpletv-admin -n 50`.

### Añadir quién entra

```bash
printf '%s:%s\n' usuario "$(openssl passwd -apr1)" | sudo tee -a /etc/nginx/simpletv-admin.htpasswd
sudo systemctl reload nginx
```

## El modelo de amenazas, en corto

Quien tenga el APK saca de él la URL, y quien tenga la URL saca la cuenta. Esto no protege contra
eso y no puede: no hay login, la URL *es* la credencial, y de ahí el segmento aleatorio y el
`autoindex off`.

Aun así es una mejora clara sobre lo que sustituye. Unas credenciales que viven en un fichero se
rotan en diez segundos; unas compiladas en un APK que está en el salón de otra casa no se rotan
nunca.

## Cuando algo no cuadre

La app está escrita para que un fallo aquí no rompa nada —un fichero ilegible se ignora y se sigue
con la última cuenta cacheada— pero eso significa que **un error de sintaxis se parece mucho a que
no pase nada**. Si editas a mano en el servidor, valida:

```bash
python3 -m json.tool /srv/videoclub/<segmento>/provider.json
```

Y para ver qué decidió la tele, `./deploy.sh --casa <id> --logs` y busca `ProviderSettings` (adoptó
una cuenta nueva), `RemoteConfigClient` (no llegó a leerla) o `ProviderOverrides` (llegó, pero no
era JSON).
