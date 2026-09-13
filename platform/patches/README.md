# Framework patch set

Each patch is a separate commit on its own branch in the affected AOSP project, kept
as a `git format-patch` file here once written (`NNNN-<project>-<name>.patch`), and
re-applied by `scripts/rebase.sh` with `git am`. Until a patch is written and verified
against the tree, its entry below is a specification: what changes, where, why, and
how to prove it works.

A finding from writing this list: being the platform-signed assistant, dialer, and
content capture service already grants several capabilities the plan assumed would
need patches. Those are marked **no patch**. The set that actually needs framework
changes in Phase 0 is small.

| # | Capability | Status | Where |
|---|---|---|---|
| 0001 | Content capture service assignment | **no patch**: overlay `config_defaultContentCaptureService` | `overlay/` |
| 0002 | Default roles (assistant, SMS, dialer, screening, listener access) | **no patch**: overlay | `overlay/` |
| 0003 | Notification interception before display | **no patch**: `NotificationAssistantService` role (Phase 1) | `overlay/` (commented) |
| 0004 | Input injection | **no patch**: `INJECT_EVENTS` is signature-level; platform-signed apps hold it via the privapp list (Phase 2) | `config/` |
| 0005 | Both sides of phone calls | **no patch**: `CAPTURE_AUDIO_OUTPUT` for the platform-signed dialer via the privapp list (Phase 1) | `config/` |
| 0006 | Hotword on the DSP | **no patch**: the voice interaction service uses `AlwaysOnHotwordDetector` (Phase 1) | app |
| 0007 | SELinux additions hooked into the device board config | patch | `device/google/mustang/BoardConfig.mk` |
| 0008 | Content capture for secure windows and opted-out apps | patch | `frameworks/base` |
| 0009 | Playback capture for opted-out apps | patch | `frameworks/base`, `frameworks/av` |
| 0010 | Concurrent low-priority ambient mic stream | patch, maybe | `frameworks/av` |
| 0011 | Microphone indicator behaviour | patch | `frameworks/base` |
| 0012 | Per-subsystem SELinux domains | patch, Phase 4 | `system/sepolicy`, `sepolicy/draft` |
| 0013 | Remove viewer chrome: the launcher, recents, the shade and quick settings | **no patch**: Soong `overrides`, a SystemUI resource overlay, and a disable flag from the app | `Android.bp`, `overlay/`, app |
| 0014 | Replace the setup wizard with buddy's wake-up | **no patch**: Soong `overrides` plus buddy setting `device_provisioned` itself | `Android.bp`, app |
| 0015 | The lock screen is buddy's face and nothing else | partly no patch (secure settings), partly a tree edit by `0015-lockscreen/apply.sh` | app, `frameworks/base/packages/SystemUI` |

## 0007: board config hook

One line appended to `device/google/mustang/BoardConfig.mk`:

```
-include vendor/buddy/platform/product/BoardConfigBuddy.mk
```

Verify: `m selinux_policy` succeeds and `ls $OUT/system_ext/etc/selinux/` includes the
buddy `seapp_contexts` entry (grep for `app.buddy` in the built `plat_seapp_contexts` or
the system_ext equivalent).

## 0008: content capture for secure windows and opted-out apps

Decision 4 in `docs/06-open-questions.md`. Two behaviours to change, both in
`frameworks/base`:

1. Apps can disable content capture for an activity or a view
   (`ContentCaptureManager.setContentCaptureEnabled(false)`, manifest flags, and the
   per-view `setImportantForContentCapture`). The check that honours the app's opt-out
   lives on the client side in `ContentCaptureManager` and `MainContentCaptureSession`,
   which run inside the app's process, and on the service side in
   `ContentCaptureManagerService` (allowlist and per-package enable). The patch makes
   the service side treat every package as enabled when the configured service is
   buddy, and makes the client side ignore the app's opt-out for the same condition.
   The client-side change is what makes this a framework patch rather than a setting.
2. Windows flagged `FLAG_SECURE` are excluded from assist and capture data. The exclusion
   is applied where the assist structure is built (`ActivityClientRecord` /
   `Activity.onProvideContentCaptureStructure` path) and, for screenshots, in
   SurfaceFlinger's secure-layer handling. Phase 0 patches only the capture path; the
   screenshot path is Phase 3 with the vision fallback.

Scope it to buddy: the condition is "the bound content capture service's package is
`app.buddy`", never a global switch.

Verify: a test app that sets `FLAG_SECURE` and `setContentCaptureEnabled(false)` on an
activity with a known string in a `TextView`; the string appears in the ledger as a
`screen` event within a second of the activity resuming. Then the same test app on a
stock build shows nothing, to prove the patch is what did it.

## 0009: playback capture for opted-out apps

Decision 5. `android:allowAudioPlaybackCapture="false"` is honoured in
`AudioPolicyService` when a capture client with `AudioPlaybackCaptureConfiguration`
attaches, by checking the player's app opt-out (carried in the audio attributes'
`ALLOW_CAPTURE_BY_*` policy, set from the manifest by `ActivityThread`). The patch
makes the policy treat buddy's audio capture uid as allowed regardless of the
per-player capture policy, in `frameworks/av/services/audiopolicy/` where the
`AUDIO_FLAG_NO_MEDIA_PROJECTION` / capture policy check runs, and keeps every other
capturer's behaviour unchanged.

Verify: a VoIP call in an app that sets the opt-out is transcribed; the same app's
audio is not visible to a second, ordinary capture app on the same build.

## 0010: concurrent low-priority ambient mic stream

The audio policy hands the microphone to one client at a time, with exceptions for the
active assistant (which can capture concurrently with ordinary apps, possibly receiving
silence while a privacy-sensitive app such as a voice call is recording). As the
assistant, buddy may already get what Phase 1 needs. Measure first: with a video call
in progress, does buddy's stream carry audio, silence, or nothing? If silence during
calls is the only gap, that is acceptable in Phase 1 because calls are captured
through the dialer path instead. Write the patch only if the assistant exemption turns
out not to cover ambient capture on this release.

## 0011: microphone indicator

Decision 6: no standing indicator; a cue only when transcription starts. The indicator
is driven by `AppOpsManager` op activity for `RECORD_AUDIO` surfaced through
`PermissionManagerService` and `SystemUI`'s privacy chip. The patch exempts buddy's
audio uid from the chip and the status bar dot (there is an existing exemption list for
some system components; extend it), and buddy raises its own haptic and earbud cue from
the audio pipeline when tier 3 starts. Not before Phase 1.

## 0012: per-subsystem SELinux domains

`sepolicy/draft/buddy_domains.te` states the intent. It cannot be built until buddy's
subsystems are separate processes with separate uids or are moved into system_server
as buddy system services. That is the Phase 4 work; the draft exists so the security
review has something to read.

## 0013: remove viewer chrome

Written out, this needed no framework patch at all. Three separate things, three
mechanisms that already exist:

**The launcher** is dropped from the build with Soong `overrides` on the Buddy app
module, so the HOME role has one candidate and there is no app grid.

**Recents** comes with it. The overview is the launcher's (`QuickStep`), so once the
launcher is not in the image there is no recents provider and the gesture resolves to
nothing. Rather than leave a gesture that half-animates into an empty screen, buddy calls
`StatusBarManager.disable(DISABLE_RECENT)` at boot, which needs the signature-level
`STATUS_BAR` permission it already holds. Disable flags are held against the caller's
token for as long as its process lives, and buddy is a persistent app, so re-applying at
boot is the whole of it.

**The notification shade** does not pull down: `disable2(DISABLE2_NOTIFICATION_SHADE)`,
with `DISABLE2_QUICK_SETTINGS` named alongside it so a release where the two come apart
still loses the panel. Nothing posts to the shade on this build — buddy is the
notification assistant and holds what arrives — so the drawer would open on a "no
notifications" label, which is a worse answer than no drawer. The same call adds
`DISABLE_NOTIFICATION_ICONS` and `DISABLE_NOTIFICATION_ALERTS`: what interrupts the user
is buddy's decision, not each app's, so nothing rings or peeks on its own account.

**Quick settings** goes with the shade, and the two switches it was still carrying move
into buddy, because they are things he can do rather than things only a panel can:
"torch" throws the flash (`buddy/android/device/Torch.kt`), and "wifi" opens the network
picker he used to get the phone online during the walk-through
(`surface/home/NetworkScreen.kt`). Both are ordinary commands, so they work spoken,
typed, or through the earbuds, and the torch answers before the ledger is even open —
which is the state the phone is in when someone asks for a torch. The trimmed tile list
is still written, by the SystemUI resource overlay and by buddy into `Settings.Secure`,
because it is what a build whose disable call fails would fall back to.

The status bar stays. A clock and a battery figure are not something to manage, and a
phone that cannot show either is a worse phone, not a calmer one.

Verify by booting: home is buddy, swipe-up does nothing, and a pull down from the top
does nothing. Say "torch" and the flash lights; say "wifi" and the picker opens. On a
build where the disable call failed, the panel is there with two tiles and
`adb shell settings get secure sysui_qs_tiles` reads `internet,flashlight`.

## 0014: replace the setup wizard

The first thing a new phone shows should be buddy waking up, not a language picker. The
wizard package is dropped from the build with Soong `overrides` on the Buddy module, the
same mechanism that drops the launcher, so buddy's `HOME` filter is the only candidate
at first boot and the framework starts it directly.

That makes buddy responsible for what the wizard did. `surface/setup` holds it:

- `Provisioning` reads and writes `device_provisioned` and `user_setup_complete`. Until
  they are set the framework keeps the keyguard off and hides the status bar and quick
  settings, which is the blank screen the wake-up wants. buddy sets them at the end of
  the walk-through, and that is the moment the phone becomes a locked, normal-feeling
  device.
- `WifiJoiner` scans and joins from inside the walk-through, using the same privileged
  path a wizard uses (`NETWORK_SETUP_WIZARD` plus `addNetwork`), so there is no handover
  to Settings.
- `LockCredential` sets the first PIN through `LockPatternUtils`, reached over
  reflection. This is the credential the ledger's storage key is bound to, so it has to
  happen before anything is written. If the call is unavailable the step falls back to
  the platform's own chooser rather than leaving the phone unlocked and silent about it.

The two extra steps only appear when `device_provisioned` is 0, so a reflash onto a
configured phone does not ask for the Wi-Fi password and a new PIN again.

Verify on a freshly flashed phone: the first frame after boot is buddy's wake-up with no
status bar; the Wi-Fi step lists real networks and joining one sticks across a reboot;
the PIN set in the flow unlocks the phone afterwards and `settings get global
device_provisioned` reads 1. VERIFY before this builds: the GrapheneOS wizard's Soong
module name, and that `LockPatternUtils.setLockCredential` still has this signature at
the pinned tag.


## 0015: the lock screen

A locked phone should say one thing: whether anything needs you. Stock keyguard says a
dozen — clock, date, weather, notification list, camera and wallet shortcuts, media
controls. On this build notifications feed the agent and the user gets a brief
(docs/01-architecture.md), so a lock screen listing them is the old phone leaking through.

Most of it needs no patch. `surface/lockscreen/LockscreenPolicy.kt` turns off lock-screen
notifications, the wallet and controls shortcuts and the QR scanner through secure
settings, and turns **on** always-on display, because the creature sheet asks for the chin
to stay lit at whisper brightness and blink, and always-on is that.

What is left needs a patch: the keyguard still draws a clock and a smartspace, and
nothing in it draws buddy.

**The change.** In SystemUI's lockscreen root, replace the clock, smartspace and
notification section with a single full-screen `BuddyFaceView`. The always-on display
shares that root, so the same view serves both; it takes `lowPower = true` there, which
drops the breathing and blinks rarely.

**Applying it.** `patches/0015-lockscreen/apply.sh`, and it is a script rather than a
`.patch` on purpose: the lockscreen root has been three different classes across three
releases, so a diff written away from the tree would not apply to the tree. The script
finds what is actually there, prints it, and writes nothing until it is run with
`--write`. What it cannot do for you is the Kotlin side — whatever binds the clock, the
smartspace and the notification section — and it prints the searches that find those.

**The files to vendor.** `scripts/vendor-face.sh` copies `BuddyFaceView`, `LockFace`,
`FacePainter`, `FaceGeometry` and `CreatureState` from
`core/android/src/main/kotlin/buddy/android/surface/` into
`SystemUI/src/com/android/systemui/buddy/`, rewriting the package as it goes. They use
only `android.graphics`, the settings provider and plain Kotlin, so they compile there
unchanged. The copy is generated and never edited in the tree: `scripts/build.sh`
refreshes it on every build once the patch is applied, and `vendor-face.sh --check` fails
if it has drifted. The originals are compiled and unit-tested in this repo
(`core/android-verify`), which is why the copy is safe: `FaceGeometry` is the single
definition of where the strokes go, and `FaceGeometryTest` and `LockFaceTest` pin the
rules the two sides share.

**The mood.** SystemUI cannot reach into buddy's process, so the face reads what he
publishes: two `Settings.Global` keys, `buddy_lock_face` ("waiting" or "rest") and
`buddy_lock_quiet` (quiet hours as "22-8"). `LockFace` is both ends of that, and
`BuddyFaceView` observes the keys itself, so the patch has nothing to wire beyond adding
the view. Global settings are device-encrypted, so they read back before the first unlock
— which is when the lock screen is first drawn — and they survive a reboot, so a phone
restarted overnight still knows to keep its eyes shut.

Quiet hours are evaluated in the view against the published window rather than published
as a state, so a phone nobody has touched since yesterday still falls asleep at the right
hour; the view wakes once an hour to check and otherwise draws nothing. Quiet hours beat
a waiting item: the point of the window is that the phone does not summon anyone, and a
glow across a dark room is a summons.

Nothing about the user's content crosses this channel. Whether something is waiting and
when the night is — that is the whole protocol. No count, no preview, no sender. The eyes
lifting is the notification.

**What is not touched.** The bouncer and everything behind it: `KeyguardSecurityContainer`,
`LockPatternUtils`, the credential checking and the lockout policy. This patch changes
what a locked phone shows, never what unlocks it. A review that finds this patch touching
the security model has found a mistake.

Verify on a phone: lock it and the screen is buddy's face on black, nothing else; put it
face up and the always-on display shows the same face, blinking; let something escalate
and the eyes lift without a word of the message appearing; set quiet hours and the eyes
close. Swipe up and the PIN pad is the stock bouncer, unchanged.

VERIFY on the build host, which is what `apply.sh` is for: the lockscreen root's class and
package at the pinned tag, and whether SystemUI's `Android.bp` picks up the vendored
directory or has to list it.
