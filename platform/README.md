# platform: the buddy Android build

Everything needed to produce a buddy build of GrapheneOS for the Pixel 10 Pro XL
(`mustang`, the reference device) or the Pixel 7 (`panther`, a development device), sign
it with our own keys, flash it, and ship updates over the air. Nothing
in this directory runs in the phone; it is consumed by the AOSP build on the build host.

This repository is checked out at `vendor/buddy` inside the GrapheneOS tree by the local
manifest in `manifest/buddy.xml`, so every path below is `vendor/buddy/platform/...` from
the tree root.

## Layout

| Path | What it is |
|---|---|
| `../Android.bp` | At the repo root: builds the app from `core/` with Soong as a privileged, platform-signed system app, plus its config files |
| `manifest/buddy.xml` | `repo` local manifest that adds this repository to the GrapheneOS checkout |
| `product/` | `buddy_common.mk`, what buddy adds to any device, and one `buddy_<codename>.mk` per device that inherits the GrapheneOS product and then the common one |
| `config/` | Privileged-permission allowlist, default runtime permission grants, sysconfig |
| `overlay/` | Framework resource overlays: default roles, content capture service, listener access, updater URL |
| `sepolicy/` | `seapp_contexts` for Phase 0 and the draft per-subsystem domains for the split |
| `patches/` | The framework patch set: what each patch changes, why, and how to verify it, and the scripts that apply the tree-dependent ones |
| `scripts/` | Host setup, sync, keys, build, flash, rebase, battery baseline |

## Build host

A Linux machine with at least 64 GB of memory, 16 or more cores, and 500 GB of fast
disk. `scripts/setup-host.sh` installs the packages GrapheneOS's build guide lists and
the `repo` tool. Expect the first sync to take an hour and the first build several.

## The flow

```
scripts/setup-host.sh                    # once
scripts/sync.sh 2026081300               # GrapheneOS release tag; verifies the signed tag
scripts/keys.sh                          # once: platform, release, verified-boot keys into keys/<device>
scripts/build.sh                         # JVM tests -> platform build -> signed release in releases/
scripts/flash.sh releases/<build>        # first install: unlock, set custom AVB key, flash, lock
scripts/rebase.sh 2026091200             # monthly: new tag, re-apply patches, rebuild
```

Every script reads `BUDDY_DEVICE` (default `mustang`); `export BUDDY_DEVICE=panther` first
to build for a Pixel 7. Each device has its own keys directory and its own releases.

`flash.sh` also runs on macOS with `fastboot` from Android platform-tools. Copy the factory
zip and the device's keys directory over from the build host, and point `BUDDY_KEYS` at
the keys if they are not under `~/grapheneos/keys/<device>`.

`keys/` is git-ignored and must be backed up out of band. Losing the verified-boot key
means a wipe on the next install; losing the platform key means every app signed with
it, buddy included, cannot be updated in place.

## What is verified where

- The JVM modules (`core/ledger`, `core/perception`) are tested on any machine with
  `./gradlew :core:ledger:test :core:perception:test`.
- `core/android` uses system and platform APIs, so it compiles only inside the tree;
  Soong builds it as part of the platform build.
- Everything in this directory is exercised only by the platform build. The product
  makefile, the overlay, and the SELinux files reference GrapheneOS paths and resource
  names that must be checked against the tree at the pinned tag. `scripts/sync.sh`
  prints the spots to verify after the first sync.

## Product names to verify after first sync

GrapheneOS names Pixel 10 Pro XL builds `mustang` and Pixel 7 builds `panther`. The Pixel 7
shares the `pantah` device tree with the 7 Pro; `scripts/common.sh` maps a codename to its
tree. Confirm in the synced tree:

- The device tree exists (`device/google/mustang/` or `device/google/pantah/`) and the
  product inherited by `product/buddy_<codename>.mk` matches the makefile GrapheneOS uses
  for that codename (see their `build/` docs and `lunch` targets; the inherit line has a
  comment marking it).
- The framework overlay resource names in `overlay/frameworks/base/.../config.xml` exist
  in `frameworks/base/core/res/res/values/config.xml` at that tag. Names drift between
  Android releases; a wrong name fails the build loudly, which is the point of an
  overlay.
