#!/usr/bin/env bash
# Checks the managed Valkey before the first start. Logout propagation (OIDC Back-Channel Logout, SAML SLO) is
# driven by keyspace notifications: a deleted or expired session must emit an event, or nothing downstream is
# ever told the user signed out. Managed Valkey often disables CONFIG, so instead of reading the setting this
# subscribes to the event channels and deletes a probe key - the only test that answers the real question.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
: "${VALKEY_HOST:?fill in VALKEY_HOST in .env}" "${VALKEY_PORT:?}" "${VALKEY_PASSWORD:?}"

tls_flag=(--tls --insecure)
[[ "${VALKEY_TLS:-true}" == "false" ]] && tls_flag=()
net_flag=()
name_flag=()
[[ "$VALKEY_HOST" == "valkey" ]] && net_flag=(--network "$(basename "$PWD")_default")

cli() {
  docker run --rm -i "${name_flag[@]}" "${net_flag[@]}" valkey/valkey:8 valkey-cli "${tls_flag[@]}" -h "$VALKEY_HOST" -p "$VALKEY_PORT" \
    --user "${VALKEY_USER:-default}" --pass "$VALKEY_PASSWORD" --no-auth-warning "$@"
}

echo "== ping"
cli PING

echo "== probe: subscribe to keyevent notifications, then delete a key"
probe="svalinn:keyspace-probe:$RANDOM"
events="$(mktemp)"
subscriber="svalinn-keyspace-probe-$$"
trap 'docker rm -f "$subscriber" > /dev/null 2>&1 || true; rm -f "$events"' EXIT
name_flag=(--name "$subscriber")
cli --csv PSUBSCRIBE '__keyevent@*__:*' > "$events" 2>&1 &
sub_pid=$!
name_flag=()
sleep 4
cli SET "$probe" 1 > /dev/null
cli DEL "$probe" > /dev/null
sleep 3
# Killing the docker client does not stop the container it started; remove the container itself.
docker rm -f "$subscriber" > /dev/null 2>&1 || true
wait "$sub_pid" 2> /dev/null || true

if grep -q "$probe" "$events"; then
  echo "RESULT: keyspace notifications are ON - logout propagation will work."
  echo "        Keep VALKEY_CONFIGURE_KEYSPACE_EVENTS=false (CONFIG is not needed)."
else
  echo "RESULT: no event arrived - keyspace notifications are OFF on this Valkey."
  echo "        Sign-in works, but logout will NOT propagate to connected apps."
  echo "        Use the local Valkey container instead (README: 'If keyspace notifications are off')."
fi
