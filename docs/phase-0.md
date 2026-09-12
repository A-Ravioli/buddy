# Phase 0 status

Phase 0 from the roadmap: a buddy build on the founder's Pixel 10 Pro XL, updating
over the air, with buddy able to see everything and write it to a ledger. No cognition.

This page tracks what exists, what is verified, and what still needs the build host or
the phone. Update it as items land.

## What exists

| Roadmap item | State | Where |
|---|---|---|
| Ledger: schema, append-only rules, deterministic ids, full-text search | **Done, tested on the JVM against real SQLite** | `core/ledger` |
| Perception normalisers: notifications (including messaging style), SMS, calendar, call log, location with debouncing, device state, content-capture trees | **Done, tested on the JVM** | `core/perception` |
| System app: ledger in credential-encrypted storage, pre-unlock buffering, notification listener, content observers, location, device state, content capture service, SMS delivery as default SMS app, in-call and screening recorders, role stubs, debug timeline as home | Written, **not yet compiled** (needs the tree) | `core/android`, `Android.bp` |
| Product config: `buddy_mustang`, framework overlay for roles and content capture, privileged and default permissions, sysconfig, SELinux app context | Written, **not yet built** | `platform/product`, `platform/overlay`, `platform/config`, `platform/sepolicy` |
| Build pipeline: host setup, signed-tag sync, key generation, build and release packaging, first flash, monthly rebase | Written, **not yet run** | `platform/scripts` |
| Battery baseline script | Written, **not yet run** | `platform/scripts/battery-baseline.sh` |
| Framework patch set | Specified; **fewer patches needed than planned** (see below) | `platform/patches/README.md` |
| JVM CI | Done | `.github/workflows/jvm.yml` |

## Decisions made while building

- **Ledger encryption is the platform's file-based encryption, not SQLCipher.** The
  app's data directory is credential-encrypted storage: encrypted at rest with a key
  the hardware keystore binds to the lock credential, which is exactly what the plan
  asked for. A second encryption layer would add a key to manage and nothing else.
  Consequence: the ledger opens only after the first unlock since boot, and events
  perceived before that wait in memory.
- **Hand-written SQL instead of Room.** The ledger is one append-only table with
  triggers and an FTS index. A tiny driver interface lets the same schema run over
  JDBC on the JVM (where it is tested) and the framework's SQLite on the phone.
- **The app is built by Soong, not Gradle.** The content capture service is a system
  API; the public SDK cannot compile it. The app compiles inside the tree with
  platform APIs. Gradle builds and tests only the JVM modules.
- **Several "patches" are not patches.** Being the platform-signed assistant, dialer,
  SMS app, and content capture service already provides notification interception
  before display (the notification assistant role), input injection, both sides of
  calls, and DSP hotword. The remaining framework patches are the two overrides
  (secure windows, playback capture), the SELinux hook, the mic indicator, and
  possibly the concurrent mic stream.

## What needs the build host

In order, each unblocking the next:

1. `platform/scripts/setup-host.sh`, then `sync.sh <tag>`. After the sync, check the
   items the script prints: the `mustang` device directory and product makefile name,
   the overlay resource names, the Updater config resource name. Fix the two `VERIFY`
   comments in `platform/product/buddy_mustang.mk`.
2. `keys.sh`, then back the keys up.
3. `build.sh`. Expect the first platform build of the app to surface compile errors in
   `core/android`; it was written without a compiler. They should be local (an import,
   an API that moved), not structural.
4. Patch 0007 (the one-line BoardConfig include) so the SELinux app context is built.
5. `flash.sh` on the phone.

## What needs the phone

- A week of real traffic in the ledger, replayable. Export with
  `adb shell run-as app.buddy cat files/ledger/ledger.db > ledger.db` (userdebug) and
  open with the JDBC driver from a Kotlin script or `sqlite3`.
- Content capture coverage per app: which of the founder's top ten apps report text
  through the generic parser, which need Accessibility, which need vision.
- `battery-baseline.sh` for the screen-off drain number that becomes the budget.
- The notification listener backfill and the SMS-as-default-app path, both of which
  behave differently on a real device than in any test.

## Exit criteria (from the roadmap)

- [ ] A week of the founder's real traffic in the ledger, replayable
- [ ] Content capture coverage measured per app
- [ ] Screen-off drain within a set budget
