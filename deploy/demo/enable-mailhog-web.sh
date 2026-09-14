#!/usr/bin/env bash
# Publishes the MailHog UI at https://mail.<DEMO_HOST>, so emailed codes can be read without an SSH tunnel.
# MailHog holds every one-time code the IdP sends: anyone who can read the inbox can pass an email-code step.
#
#   ./enable-mailhog-web.sh            # enable behind basic auth (prints the generated password)
#   ./enable-mailhog-web.sh --public   # enable with NO authentication (demo only - codes are world-readable)
#   ./enable-mailhog-web.sh --disable  # remove the route again
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

compose=(docker compose -f docker-compose.demo.yml)
[[ "${VALKEY_HOST:-}" == "valkey" ]] && compose+=(--profile local-valkey)
site=caddy-sites/mailhog.caddy
mkdir -p caddy-sites

case "${1:-}" in
  --disable)
    rm -f "$site"
    "${compose[@]}" up -d --force-recreate caddy
    echo "MailHog web route removed"
    exit 0
    ;;
  --public)
    cat > "$site" <<'EOF'
mail.{$DEMO_HOST} {
	reverse_proxy mailhog:8025
}
EOF
    chmod 644 "$site"
    "${compose[@]}" up -d --force-recreate caddy
    echo
    echo "MailHog (public, no authentication): https://mail.${DEMO_HOST}"
    ;;
  *)
    password="$(openssl rand -base64 18 | tr -d '/+=\n')"
    hash="$(docker run --rm caddy:2 caddy hash-password --plaintext "$password")"
    cat > "$site" <<EOF
mail.{\$DEMO_HOST} {
	basic_auth {
		mailhog ${hash}
	}
	reverse_proxy mailhog:8025
}
EOF
    chmod 644 "$site"
    "${compose[@]}" up -d --force-recreate caddy
    echo
    echo "MailHog: https://mail.${DEMO_HOST}"
    echo "user:    mailhog"
    echo "pass:    ${password}"
    ;;
esac
echo "(the certificate for mail.${DEMO_HOST} is issued on first start; allow a minute)"
