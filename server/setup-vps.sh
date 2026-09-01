#!/usr/bin/env bash
#
# Prepare this VPS to host the SimpleTV provider config.
#
#   ./setup-vps.sh                  inspect only: report what it WOULD do
#   ./setup-vps.sh --apply          do it
#   ./setup-vps.sh --nginx <dom>    same, for a public vhost instead of the tailnet
#
# This machine is assumed to be running other things that matter. The script is
# therefore built around three rules:
#
#   1. It does nothing at all without --apply. The default is a report.
#   2. It never writes over an existing file without copying it aside first.
#   3. It refuses, rather than guesses, whenever it finds something already
#      holding the ground it wants — a vhost for the domain, a serve path.
#
# It lives in its own directory, /srv/simpletv, and does not touch /var/www.
#
set -euo pipefail

MODE=tailscale
DOMAIN=
APPLY=0
DIR=/srv/simpletv
STAMP=/etc/simpletv-path

die()   { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note()  { printf '\033[36m→ %s\033[0m\n' "$*"; }
would() { printf '  \033[33m[haría]\033[0m %s\n' "$*"; }
did()   { printf '  \033[32m[hecho]\033[0m %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) APPLY=1; shift ;;
        --nginx) MODE=nginx; DOMAIN="${2:-}"; [[ -n "$DOMAIN" ]] || die "Uso: --nginx <dominio>"; shift 2 ;;
        *) die "Opción desconocida: $1" ;;
    esac
done

[[ $EUID -eq 0 ]] || die "Necesita root para leer la configuración de nginx. Usa sudo."

# `run` is the only thing in this script that changes the machine. Everything
# else reports. Keeping that in one place is what makes the dry run trustworthy.
run() {
    if [[ $APPLY -eq 1 ]]; then
        eval "$1" && did "$2"
    else
        would "$2"
    fi
}

backup() {
    local f=$1
    [[ -e $f ]] || return 0
    local b="$f.pre-simpletv.$(date +%Y%m%d%H%M%S)"
    run "cp -a '$f' '$b'" "copia de seguridad: $b"
}

printf '\n\033[1mSimpleTV — preparación del VPS (%s)\033[0m\n\n' \
    "$([[ $APPLY -eq 1 ]] && echo "APLICANDO" || echo "sólo inspección")"

# --- what is already here ---------------------------------------------------

note "Estado actual de la máquina"

if [[ -d $DIR ]]; then
    echo "  $DIR ya existe — se reutiliza, no se cambian sus permisos"
else
    echo "  $DIR no existe todavía"
fi

if [[ -f $STAMP ]]; then
    TOKEN=$(cat "$STAMP")
    echo "  Ruta secreta ya generada — NO se regenera (está compilada en el APK de la tele)"
else
    TOKEN=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
    echo "  Ruta secreta: se generará una nueva"
fi

FILE="$DIR/$TOKEN/provider.json"

# --- the files --------------------------------------------------------------

echo
note "Ficheros"

[[ -d $DIR ]]           || run "install -d -m 755 '$DIR'" "crear $DIR"
OWNER="${SUDO_USER:-root}"
[[ -d $DIR/$TOKEN ]]    || run "install -d -m 755 -o '$OWNER' '$DIR/$TOKEN'" "crear $DIR/$TOKEN (de $OWNER, para que push-config.sh escriba sin sudo)"
[[ -f $STAMP ]]         || run "printf '%s' '$TOKEN' > '$STAMP' && chmod 600 '$STAMP'" "guardar la ruta en $STAMP"

if [[ -f $FILE ]]; then
    echo "  $FILE ya existe — se deja intacto"
else
    # `{}` overrides nothing: valid, inert, and safe to leave forever. The
    # television keeps using the account inside its APK until something real
    # goes in here.
    run "printf '{}\n' > '$FILE' && chmod 644 '$FILE' && chown '$OWNER' '$FILE'" "crear $FILE con {} (no anula nada)"
fi

# --- serving ----------------------------------------------------------------

echo
note "Publicación"

if [[ $MODE == tailscale ]]; then
    command -v tailscale >/dev/null || die "Tailscale no está instalado aquí.
Instálalo tú (el script no toca paquetes del sistema):

    curl -fsSL https://tailscale.com/install.sh | sh && tailscale up"

    tailscale status >/dev/null 2>&1 || die "Tailscale instalado pero sin conectar: tailscale up"

    HOST=$(tailscale status --json | grep -o '"DNSName":"[^"]*"' | head -1 | cut -d'"' -f4)
    HOST=${HOST%.}
    [[ -n "$HOST" ]] || die "No consigo leer el nombre MagicDNS de esta máquina."

    EXISTING=$(tailscale serve status 2>/dev/null || true)
    if [[ -n "$EXISTING" ]]; then
        echo "  Ya hay cosas publicadas con 'tailscale serve' en esta máquina:"
        printf '%s\n' "$EXISTING" | sed 's/^/      /'
        echo
        if printf '%s' "$EXISTING" | grep -q "/$TOKEN/provider.json"; then
            echo "  La ruta de SimpleTV ya está publicada. No hay nada que hacer."
            SERVE_NEEDED=0
        else
            echo "  Se AÑADE una ruta nueva. Lo de arriba no se toca: --set-path"
            echo "  añade un manejador, no reemplaza la configuración."
            SERVE_NEEDED=1
        fi
    else
        echo "  No hay nada publicado con 'tailscale serve' todavía."
        SERVE_NEEDED=1
    fi

    if [[ ${SERVE_NEEDED:-1} -eq 1 ]]; then
        # `serve`, never `funnel`: funnel publishes to the open internet, which
        # is the one thing this mode exists to avoid.
        run "tailscale serve --bg --https=443 --set-path='/$TOKEN/provider.json' '$FILE'" \
            "publicar /$TOKEN/provider.json en el tailnet"
    fi

    URL="https://$HOST/$TOKEN/provider.json"

else
    command -v nginx >/dev/null || die "nginx no está instalado."

    # Refuse rather than collide. Two server blocks answering the same name on
    # the same port is a configuration whose behaviour depends on file ordering,
    # and this VPS has other sites on it.
    CONFLICT=$(grep -rl "server_name.*\b$DOMAIN\b" /etc/nginx/ 2>/dev/null | grep -v simpletv || true)
    if [[ -n "$CONFLICT" ]]; then
        echo "  Ya hay un vhost para $DOMAIN:"
        printf '%s\n' "$CONFLICT" | sed 's/^/      /'
        printf '\n  \033[33mNo voy a tocarlo.\033[0m Añade tú este bloque dentro de ese server {}:\n'
        cat <<EOF

      location /simpletv/ {
          alias $DIR/;
          autoindex off;              # obligatorio: un índice regala la ruta secreta
          add_header Cache-Control "no-store";
      }

  Después: nginx -t && systemctl reload nginx

  La URL para local.properties será:
      https://$DOMAIN/simpletv/$TOKEN/provider.json
EOF
        exit 0
    fi

    [[ -d /etc/letsencrypt/live/$DOMAIN ]] || die "Falta el certificado para $DOMAIN.
Sácalo tú primero (el script no ejecuta certbot sobre un servidor con otros
sitios en marcha):

    certbot certonly --nginx -d $DOMAIN"

    CONF=/etc/nginx/conf.d/simpletv.conf
    backup "$CONF"
    run "cat > '$CONF' <<'EOF'
server {
    listen 443 ssl;
    server_name $DOMAIN;

    ssl_certificate     /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;

    location /simpletv/ {
        alias $DIR/;
        autoindex off;
        add_header Cache-Control \"no-store\";
    }
}
EOF" "escribir $CONF"

    if [[ $APPLY -eq 1 ]]; then
        # Tested before reloading, and a failed reload leaves the running nginx
        # on its previous configuration: the other sites on this box stay up
        # even if this block is wrong.
        nginx -t || die "La configuración de nginx no valida. Los otros sitios siguen
sirviendo con la configuración anterior. Revisa $CONF (hay copia .pre-simpletv.*)."
        run "systemctl reload nginx" "recargar nginx"
    else
        would "nginx -t && systemctl reload nginx"
    fi

    URL="https://$DOMAIN/simpletv/$TOKEN/provider.json"
fi

# --- verify -----------------------------------------------------------------

echo
if [[ $APPLY -eq 1 ]]; then
    note "Comprobando $URL"
    if BODY=$(curl -sf --max-time 10 "$URL" 2>/dev/null) && \
       printf '%s' "$BODY" | python3 -m json.tool >/dev/null 2>&1; then
        printf '\033[32m✓ Sirviendo JSON válido\033[0m\n'
    else
        printf '\033[33m⚠ No he podido comprobarlo desde el propio VPS.\033[0m\n'
        [[ $MODE == tailscale ]] && echo "  Normal en modo tailnet: pruébalo desde otra máquina del tailnet."
    fi
else
    printf '\033[1mNo se ha cambiado nada.\033[0m Repite con --apply para aplicarlo.\n'
fi

cat <<EOF

────────────────────────────────────────────────────────────────────────
Para local.properties, en el portátil:

  remoteConfig.url=$URL
  vps.ssh=${SUDO_USER:-root}@$(hostname -f 2>/dev/null || hostname)
  vps.configPath=$FILE

Fichero a editar en este VPS:  $FILE
────────────────────────────────────────────────────────────────────────
EOF
