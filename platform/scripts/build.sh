#!/usr/bin/env bash
# Build a signed buddy release:
#   1. the JVM tests
#   2. the platform build for buddy_mustang (which compiles the app with Soong)
#   3. signing and release packaging with GrapheneOS's scripts
#
#   scripts/build.sh                # full
#   scripts/build.sh --tests-only   # just the JVM tests
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

APK_ONLY=0
[[ "${1:-}" == "--tests-only" ]] && APK_ONLY=1

# ---- 1. JVM tests --------------------------------------------------------------
# The app itself is compiled by Soong from vendor/buddy (Android.bp at the repo root);
# the JVM modules are tested here first so a broken normaliser never reaches the phone.
log "running the JVM tests"
(cd "$BUDDY_SRC" && ./gradlew --quiet :core:ledger:test :core:perception:test)
[[ $APK_ONLY -eq 1 ]] && exit 0

# ---- 1b. the boot animation --------------------------------------------------------
# Drawn from the same face geometry as the app, so it cannot drift from the creature it
# shows. The product makefile copies the zip from vendor/buddy into the image.
log "drawing the boot animation"
(cd "$BUDDY_SRC" && ./gradlew --quiet :platform:bootanimation:bootAnimation)

# ---- 2. platform build -------------------------------------------------------------
cd "$BUDDY_TREE" || die "no tree at $BUDDY_TREE; run sync.sh first"
[[ -f .buddy-base-tag ]] || die "tree has no .buddy-base-tag; run sync.sh"
export BUDDY_BASE_TAG="$(cat .buddy-base-tag)"
export BUILD_NUMBER="${BUILD_NUMBER:-$(date -u +%Y%m%d%H)}"
export OFFICIAL_BUILD=false

# Keep vendor/buddy pointing at the same commit as this checkout.
if [[ -d vendor/buddy/.git ]]; then
    (cd vendor/buddy && git fetch -q origin && git checkout -q "$(cd "$BUDDY_SRC" && git rev-parse HEAD)") \
        || log "WARNING: could not sync vendor/buddy to this checkout's commit"
fi

# The animation is generated, so it is not in the commit vendor/buddy was just checked out
# to. Copy it across; the product makefile picks it up from there.
if [[ -f "$BUDDY_SRC/platform/bootanimation/build/bootanimation.zip" ]]; then
    mkdir -p vendor/buddy/platform/bootanimation/build
    cp "$BUDDY_SRC/platform/bootanimation/build/bootanimation.zip" vendor/buddy/platform/bootanimation/build/
else
    log "WARNING: no boot animation; the image will show the base one"
fi

log "platform build: buddy_${BUDDY_DEVICE}-cur-user, BUILD_NUMBER=$BUILD_NUMBER"
# shellcheck disable=SC1091
source build/envsetup.sh
lunch "buddy_${BUDDY_DEVICE}-cur-user"
m -j"$BUDDY_JOBS" vendorbootimage vendorkernelbootimage target-files-package
m -j"$BUDDY_JOBS" otatools-package

# ---- 3. sign and package ---------------------------------------------------------
[[ -f "$BUDDY_KEYS/avb.pem" ]] || die "no keys in $BUDDY_KEYS; run keys.sh"
log "signing and packaging release $BUILD_NUMBER"
script/finalize.sh
script/generate-release.sh "$BUDDY_DEVICE" "$BUILD_NUMBER"

mkdir -p "$BUDDY_RELEASES"
cp -v "releases/$BUILD_NUMBER/release-$BUDDY_DEVICE-$BUILD_NUMBER/"*.zip "$BUDDY_RELEASES/" 2>/dev/null || true
cp -v "releases/$BUILD_NUMBER/$BUDDY_DEVICE-stable" "$BUDDY_RELEASES/" 2>/dev/null || true
log "release in $BUDDY_RELEASES (factory zip for flash.sh, OTA zip and channel file for the update server)"
