#!/usr/bin/env bash
#
# Compila el descodificador FFmpeg de Media3 y lo deja en app/libs.
#
#   ./build-ffmpeg-decoder.sh
#
# Ningún móvil ni ninguna tablet trae descodificador de DTS ni de AC3: son formatos con licencia y
# AOSP no los incluye. El catálogo del proveedor, en cambio, es AC3, E-AC3 y DTS casi entero, así
# que sin esto la app reproduce el vídeo y no saca sonido — y sin dar ningún error, porque ExoPlayer
# se limita a no activar el renderer de audio. Los aparatos de televisión sí traen los suyos y los
# siguen usando: `EXTENSION_RENDERER_MODE_ON` pone éste detrás, no delante.
#
# Google no publica este .aar en Maven, hay que compilarlo. Tarda un rato largo la primera vez
# (clona androidx/media y FFmpeg, y compila FFmpeg para cuatro arquitecturas), y sólo hay que
# relanzarlo al subir de versión de Media3 o al querer más formatos.
#
set -euo pipefail
cd "$(dirname "$0")"

die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[36m→ %s\033[0m\n' "$*"; }

# La versión sale de libs.versions.toml a propósito: el .aar y el `media3-exoplayer` con el que se
# enlaza tienen que ser la misma, y una versión escrita aquí a mano se queda vieja sin avisar.
MEDIA3=$(sed -n 's/^media3 *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)
[[ -n "$MEDIA3" ]] || die "No he sabido leer la versión de media3 en gradle/libs.versions.toml."

# La rama de FFmpeg que recomienda el README del módulo para esta serie de Media3.
FFMPEG_BRANCH="release/6.0"

# Lo que hay en el catálogo y el sistema no sabe descodificar, más lo barato de incluir.
DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac aac_latm ac3 eac3 dca mlp truehd)

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
[[ -d "$ANDROID_HOME" ]] || die "No encuentro el SDK de Android en $ANDROID_HOME."
[[ -n "${JAVA_HOME:-}" ]] || die "Falta JAVA_HOME (JDK 17)."

NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
[[ -n "$NDK" ]] || die "Falta el NDK. Instálalo con:

  sdkmanager 'ndk;27.3.13750724' 'cmake;3.22.1'"

# Fuera del repositorio: son ~2 GB de fuentes y objetos intermedios que no pintan nada aquí.
WORK="${FFMPEG_WORKDIR:-${TMPDIR:-/tmp}/videoclub-ffmpeg}"
mkdir -p "$WORK"

if [[ ! -d "$WORK/media" ]]; then
  note "Clonando androidx/media $MEDIA3"
  git clone -q -b "$MEDIA3" --depth 1 https://github.com/androidx/media.git "$WORK/media"
fi
if [[ ! -d "$WORK/ffmpeg" ]]; then
  note "Clonando FFmpeg $FFMPEG_BRANCH"
  git clone -q -b "$FFMPEG_BRANCH" --depth 1 https://github.com/FFmpeg/FFmpeg.git "$WORK/ffmpeg"
fi

MODULE="$WORK/media/libraries/decoder_ffmpeg/src/main"
ln -sfn "$WORK/ffmpeg" "$MODULE/jni/ffmpeg"

if [[ ! -d "$MODULE/jni/ffmpeg/android-libs/arm64-v8a" ]]; then
  note "Compilando FFmpeg (esto tarda)"
  "$MODULE/jni/build_ffmpeg.sh" "$MODULE" "$NDK" linux-x86_64 25 "${DECODERS[@]}"
fi

note "Compilando el .aar"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WORK/media/local.properties"
(cd "$WORK/media" && ./gradlew --no-daemon -q :lib-decoder-ffmpeg:assembleRelease)

AAR=$(find "$WORK/media/libraries/decoder_ffmpeg" -name '*-release.aar' | head -1)
[[ -n "$AAR" ]] || die "La compilación no ha dejado ningún .aar."

mkdir -p app/libs
cp "$AAR" app/libs/media3-decoder-ffmpeg.aar
note "app/libs/media3-decoder-ffmpeg.aar ($(du -h app/libs/media3-decoder-ffmpeg.aar | cut -f1))"
