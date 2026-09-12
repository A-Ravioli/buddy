#!/usr/bin/env bash
# Shared settings for the platform scripts. Source, do not run.

set -euo pipefail

BUDDY_DEVICE="${BUDDY_DEVICE:-mustang}"                      # Pixel 10 Pro XL
BUDDY_TREE="${BUDDY_TREE:-$HOME/grapheneos}"                  # the GrapheneOS checkout
BUDDY_REPO_URL="${BUDDY_REPO_URL:-https://github.com/A-Ravioli/buddy.git}"
BUDDY_MANIFEST_URL="${BUDDY_MANIFEST_URL:-https://github.com/GrapheneOS/platform_manifest.git}"
BUDDY_KEYS="${BUDDY_KEYS:-$BUDDY_TREE/keys/$BUDDY_DEVICE}"
BUDDY_RELEASES="${BUDDY_RELEASES:-$BUDDY_TREE/releases}"
BUDDY_JOBS="${BUDDY_JOBS:-$(nproc)}"

# The directory this repository lives in, whether run from a plain clone or from
# vendor/buddy inside the tree.
BUDDY_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

log() { printf '\033[1;34m[buddy]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[buddy]\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"; }
