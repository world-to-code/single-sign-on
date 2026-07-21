#!/usr/bin/env bash
# Re-encrypt every committed secret to the CURRENT recipient list in .sops.yaml.
#
# Run this after adding or removing a recipient. Editing .sops.yaml alone changes nothing: the files stay
# encrypted to whoever they were encrypted to, so a new operator still cannot read them and a departed one
# still can.
#
# Removing someone is NOT revocation. They may already hold the plaintext, and they can decrypt any older
# commit. A departure means rotating the SECRETS — new database password, new crypto master, new keystore
# password — not just re-keying the file.
set -euo pipefail

cd "$(dirname "$0")/.."

command -v sops >/dev/null || { echo "sops is not installed — see docs/sops.md" >&2; exit 1; }

shopt -s nullglob
found=0
for f in secrets/*.sops.yaml secrets/*.sops.yml; do
  found=$((found + 1))
  echo "re-keying $f"
  sops updatekeys --yes "$f"
done

if [ "$found" -eq 0 ]; then
  echo "No secrets/*.sops.yaml to re-key yet."
  exit 0
fi

echo
echo "Done. Commit the result — the ciphertext changed, the plaintext did not."
