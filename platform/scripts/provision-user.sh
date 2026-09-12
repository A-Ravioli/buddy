#!/usr/bin/env bash
# Provision a phone for a user after flashing: cloud key, mail account, profile seed,
# and the onboarding run. Everything lands in the app's credential-encrypted storage,
# so the phone must be unlocked once since boot and adb must be authorised.
#
#   scripts/provision-user.sh <provision dir>
#
# The provision dir holds, all optional:
#   api_key                 the Claude API key, one line
#   mail.properties         address=, password=, imap_host=, smtp_host= (defaults for Gmail)
#   triage.properties       emergency=, close=, muted=, muted_packages=, urgent_keywords=
#   policy.properties       level.<domain>=, known_payees=, never_contacts=, quiet_start=, quiet_end=, hold_minutes=
#
# Files not present are not touched; the onboarding screen infers the rest.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

DIR="${1:-}"
[[ -d "$DIR" ]] || die "usage: provision-user.sh <provision dir>"
need adb
adb get-state >/dev/null 2>&1 || die "no device over adb"
[[ "$(adb shell getprop ro.build.type | tr -d '\r')" == "userdebug" ]] || log "WARNING: run-as needs a userdebug build or a debuggable app"

APP=app.buddy
push() {
    local src="$1" dest="$2"
    [[ -f "$src" ]] || return 0
    log "installing $(basename "$src") -> files/$dest"
    adb push "$src" "/data/local/tmp/buddy-provision" >/dev/null
    adb shell "run-as $APP sh -c 'mkdir -p files/$(dirname "$dest") && cat /data/local/tmp/buddy-provision > files/$dest && chmod 600 files/$dest'"
    adb shell rm -f /data/local/tmp/buddy-provision
}

push "$DIR/api_key" cloud/api_key
push "$DIR/mail.properties" mail/account.properties
push "$DIR/triage.properties" profile/triage.properties
push "$DIR/policy.properties" profile/policy.properties

log "starting onboarding"
adb shell am start -n "$APP/buddy.android.ui.OnboardingActivity" >/dev/null
log "done. The profile applies from the next brief; run scripts/battery-baseline.sh after a day."
