# Videoclub

A video shop and a live-television set for one family, spread across several households: an Android
app installed on each person's television, and a panel on a VPS from which the whole thing is run
without having to visit any of those houses.

---

> # ⚠️ THIS IS VIBE CODED ⚠️
>
> **Practically all of the code in this repository — the app, the panel, the scripts, the tests and
> this documentation — was written by a language model**, in conversation, over many sessions. There
> has been no line-by-line human review.
>
> What that means in practice:
>
> - **It works, and it has been tested on real devices**, but "tested" means somebody watched it
>   work, not that there is a safety net that will shout when it breaks.
> - **Some decisions look deliberate and are only half so.** The comments explain why each thing was
>   done; read them as intent, not as verified truth.
> - **Do not take this as a reference for how things are done.** It is a domestic project solving a
>   domestic problem.
> - **If you are going to touch it, read what you touch.** Especially the panel: other people's
>   televisions depend on it, and those people cannot fix it themselves.
>
> That said: it has been serving several households for months without anyone having to phone for
> help. It is worth what it is worth.

---

## What each piece is

```
app/                 The Android application (Kotlin + Compose, media3/ExoPlayer)
tests/e2e/           Tests against real devices, over ADB
deploy.sh            Build and install onto one household's television
publish.sh           Build the releases the named households need and publish them on the VPS
sync-casas.sh        Pull the simple households, the ones with an APK of their own, into local.properties
```

The panel and the VPS setup live in their own repository,
[videoclub-server](https://github.com/vicenteqa/videoclub-server) (private).

### The app

Two halves sharing one account and one connection:

- **The video shop**: the supplier's film and series catalogue, with a profile per person, and
  "Continue watching" and "My list" synchronised across the devices of the same household.
- **Live television**: a hand-curated channel list built from the ~2,000 streams the supplier
  carries, with a guide, a per-channel fallback chain and channel surfing.

And a **simple mode**: a household can be configured so that the app is only the television — it
starts already tuned in, with no tabs, no catalogue and no profile picker. It is for someone who
wants a television, not a menu. It is switched on with a checkbox in the panel.

### The panel

It lives on the VPS at `https://<host>/panel/`. From there you add a household, change its supplier
password, see what each television is watching, and — for simple households — send it a channel
remotely.

It is not a modern web application: it is one Python file using only the standard library, serving
HTML behind nginx. That is deliberate — the VPS has other things running on it, and this was not
going to add an ecosystem to maintain. Its code lives in
[videoclub-server](https://github.com/vicenteqa/videoclub-server).

## The idea holding it all up

**Nothing about the account is compiled into the APK.** The supplier's server, the username, the
password, the people in the household, whether it runs in simple mode: all of it lives in a JSON
document on the VPS, one per household, which the app reads at launch and every two minutes while
it is open.

The practical consequence is the one that matters: **changing a password is editing a file on a
server**, not driving to someone else's living room with a laptop.

Which document to read is the single thing two households do not share, and there are two ways an
APK learns it:

- **`general`, for every household that is not simple.** One APK. The first time it opens it asks
  for the household's username and password — set in the panel — and trades them at
  `/videoclub/login` for the document's URL, which it keeps. A device that already had the app keeps
  the URL its previous APK left behind, and asks nothing.
- **One APK per simple household**, with the URL compiled in, so that it goes straight to the
  television: Gradle generates a flavour for every `casa.<id>.remoteConfig.url` in
  `local.properties`, which `./sync-casas.sh` pulls from the panel for simple households only.

That URL **is the credential**: the document carries the password in the clear. Hence the random
path segment, the attempt limits on the login, and `local.properties` never being committed.

## Getting started

```bash
cp local.properties.example local.properties   # then fill it in
./build-ffmpeg-decoder.sh                      # the decoder, once
./sync-casas.sh                                # pull the simple households from the panel
./gradlew :app:assembleGeneralDebug            # or a simple household's flavour
```

You need **JDK 17** (the Android plugin rejects newer ones) and the Android SDK.

To install onto a television, see [DEPLOYMENT.md](DEPLOYMENT.md).
For the VPS, the hosted document and the panel, see
[videoclub-server](https://github.com/vicenteqa/videoclub-server).

## Signing

Release builds are signed with `keystore/videoclub-release.p12`, which is **not in the repository**.
Android only accepts an update signed with the same key as the installed APK, so losing that key
means never being able to update any television again without going there to uninstall by hand. The
file and its password belong in a password manager, not on this disk.

The `versionCode` is generated from the build date (`yyMMddHH`), because a counter you have to
remember to bump eventually gets forgotten — and that mistake is not visible at build time, it is
visible weeks later on a device that never updates.

## A note on language

The user-facing text — everything on screen, and everything in the panel — is in **Spanish**,
because the people using it are. So are the JSON field names in the hosted document (`canales`,
`perfiles`, `poner`), which are part of the wire format and cannot be renamed without breaking every
device already installed.

The documentation is in English. The code comments are mostly English in the Android app and mostly
Spanish in the panel and the end-to-end tests, which is honest about how it grew rather than tidy.

## Where this came from

This started as two projects: **SimpleTV**, an app that was only live television, and **Videoclub**,
which also carried the catalogue. They shared half their code in two copies, so every fix had to be
made twice — and once or twice it was only made once.

In September 2026 they were merged: Videoclub learned "simple mode" and SimpleTV was retired. The
panel comes from that project, which is why its file and its systemd service on the VPS are still
called `simpletv-admin`: renaming a service that six households depend on is a separate job, with
its own risk, and is not done in passing.
