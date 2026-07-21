#!/usr/bin/env bash
# Generate this operator's age key and print the PUBLIC half to paste into .sops.yaml.
#
# Run this yourself. Nobody else should generate your key: the private half is the root of trust for every
# production secret in this repo, and it must never exist anywhere but the machine that will use it.
set -euo pipefail

KEY="${SOPS_AGE_KEY_FILE:-$HOME/.config/sops/age/keys.txt}"

command -v age-keygen >/dev/null || {
  echo "age is not installed. Debian/Ubuntu: sudo apt-get install -y age  |  macOS: brew install age" >&2
  exit 1
}

if [ -e "$KEY" ]; then
  echo "A key already exists at $KEY — not overwriting it."
  echo "Overwriting would lock you out of every file currently encrypted to it."
else
  mkdir -p "$(dirname "$KEY")"
  # 0600 before anything is written: created world-readable and fixed afterwards is a window, not a mistake
  # you get to notice.
  (umask 077 && age-keygen -o "$KEY" 2>/dev/null)
  echo "Created $KEY (mode 0600). Back it up somewhere you control — it cannot be regenerated."
fi

echo
echo "Your PUBLIC key — safe to share, paste it into .sops.yaml under age::"
age-keygen -y "$KEY"
echo
echo "Then have an EXISTING recipient run scripts/sops-rekey.sh so the committed files can be read by you."
