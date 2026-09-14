#!/usr/bin/env bash
# Run ON THE SERVER from the copied deploy/demo directory: loads the images (if the archive is present) and
# starts or updates the stack. Run ./setup-db.sh once before the first start.
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "missing .env - run ./gen-env.sh <public-ip> <acme-email> first" >&2
  exit 1
fi
set -a; . ./.env; set +a
for var in DB_HOST DB_PORT DB_ADMIN_PASSWORD VALKEY_HOST VALKEY_PORT VALKEY_PASSWORD; do
  if [[ -z "${!var:-}" ]]; then
    echo "$var is empty in .env - copy it from the Vultr console" >&2
    exit 1
  fi
done

if [[ -f svalinn-images.tar.gz ]]; then
  gunzip -c svalinn-images.tar.gz | docker load
fi

compose=(docker compose -f docker-compose.demo.yml)
[[ "$VALKEY_HOST" == "valkey" ]] && compose+=(--profile local-valkey)
"${compose[@]}" up -d
"${compose[@]}" ps

echo
echo "waiting for the backend (the first start runs the migrations)..."
for _ in $(seq 1 60); do
  if curl -fsS "https://${DEMO_HOST}/.well-known/openid-configuration" > /dev/null 2>&1; then
    echo "up: https://${DEMO_HOST}   tenant: https://${DEMO_TENANT}.${DEMO_HOST}"
    exit 0
  fi
  sleep 5
done
echo "not reachable yet - check: docker compose -f docker-compose.demo.yml logs backend caddy" >&2
exit 1
