#!/usr/bin/env bash
# First install on a device: unlock the bootloader, set our verified boot key, flash the
# factory image, lock the bootloader again. Wipes the device. Later builds arrive over
# the air and never need this.
#
#   scripts/flash.sh releases/<device>-factory-<build>.zip
#
# Requires the phone in fastboot mode (power + volume down) with OEM unlocking enabled
# in developer settings beforehand.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

ZIP="${1:-}"
[[ -f "$ZIP" ]] || die "usage: flash.sh <factory zip>"
need fastboot
[[ -f "$BUDDY_KEYS/avb_pkmd.bin" ]] || die "no avb_pkmd.bin in $BUDDY_KEYS"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$ZIP" -d "$WORK"
DIR="$(find "$WORK" -maxdepth 1 -mindepth 1 -type d | head -n1)"
[[ -x "$DIR/flash-all.sh" ]] || die "factory zip has no flash-all.sh"

log "waiting for a device in fastboot"
fastboot devices | grep -q . || die "no device in fastboot mode"

read -r -p "This wipes the phone. Type the device codename ($BUDDY_DEVICE) to continue: " CONFIRM
[[ "$CONFIRM" == "$BUDDY_DEVICE" ]] || die "aborted"

log "unlocking bootloader (confirm on the phone)"
fastboot flashing unlock || true

log "installing our verified boot key"
fastboot erase avb_custom_key
fastboot flash avb_custom_key "$BUDDY_KEYS/avb_pkmd.bin"

log "flashing"
(cd "$DIR" && ./flash-all.sh)

log "locking bootloader (confirm on the phone)"
fastboot flashing lock

log "done. On first boot: disable OEM unlocking in developer settings, then run scripts/battery-baseline.sh."
