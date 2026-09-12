#!/usr/bin/env bash
# Screen-off drain baseline. Leaves the phone alone for a set time with the screen off
# and reports the average drain, so every build can be compared to the last.
#
#   scripts/battery-baseline.sh [minutes]     # default 120
#
# Run with the phone unplugged, unlocked once since boot (so the ledger is open), and
# buddy's perception running. Results are appended to platform/battery-baseline.log.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

MINUTES="${1:-120}"
need adb
adb get-state >/dev/null 2>&1 || die "no device over adb"

LOG="$BUDDY_SRC/platform/battery-baseline.log"
build="$(adb shell getprop ro.build.version.incremental | tr -d '\r')"
tag="$(adb shell getprop ro.buddy.build.tag | tr -d '\r')"

level() { adb shell dumpsys battery | awk '/level:/ {print $2}' | tr -d '\r'; }
charge_uah() { adb shell dumpsys battery | awk '/Charge counter:/ {print $3}' | tr -d '\r'; }
plugged() { adb shell dumpsys battery | awk '/plugged:/ {print $2}' | tr -d '\r'; }

[[ "$(plugged)" == "0" ]] || die "unplug the phone first"

adb shell dumpsys batterystats --reset >/dev/null
adb shell input keyevent KEYCODE_SLEEP
start_ts=$(date +%s); start_level=$(level); start_uah=$(charge_uah)
log "start: ${start_level}% (${start_uah} uAh); sleeping ${MINUTES} min with screen off"
sleep $(( MINUTES * 60 ))
end_ts=$(date +%s); end_level=$(level); end_uah=$(charge_uah)

hours=$(awk -v s="$start_ts" -v e="$end_ts" 'BEGIN{printf "%.3f", (e-s)/3600}')
pct_per_h=$(awk -v a="$start_level" -v b="$end_level" -v h="$hours" 'BEGIN{printf "%.2f", (a-b)/h}')
mah_per_h=$(awk -v a="$start_uah" -v b="$end_uah" -v h="$hours" 'BEGIN{printf "%.1f", (a-b)/1000/h}')

line="$(date -u +%FT%TZ) build=$build tag=$tag minutes=$MINUTES drain=${pct_per_h}%/h ${mah_per_h}mAh/h"
echo "$line" | tee -a "$LOG"

log "top consumers (batterystats):"
adb shell dumpsys batterystats 2>/dev/null | grep -A 15 "Estimated power use" || true
adb shell dumpsys batterystats --checkin 2>/dev/null | grep -E ',(uid|pwi),' | sort -t, -k6 -nr | head -n 15 || true
