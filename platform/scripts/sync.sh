#!/usr/bin/env bash
# Sync the GrapheneOS tree at a release tag and add this repository as vendor/buddy.
#
#   scripts/sync.sh 2026081300
#
# Picks the tag from https://grapheneos.org/releases for mustang. Verifies the signed
# tag before trusting it.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TAG="${1:-}"
[[ -n "$TAG" ]] || die "usage: sync.sh <grapheneos-release-tag>"
need repo
need git

mkdir -p "$BUDDY_TREE"
cd "$BUDDY_TREE"

log "repo init at tag $TAG"
repo init -u "$BUDDY_MANIFEST_URL" -b "refs/tags/$TAG" --depth=1

log "verifying the signed tag"
# GrapheneOS signs manifest tags with an SSH key published as allowed_signers in the
# manifest repo. Verify with the copy from the freshly fetched manifest.
(
    cd .repo/manifests
    if [[ -f allowed_signers ]]; then
        git -c gpg.ssh.allowedSignersFile=allowed_signers verify-tag "$TAG" || die "tag $TAG failed signature verification"
    else
        log "WARNING: no allowed_signers in manifest repo; verify the tag manually per grapheneos.org/build"
    fi
)

log "adding the buddy local manifest"
mkdir -p .repo/local_manifests
cp "$BUDDY_SRC/platform/manifest/buddy.xml" .repo/local_manifests/buddy.xml

log "repo sync (-j$BUDDY_JOBS); this takes a while the first time"
repo sync -j"$BUDDY_JOBS" --force-sync --no-clone-bundle --no-tags

echo "$TAG" > .buddy-base-tag

log "sync complete. Things to verify against this tag before building:"
for f in \
    "device/google/$BUDDY_DEVICE" \
    "frameworks/base/core/res/res/values/config.xml" \
    "packages/apps/Updater/res/values/config.xml"; do
    if [[ -e "$f" ]]; then echo "  ok      $f"; else echo "  MISSING $f"; fi
done
echo "  grep -n 'config_defaultContentCaptureService\|config_defaultAssistant\|config_defaultSms\|config_defaultDialer\|config_defaultCallScreening\|config_defaultListenerAccessPackages' frameworks/base/core/res/res/values/config.xml"
echo "  ls device/google/$BUDDY_DEVICE/*.mk   # confirm the product makefile inherited by vendor/buddy/platform/product/buddy_$BUDDY_DEVICE.mk"
