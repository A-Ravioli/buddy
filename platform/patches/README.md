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
| 0013 | Remove viewer chrome: the launcher (no patch, Soong `overrides`), quick settings and recents in SystemUI | partly no patch, partly patch | `Android.bp`, `frameworks/base/packages/SystemUI` |
| 0014 | Replace the setup wizard with buddy's wake-up | **no patch**: Soong `overrides` plus buddy setting `device_provisioned` itself | `Android.bp`, app |

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

Phase 4. The stock launcher is removed with Soong `overrides` on the Buddy app module,
so the HOME role has one candidate and there is no app grid. Quick settings and the
recents overview in SystemUI assume someone is looking; a patch disables the recents
gesture and reduces quick settings to the connectivity and torch tiles buddy cannot
manage on the user's behalf. The status bar stays for the clock and battery. Verify by
booting: home shows the timeline, swipe-up does nothing, quick settings has two tiles.

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
