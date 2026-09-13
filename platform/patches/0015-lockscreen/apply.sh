#!/usr/bin/env bash
# Patch 0015: the lock screen is buddy's face and nothing else.
#
#   patches/0015-lockscreen/apply.sh            # vendor the face, find the anchors, report
#   patches/0015-lockscreen/apply.sh --write    # also edit the layout it found
#
# Why this is a script and not a .patch: the lockscreen root has been three different
# classes across three releases (KeyguardStatusView, then KeyguardRootView, then the
# scene-based lockscreen), so a diff written away from the tree would not apply to it.
# This finds what is actually there at the pinned tag and says so, loudly, rather than
# failing halfway through a build.
#
# What it does NOT touch: the bouncer, LockPatternUtils, credential checking, the lockout
# policy. This patch changes what a locked phone shows, never what unlocks it.
source "$(dirname "${BASH_SOURCE[0]}")/../../scripts/common.sh"

WRITE=0
[[ "${1:-}" == "--write" ]] && WRITE=1

SYSUI="$BUDDY_TREE/frameworks/base/packages/SystemUI"
[[ -d "$SYSUI" ]] || die "no SystemUI at $SYSUI; run sync.sh first"

# 1. The face itself, from the one definition in core/android.
"$BUDDY_SRC/platform/scripts/vendor-face.sh"

# 2. The layout the keyguard inflates, whatever it is called this release.
log "looking for the lockscreen root layout"
CANDIDATES=()
while IFS= read -r f; do CANDIDATES+=("$f"); done < <(
    find "$SYSUI/res/layout" -maxdepth 1 -name 'keyguard_root*.xml' -o -maxdepth 1 -name 'keyguard_status_view.xml' 2>/dev/null | sort
)
if [[ ${#CANDIDATES[@]} -eq 0 ]]; then
    log "found none. Look for what the keyguard inflates:"
    log "  grep -rn 'keyguard_root\\|KeyguardRootView\\|LockscreenContent' $SYSUI/src | head"
    die "cannot place the face without knowing the root; nothing was written"
fi
for f in "${CANDIDATES[@]}"; do log "  candidate: ${f#"$BUDDY_TREE"/}"; done

# 3. What still has to be read by a person, because it is Kotlin and it moves.
log ""
log "the rest of the patch, against the tree you have:"
log "  - whatever binds the clock, the smartspace and the notification section to the root"
log "    stops binding them. Search:"
log "      grep -rn 'smartspace\\|KeyguardClockSwitch\\|NotificationStackScrollLayout' $SYSUI/src | grep -i keyguard | head"
log "  - the root inflates com.android.systemui.buddy.BuddyFaceView instead, full bleed,"
log "    with lowPower = true on the always-on display path (SystemUI calls it dozing)."
log "  - SystemUI's Android.bp lists src/com/android/systemui/buddy in its srcs."
log "  - the face reads its state from Settings.Global; nothing has to be wired to it."

if [[ $WRITE -eq 0 ]]; then
    log ""
    log "nothing written. Re-run with --write once the candidate above is the right file."
    exit 0
fi

[[ ${#CANDIDATES[@]} -eq 1 ]] || die "${#CANDIDATES[@]} candidates; edit the right one by hand rather than guessing"
TARGET="${CANDIDATES[0]}"
[[ -f "$TARGET.buddy-orig" ]] || cp "$TARGET" "$TARGET.buddy-orig"
cat > "$TARGET" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<!--
  buddy: the lock screen. A locked phone says one thing, which is whether anything needs
  you, and the eyes are that. No clock, no notifications, no shortcuts.
  Replaced by platform/patches/0015-lockscreen/apply.sh; the original is alongside as
  .buddy-orig.
-->
<com.android.systemui.buddy.BuddyFaceView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/buddy_face"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
XML
log "wrote ${TARGET#"$BUDDY_TREE"/} (original kept as .buddy-orig)"
log "the Kotlin side above is still yours to do."
