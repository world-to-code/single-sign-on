#!/usr/bin/env bash
# Writes .env: generated secrets for this stack, plus blanks for the managed PostgreSQL / Valkey connection that
# you copy from the Vultr console. Refuses to overwrite an existing .env, because the database contents and the
# encrypted columns are bound to the secrets it was first started with.
#
#   ./gen-env.sh <public-ip> <acme-email> [tenant-slug]
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env ]]; then
  echo ".env already exists; it is bound to the database it first ran against." >&2
  exit 1
fi
if [[ $# -lt 2 ]]; then
  echo "usage: $0 <public-ip> <acme-email> [tenant-slug]" >&2
  exit 1
fi

ip="$1"
email="$2"
tenant="${3:-default}"
secret() { openssl rand -base64 "$1" | tr -d '/+=\n' | cut -c1-"$2"; }

umask 077
cat > .env <<EOF
# ---- public origin ----
DEMO_HOST=${ip//./-}.sslip.io
DEMO_TENANT=${tenant}
ACME_EMAIL=${email}

# ---- Vultr Managed PostgreSQL 17: copy from the database's Overview page ----
DB_HOST=
DB_PORT=
DB_ADMIN_USER=vultradmin
DB_ADMIN_PASSWORD=
DB_NAME=sso

# ---- Vultr Managed Valkey: copy from the database's Overview page ----
VALKEY_HOST=
VALKEY_PORT=
VALKEY_USER=default
VALKEY_PASSWORD=
# true only if ./check-valkey.sh reports that CONFIG SET is allowed
VALKEY_CONFIGURE_KEYSPACE_EVENTS=false

# ---- generated ----
DB_APP_PASSWORD=$(secret 48 32)
SSO_ADMIN_PASSWORD=$(secret 24 16)Aa1!
SSO_SAML_KEYSTORE_PASSWORD=$(secret 48 32)
SSO_CRYPTO_MASTER_PASSWORD=$(secret 48 32)
SSO_CRYPTO_SALT=$(openssl rand -hex 16)
EOF

echo "wrote .env (mode 600) - now fill in DB_* and VALKEY_* from the Vultr console"
echo "platform: https://${ip//./-}.sslip.io"
echo "tenant:   https://${tenant}.${ip//./-}.sslip.io"
