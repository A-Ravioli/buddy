# Security review checklist

Phase 4 requires a review of the framework patches and the SELinux policy by someone
who did not write them, and a fix for every finding before anything else in the phase
ships. This is what the reviewer works through. Each item names where to look and what
a pass looks like.

## 1. What buddy can reach

| Check | Where | Pass |
|---|---|---|
| Privileged permissions are the minimum the phase needs | `platform/config/privapp-permissions-buddy.xml` | Every entry is used by a component in the manifest; none are speculative |
| Default runtime grants match the manifest | `platform/config/default-permissions-buddy.xml` | No grant without a `uses-permission` |
| The framework overrides are scoped to buddy's package or domain, never global | `platform/patches/README.md` 0008, 0009, 0011 | Each patch's condition names `app.buddy` or its SELinux domain |
| No component is exported without a system permission guarding it | `core/android/src/main/AndroidManifest.xml` | Every `exported="true"` has an `android:permission` or is a launcher/assist entry |

## 2. Isolation inside buddy

| Check | Where | Pass |
|---|---|---|
| Only cognition has network | `platform/sepolicy` (draft domains), `Brain.kt` | The cloud client is constructed in one place; no other module imports the SDK |
| Only actuation acts | `core/actuation`, `core/android/actuation` | Connectors are reachable only through `Executor.apply`; nothing else calls a connector |
| Actuation cannot decide | `Executor.kt` | The executor takes a verdict; it never constructs one |
| Policy cannot be bypassed | `ActPlanner.kt`, `VoiceServices.kt`, `BriefScheduler.kt` | Every proposal passes `PolicyEngine.decide` before `Executor.apply`; grep for `apply(` finds only sites preceded by `decide(` |

## 3. Data that must never leave

| Check | Where | Pass |
|---|---|---|
| One-time codes are stripped from envelopes and outputs | `Envelope.kt`, `BriefPlanner.sanitise` | Tests in `CognitionTest` and `InjectionCorpusTest` pass; the hidden field list is reviewed |
| Raw audio is never persisted | `core/audio`, the Android audio runtime | No file write of PCM anywhere; transcripts only |
| The ledger never leaves the device whole | `SliceBuilder.kt` | Every cloud call takes a slice with a character budget; there is no "all events" path |
| Credentials are not in the ledger | `Brain.kt`, `Mail.kt` | API key and mail password come from `files/` under credential-encrypted storage, never from events |
| Third-party speech stays home by default | `SliceBuilder`, `MemoryConsolidator` | Utterance events from non-user speakers are excluded from slices unless the task names the conversation |

## 4. Injection resistance

| Check | Where | Pass |
|---|---|---|
| Envelopes cannot be escaped | `Envelope.neutralise`, `InjectionCorpusTest` | Test passes for every corpus case; the corpus has grown since the last review |
| Operator instructions never carry external text | `AnthropicCloudModel`, `AnthropicCloudAgent` | The operator argument is built from profile and situation only |
| Hard limits are in code | `PolicyEngine.decide` | Deny rules run before any level check; no prompt text is consulted |
| The corpus blocks at maximum trust | `InjectionCorpusTest` | Passes with every domain at FULL |
| Voice commands are speaker-gated | `core/audio`, `VoiceServices.kt` | The command path requires the speaker gate; the stop phrase is the one exception and only pauses |

## 5. Keys and the build

| Check | Where | Pass |
|---|---|---|
| Signing keys are not in the repository | `.gitignore`, `platform/scripts/keys.sh` | `keys/` ignored; no `.pk8`/`.pem` tracked |
| Verified boot key is the one on the device | `platform/scripts/flash.sh` | `avb_pkmd.bin` matches the key in `keys/` |
| Cloud dependencies are pinned | `gradle/libs.versions.toml`, `platform/prebuilts` | Versions pinned; the prebuilt jars match the catalog |
| The patch set applies cleanly on the pinned tag | `platform/scripts/rebase.sh` | `git am` succeeds without conflicts |

## 6. What to do with findings

Every finding is a ledger of its own: file, severity, fix, verified-by. Blocking
findings (anything in sections 2 to 4) stop the phase. The review is repeated after
the fixes and again before the second user.
