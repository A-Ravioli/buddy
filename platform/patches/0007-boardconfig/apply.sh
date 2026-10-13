#!/usr/bin/env bash
# Patch 0007: hook buddy's SELinux additions into the device's board config.
#
#   patches/0007-boardconfig/apply.sh
#
# One line appended to the BoardConfig the device builds with. Safe to re-run; build.sh
# runs it before every platform build. A script rather than a .patch because where that
# BoardConfig sits depends on the device tree: mustang has its own, while panther shares
# pantah with cheetah and each has a BoardConfig of its own inside it.
source "$(dirname "${BASH_SOURCE[0]}")/../../scripts/common.sh"

LINE='-include vendor/buddy/platform/product/BoardConfigBuddy.mk'
DIR="$BUDDY_TREE/$BUDDY_DEVICE_TREE"
[[ -d "$DIR" ]] || die "no device tree at $BUDDY_DEVICE_TREE; run sync.sh first"

# Most specific first: the device's own BoardConfig inside a shared tree, then the tree's.
TARGET=""
for f in "$DIR/$BUDDY_DEVICE/BoardConfig.mk" "$DIR/BoardConfig-$BUDDY_DEVICE.mk" "$DIR/BoardConfig.mk"; do
    [[ -f "$f" ]] && { TARGET="$f"; break; }
done
if [[ -z "$TARGET" ]]; then
    log "no BoardConfig found for $BUDDY_DEVICE. Look with:"
    log "  find $BUDDY_DEVICE_TREE -name 'BoardConfig*.mk'"
    die "nothing was written"
fi

if grep -qxF -- "$LINE" "$TARGET"; then
    log "0007 already applied: ${TARGET#"$BUDDY_TREE"/}"
    exit 0
fi
printf '\n# buddy: SELinux additions (vendor/buddy/platform/patches/README.md, 0007)\n%s\n' "$LINE" >> "$TARGET"
log "0007 applied: ${TARGET#"$BUDDY_TREE"/}"
