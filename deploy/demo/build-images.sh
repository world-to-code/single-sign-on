#!/usr/bin/env bash
# Builds both images on THIS machine (the Gradle and npm builds need more memory than a 4 GB server has to
# spare) and packs them into one archive to copy to the server.
#
#   ./build-images.sh [platform]        # default linux/amd64; use linux/arm64 for an ARM server
set -euo pipefail
cd "$(dirname "$0")/../.."

platform="${1:-linux/amd64}"
docker buildx build --platform "$platform" --load -t svalinn-backend:demo sso-backend
docker buildx build --platform "$platform" --load -t svalinn-frontend:demo sso-frontend
docker save svalinn-backend:demo svalinn-frontend:demo | gzip > deploy/demo/svalinn-images.tar.gz
ls -lh deploy/demo/svalinn-images.tar.gz
