# Installing the app on a television

There are two ways to change what a household does, and they cover different things.

**Data changes** — the supplier account expired, a person needs adding, simple mode needs switching
on, a channel needs sending. These are made from the panel and the app picks them up on its own: at
launch, when the television is switched on, and every two minutes while it is open. **Nothing needs
building and nobody needs to touch the device**, and this is what you will be doing 90% of the time.
See [server/README.md](server/README.md).

**Code changes** — a player bug, a new channel in the curation, a new screen. These need an APK, and
that is what this file documents.

---

## The normal case: `./deploy.sh`

```bash
./deploy.sh --casa papa              # build, install, restart
./deploy.sh --casa papa --logs       # the app's logcat, live
./deploy.sh --casa papa --no-build   # install whatever is already in build-out/
```

The transport is ADB over Tailscale, because the boxes sit behind someone else's router and there is
no port to open. Each household's television is declared separately in `local.properties`
(`casa.<id>.tv.adb.host`) and **is not inherited**: inheriting it means one absent-minded `--casa`
installs onto your father's television, and no error message afterwards fixes that.

To test on a device of your own without touching anybody else's:

```bash
./deploy.sh --casa papa --host my-tablet
```

## Setting up a box for the first time, with the television in front of you

Steps 1–3 need the screen at hand; from 4 onwards everything is done from here. Set aside half an
hour on the next visit.

### 1. Developer options

Settings → Device information → tap **Build number** seven times. Go back, enter **Developer
options** and enable **USB debugging** and **Network debugging** (or *Wireless debugging*). Note
what appears on screen: AOSP-style boxes listen on port **5555**, while Android 11+ with *Wireless
debugging* requires pairing first, with a port and a six-digit code.

### 2. Tailscale

Install it on the television and sign in. In the Tailscale console, rename that machine to something
stable — `videoclub-salon` — and **disable key expiry**. Otherwise the television drops off the
tailnet by itself in a few months and you need another visit.

### 3. The RSA fingerprint

With the television still in front of you:

```bash
./deploy.sh --pair <host:port> <code>   # Android 11+ only
./deploy.sh --casa <id>
```

A dialog appears on the television with this laptop's fingerprint. Tick **Always allow from this
computer**. That "always" is what means you never have to come back.

### 4. The signature

If that box holds a build signed with a different key — for instance one compiled before the release
keystore existed — Android **will not let it update**. It has to be uninstalled once:

```bash
adb -s <host>:5555 uninstall com.videoclub.app
./deploy.sh --casa <id>
```

Uninstalling hurts less than it sounds: progress and "My list" live on the VPS and come back on
reinstall, and the catalogue rebuilds itself. You lose the local cache and little else.

From then on every update is direct, and forever — **as long as you still have
`keystore/videoclub-release.p12` and its password**.

## When it fails

**It will not connect.** In order: `tailscale status` to see whether the television is online; then
developer options. Many boxes **turn network debugging off when they reboot** — that is the most
common cause and the real weakness of this route. After a power cut, somebody in that house has to
flip the switch again.

**`unauthorized`.** The RSA authorisation was cleared. The on-screen dialog has to be accepted
again.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** Step 4 is missing: there is a build installed with a
different signature.

**`INSTALL_FAILED_VERSION_DOWNGRADE`.** The `versionCode` comes from the date, so this only happens
if this machine's clock is behind the one that built what is installed.

## What you must keep

`keystore/videoclub-release.p12` and its password. Neither is in the repository. If this laptop's
disk dies without a copy, the only way to update a television again is to go and uninstall the app
by hand, in each household's living room.
