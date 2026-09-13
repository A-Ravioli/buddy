# The build host checklist

Everything in this repository that cannot be settled without the GrapheneOS tree or the
phone, in the order it can be answered. Each row is a `VERIFY` comment somewhere in the
source; the command is what settles it.

Set up first: `platform/scripts/setup-host.sh`, then `platform/scripts/sync.sh <tag>`.
`$BUDDY_TREE` is the checkout (default `~/grapheneos`).

## 1. After the sync, before the first build

These are all reading the tree. None of them need a build.

| # | Question | Where it is asked | Command |
|---|---|---|---|
| 1 | Is `aosp_mustang.mk` the product to inherit? | `platform/product/buddy_mustang.mk:8` | `ls $BUDDY_TREE/device/google/mustang/*.mk` |
| 2 | What are the androidx Compose prebuilt module names? | `Android.bp` (static_libs) | `grep -rn 'name: "androidx.compose' $BUDDY_TREE/prebuilts/sdk/current/androidx/Android.bp \| head -20` |
| 3 | What is the setup wizard's Soong module called? | `Android.bp` (`overrides`), `platform/patches/README.md` 0014 | `grep -rn 'name: "SetupWizard\|name: "Provision\|name: "Launcher3' $BUDDY_TREE/packages/apps $BUDDY_TREE/vendor 2>/dev/null \| head` |
| 4 | Which resource carries the Updater's release URL? | `platform/overlay/packages/apps/Updater/res/values/config.xml` | `cat $BUDDY_TREE/packages/apps/Updater/res/values/config.xml` |
| 5 | What key names does the release signing expect? | `platform/scripts/keys.sh:18` | `grep -rn 'avb\|releasekey\|generate_key' $BUDDY_TREE/script/*.sh \| head -20` |
| 6 | How does a `system_ext` app get marked persistent on this release? | `platform/product/buddy_mustang.mk:47` | `grep -rn 'FLAG_PERSISTENT' $BUDDY_TREE/frameworks/base/services/core/java/com/android/server/pm/*.java \| head` |
| 7 | Which partition does surfaceflinger read the boot animation from first? | `platform/product/buddy_mustang.mk:32` | `grep -n 'bootanimation.zip' $BUDDY_TREE/frameworks/base/cmds/bootanimation/BootAnimation.cpp` |
| 8 | What is `LockPatternUtils.setLockCredential`'s signature? | `surface/setup/LockCredential.kt:35` | `grep -n 'setLockCredential' $BUDDY_TREE/frameworks/base/core/java/com/android/internal/widget/LockPatternUtils.java` |
| 9 | Do the lock-screen secure settings still have these names? | `surface/lockscreen/LockscreenPolicy.kt:21` | `grep -n 'LOCK_SCREEN_SHOW_NOTIFICATIONS\|LOCKSCREEN_SHOW_CONTROLS\|LOCK_SCREEN_SHOW_QR_CODE_SCANNER' $BUDDY_TREE/frameworks/base/core/java/android/provider/Settings.java` |
| 10 | Is `addNetwork` + `NETWORK_SETUP_WIZARD` still the wizard's own path? | `surface/setup/WifiJoiner.kt:35` | `grep -rn 'NETWORK_SETUP_WIZARD' $BUDDY_TREE/packages/modules/Wifi/framework/java/android/net/wifi/WifiManager.java` |
| 11 | What is the lockscreen root called this release? | `platform/patches/README.md` 0015 | `platform/patches/0015-lockscreen/apply.sh` (reports, writes nothing) |

## 2. The first build

    platform/scripts/keys.sh          # then back the keys up, somewhere that is not this machine
    platform/scripts/build.sh

Expect the first Soong compile of `core/android` to surface errors: it is compiled here
against Robolectric's framework jar (`core/android-verify`), which is close but not the
tree. They should be local — an import, an API that moved — not structural. Everything
structural is already compiled and unit-tested on the host.

Then, in order:

1. Patch 0007, the one-line BoardConfig include, so the SELinux app context is built.
2. `platform/patches/0015-lockscreen/apply.sh --write`, once row 11 says the candidate is
   the right file, plus the Kotlin side it prints.
3. `platform/scripts/vendor-face.sh --check` — should say the tree's copy is current.
   `build.sh` re-vendors on every build, so this only fails if someone edited the copy.

## 3. On the phone

`platform/scripts/flash.sh`, then work down. Each of these is a claim this repo makes.

| What | How it should go |
|---|---|
| Power on | buddy's eyes open on black, he breathes, and the wake-up takes over without a cut |
| First boot | The walk-through is the setup: Wi-Fi and a PIN in his voice, no wizard |
| Home | buddy. Swipe up does nothing; there is no app grid |
| The shade | Does not pull down. Nothing rings or peeks on its own |
| The torch | Say "torch". It lights before the phone is unlocked |
| The network | Say "wifi". His picker, not a panel |
| An app | Say "open Monzo". It opens; there is no other way in |
| Lock it | His face on black. Nothing else. `adb shell settings get global buddy_lock_face` reads `rest` |
| Make something wait | The eyes lift and the glow warms, with no word of the message on screen |
| Quiet hours | The eyes are shut, and nothing chimes, whatever is waiting |
| Always-on | The same face, dimmed, blinking rarely |
| Call it | The call screen comes up over the lock screen, with the caller's name |
| A brief | A chime and a tap, not a notification — there is nowhere for one to go |

Two rows are the ones most likely to be wrong, because they are the two places this build
asks the framework for something unusual:

- **`Settings.Global` with buddy's own key names** (`surface/lockscreen/LockFace.kt:23`).
  Check with `adb shell settings put global buddy_lock_face rest` and read it back. If the
  provider refuses unknown names, the channel becomes a broadcast and `BuddyFaceView`
  observes that instead; everything else about the patch is unchanged.
- **An in-call UI starting an activity from the background** while the phone is locked
  (`perception/RoleComponents.kt:112`). If the call screen does not come up, the fallback
  is a full-screen-intent notification, which needs the shade's disable flags loosened for
  that one channel.

## 4. Then Phase 0's own exit criteria

From `docs/phase-0.md`: a week of real traffic in the ledger, content capture coverage per
app, and the screen-off drain number that becomes the battery budget.
