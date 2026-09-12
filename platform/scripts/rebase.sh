#!/usr/bin/env bash
# Monthly rebase onto a new GrapheneOS release tag: re-sync, re-apply the patch set,
# rebuild. Run on the release that carries the month's Android security bulletin;
# ignore the interim ones.
#
#   scripts/rebase.sh 2026091200
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TAG="${1:-}"
[[ -n "$TAG" ]] || die "usage: rebase.sh <grapheneos-release-tag>"

"$BUDDY_SRC/platform/scripts/sync.sh" "$TAG"

cd "$BUDDY_TREE"
PATCHES="$BUDDY_SRC/platform/patches"
shopt -s nullglob
failed=0
for patch in "$PATCHES"/*.patch; do
    # Patch files are named NNNN-<project path with __ for />-<name>.patch
    base="$(basename "$patch" .patch)"
    project="$(echo "$base" | cut -d- -f2 | sed 's#__#/#g')"
    [[ -d "$project" ]] || { log "SKIP $base: no project dir $project"; continue; }
    log "applying $base to $project"
    if ! (cd "$project" && git am --3way "$patch"); then
        log "FAILED $base; resolve in $project then 'git am --continue'"
        failed=1
        break
    fi
done
[[ $failed -eq 0 ]] || die "patch application stopped; fix and rerun build.sh"

log "patches applied; building"
"$BUDDY_SRC/platform/scripts/build.sh"
