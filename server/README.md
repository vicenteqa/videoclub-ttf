# The VPS: hosted documents and the panel

Each household has a JSON file on the VPS from which its app reads the supplier account and
everything else that makes that household different. The panel is a web page for editing those files
without writing them by hand.

**This VPS has other things running on it.** Everything here is written on that basis: the setup
script applies nothing by default, installs no packages, runs no certbot, overwrites no file without
copying it aside first, and refuses to continue the moment it finds something already occupying the
place it wanted. It lives in `/srv/videoclub` and does not touch `/var/www`.

## A household's document

**The whole account lives here.** The APK carries none: without `url`, `username` and `password` the
app starts, says «Error de credenciales» and goes no further. The smallest document that works:

```json
{
  "url": "http://server.com:8080",
  "username": "user",
  "password": "password"
}
```

| Field               | What it is                                                          |
|---------------------|---------------------------------------------------------------------|
| `url`               | the Xtream supplier's address                                       |
| `username`          | the account's user                                                  |
| `password`          | the account's password                                              |
| `userAgent`         | the User-Agent the supplier insists on                              |
| `reportUrl`         | where to report what is being watched; must be https                |
| `reportToken`       | the credential for that; without both, the app reports nothing      |
| `perfiles`          | the people in the household: `[{"id": 0, "nombre": "…"}]`           |
| `siguientePerfilId` | the next id to hand out, so a deletion does not reuse one           |
| `simple`            | `true` = live television only, no video shop                        |
| `canales`           | channels the supplier does not carry; see below                     |
| `poner`             | "tune to this channel": an errand with a date, not a setting        |
| `apk`                | a published release, waiting to be fetched; see below               |

The field names are Spanish because they are the wire format, read by every device already
installed; renaming them would break all of them at once.

**An absent field means "leave whatever was there"**, never "delete it". That is what lets the
document be hand-edited one line at a time without dragging the rest of the household along. An
empty or `null` value counts as absent. The app caches the last document it read, so a VPS that is
down leaves nobody without an account: it carries on with what it had and only reloads when the
document really changes.

### `canales`: televisions the supplier does not carry

A household can add channels of its own — usually a local television station:

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

The `userAgent` is per channel rather than per household **because it has to be**: the household's
own is the one the IPTV supplier demands, and a local station's CDN usually rejects anything that
does not look like a browser. Two servers, two incompatible demands.

These channels go at the **end** of the list, never at the front: at the front they would become
that household's start-up channel, and somebody adding their local station is not asking for that.

### `poner`: sending a channel from the panel

```json
"poner": {"canal": "Betis TV", "cuando": 1788261454}
```

It carries a timestamp, and without one it is ignored. The timestamp is what allows it to be
**obeyed exactly once**: the document keeps carrying the order after it has been carried out, so
without it the box would jump to that channel on every check, forever. It expires after ten minutes,
so that last night's order is not carried out when the television is switched on in the morning.

It only appears in the panel for households in simple mode, and only while their app is awake — see
"Knowing whether an app is alive".

### `apk`: the app updating itself

```json
"apk": {"version": 26090114, "url": "https://…/<segment>/videoclub-26090114.apk", "sha256": "…"}
```

Unlike `poner`, this is not an errand with a fuse: it stays offered until a newer one replaces it,
because a box that has been off for a week should still find it waiting when it comes back.

`Updater`, on the app's side, downloads it as soon as it is newer than the running build, and then
waits for a person: installing kills the running process, and that must never happen without
somebody having said so. Whether it can install *silently* once they have depends on one thing:
whether that device has been made the phone's — sorry, the box's — **device owner**, a one-time,
in-person step documented in DEPLOYMENT.md. On every other device — every household today — the
confirmation is a deliberate gesture on the device itself: a small icon beside the **TV** tab for
full-catalogue households, tapped to open "Instalar" / "Ahora no"; in simple mode, which has no tab
strip, holding OK over the open channel list does the same thing. Either way the app never throws up
a surprise install dialog on its own initiative — a release just sits there, downloaded and quietly
waiting, until somebody notices the hint and acts on it.

The URL points at the very same secret directory that already serves `provider.json` — nothing new
is exposed by adding a second file next to the first. It gets there via `./publish.sh --casa <id>`,
which builds that household's flavour, uploads the APK over SSH, and publishes it in the same step
at `POST /liberar` — the household's document is updated as soon as the script finishes, no separate
button to press in the panel afterwards. A broken build still cannot travel further than the one
command that built it: publishing is still one household at a time, one command per household, and
what stops a bad build from reaching a living room is the gesture on the device, not a queue in the
panel.

There is no going back once a release lands: Android refuses to install anything with a lower
`versionCode` than what is already there. The safety net here is not technical, it is procedural —
send one household, let it sit, then the rest — and the app reports its own running version back
through `/informe` (a third kind of report, alongside "what is playing" and the channel list) so
that a card in the panel says the truth without anyone having to go and look.

## The panel

    https://<host>/panel/

One card per household with what you come to glance at — who is watching something right now, when
it was last used — and one dialog per household with four tabs:

- **Cuenta**: server, user, password, User-Agent, and the **simple mode** checkbox. At the foot,
  what the supplier says: whether the account authenticates, when it expires, whether anyone is
  watching — and, for Videoclub, which version is running and, if it differs from what has been
  published, a note that the household has not caught up yet. There is no button here: the device
  decides when. See `apk` above.
- **Perfiles**: the people of the household. Disabled for simple households, which have no picker.
- **Qué ve**: what that household has been watching. Fetched when the tab is opened and not before.
- **Poner canal**: simple households only. See below.

The supplier password is shown already filled in, masked until you press **VER**. That is not an
oversight: anyone past nginx's password prompt can change it anyway, so hiding it protected nothing
and did prevent answering the one question the panel exists for — which account is that television
on.

### What is being watched

The supplier does not know which channel — with these credentials there is no way to ask it — so the
app says. The two questions have two different sources, deliberately:

| question                          | who answers it                     |
|-----------------------------------|------------------------------------|
| is anyone watching television?    | the supplier, with `active_cons`   |
| what are they watching?           | the app, with its last channel     |

Crossing them is what avoids the obvious failure: without the first, a power cut would leave the
panel swearing that somebody is watching La 1 at four in the morning.

**Only settled viewing is reported.** The app waits 45 seconds on the same channel before saying
anything, so scrolling twelve rows down the list sends zero messages. An evening of television is
three or four requests. And **it is off unless switched on**: without `reportUrl` and `reportToken`
the app sends nothing. It is the only part of this that records what a person watches, and its
default is "no".

The timestamp is the server's: the clock in a thirty-euro set-top box is not something to rest a
"12 minutes ago" on.

### Knowing whether an app is alive

To send a household a channel, its app has to be going to find out. The signal is **not**
`active_cons`: that says the account has a stream open, which could be anybody's player — and that
one does not read our document — while it also falls short for an app that is open but not playing,
which does.

The right signal already exists and costs no extra request: **the nginx access log**. The app asks
for its document every two minutes, each household by a different path, so "this household asked for
its document less than five minutes ago" is exactly "it would find out about the order". It has a
property that comes for free, too: an old APK only asks at launch, so it does not appear and its
household shows as disabled — which is the truth, because that one would not obey anyway.

### When it was last used

Two sources, and the more recent one is shown: what the app reports (exact, but only if it runs an
APK that reports) and what the panel sees when the supplier says there is a connection open (works
for any device, even one running the old app).

The second is **only recorded while somebody has the panel open**, so a week without looking is a
week without records. That date is a floor — "it was in use at least until then" — and never a
"unused since".

## Preparing the VPS, once

Copy the script and **read it before applying anything**:

```bash
scp server/setup-vps.sh your-vps:/tmp/
ssh your-vps
sudo /tmp/setup-vps.sh              # reports only: changes nothing
sudo /tmp/setup-vps.sh --apply
```

It will tell you which files it would create, what it found already in place, and where it would
refuse to continue.

## How it is mounted

| Piece                 | Where                                                        |
|-----------------------|--------------------------------------------------------------|
| The program           | `/opt/simpletv-admin/simpletv-admin.py`                      |
| Its configuration     | `/etc/simpletv-admin.json` (mode 0600: it holds secret paths)|
| The service           | `simpletv-admin.service`, as `ubuntu`, on `127.0.0.1:8791`   |
| Its state             | `/var/lib/simpletv-admin/`                                   |
| The documents         | `/srv/videoclub/<segment>/provider.json`                     |
| TLS and password      | nginx, `location /panel/`                                    |
| Panel users           | `/etc/nginx/simpletv-admin.htpasswd`                         |

**The name `simpletv-admin` is historical**: the panel comes from the SimpleTV project, which was
retired. Renaming a service six households depend on is a separate job and is not done in passing.

The public base for generated URLs comes from `SIMPLETV_ADMIN_BASE`, set in the systemd unit. Its
default in the source is a placeholder on purpose: if someone forgets to set it, the URLs come out
visibly wrong instead of subtly wrong, which is the kind of mistake otherwise discovered weeks later
in an APK that never found its document.

The program does **not** live in `/srv/videoclub`, and that is not fussiness: that directory is
published by nginx `alias`, so anything inside it is downloadable over the web.

The process listens on loopback only and knows nothing about TLS or passwords — nginx handles both,
and already did. It is pure standard library: no virtualenv, no package index, not one more
dependency to patch on a server that has other things running.

### Deploying a change to the panel

There is no automation, and six households depend on it: do it watching.

```bash
ssh <vps> "sudo cp /opt/simpletv-admin/simpletv-admin.py /opt/simpletv-admin/simpletv-admin.py.bak-$(date +%Y%m%d-%H%M%S)"
scp server/admin/simpletv-admin.py <vps>:/tmp/admin.new
ssh <vps> "python3 -m py_compile /tmp/admin.new && sudo install -o root -g root -m 755 /tmp/admin.new /opt/simpletv-admin/simpletv-admin.py && sudo systemctl restart simpletv-admin"
```

The `py_compile` before installing is what stops six households' panel being left with a syntax
error. If something goes wrong: `systemctl status simpletv-admin`,
`journalctl -u simpletv-admin -n 50`.

**The backups do not clean themselves up.** Each deploy leaves one more `.bak-<fecha>` next to the
program, and nothing ever removes an old one — over enough small changes that adds up to nothing
that matters for the server, but to no reason to let it run forever either. Trim to the twenty most
recent after installing:

```bash
ssh <vps> "cd /opt/simpletv-admin && ls -t simpletv-admin.py.bak-* | tail -n +21 | xargs -r sudo rm --"
```

### Adding someone who can log in

```bash
printf '%s:%s\n' user "$(openssl passwd -apr1)" | sudo tee -a /etc/nginx/simpletv-admin.htpasswd
sudo systemctl reload nginx
```

## The threat model, briefly

Whoever has the APK can extract the URL from it, and whoever has the URL has the account. This does
not protect against that and cannot: there is no login, the URL *is* the credential, and hence the
random segment and `autoindex off`.

It is still a clear improvement on what it replaces. Credentials living in a file are rotated in ten
seconds; credentials compiled into an APK sitting in someone else's living room are never rotated at
all.

**Since `apk`, this VPS controls code, not only credentials.** It already decided which account and
which channels a television has; now it can decide what software runs on it, on any device made its
owner. That is accepted rather than incidental — the VPS is yours and so are the televisions — but it
is worth saying plainly, because it is a materially different amount of trust from everything above
it on this page.

## When something does not add up

The app is written so that a failure here breaks nothing — an unreadable file is ignored and the
last cached account is used — but that means **a syntax error looks a lot like nothing happening**.
If you edit by hand on the server, validate:

```bash
python3 -m json.tool /srv/videoclub/<segment>/provider.json
```

And to see what the television decided, `./deploy.sh --casa <id> --logs` and look for
`ProviderSettings` (it adopted a new account), `RemoteConfigClient` (it never managed to read it) or
`ProviderOverrides` (it arrived, but was not JSON).
