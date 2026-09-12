# core/android

The buddy system app: perception sources that map platform objects into the snapshot
types in `core/perception`, the ledger opened in credential-encrypted storage, the
cognition and actuation wiring, and the surface: the chat with buddy, the creature,
the timeline, and the first-run walk-through. The surface is the home screen.

It is **not a Gradle module**. It uses system and platform APIs (the content capture
service, the voice interaction services) that the public Android SDK does not expose,
so it is built by Soong from the `Android.bp` at the repository root when this
repository sits at `vendor/buddy` in the GrapheneOS tree. `platform/scripts/build.sh`
runs the JVM tests first and then the platform build, which compiles this app.

The surface is Jetpack Compose, built from the androidx prebuilts in the platform tree
(see `Android.bp`). The design it ports is the canvas linked from the root README.

`core/android-verify` compiles everything in this directory on the host (`./gradlew
build`), against Robolectric's full framework jar and the Compose API, so a type or API
error shows up in CI rather than on the build host. It does not check the Soong module
names in `Android.bp`. `./gradlew -Pshots :core:android-shots:run --args=/tmp/shots`
renders the surface's screens to PNGs on the host, from the same sources.

Rule for this directory: no logic. Anything that decides what becomes an event, how
it is keyed, or what it means belongs in `core/perception` or `core/ledger`, where it
is tested on the JVM. Code here should be a thin mapping from framework types to
snapshot types and a call to `Ingest.submit`.

| File | What it does |
|---|---|
| `BuddyApp.kt` | Application and boot receiver; opens the ledger after first unlock |
| `ledger/FrameworkSqlDriver.kt` | The ledger's SQL driver over the framework's SQLite, with typed binding |
| `ledger/LedgerHolder.kt` | Owns the one database in credential-encrypted storage |
| `perception/Ingest.kt` | Single write path; buffers pre-unlock events in memory |
| `perception/BuddyNotificationListener.kt` | Notification stream to snapshots |
| `perception/PerceptionService.kt` | Content observers for SMS, call log, calendar; location; device state |
| `perception/RoleComponents.kt` | SMS delivery (persists incoming SMS as the default SMS app), in-call, screening, and role stubs |
| `capture/BuddyContentCaptureService.kt` | Content capture sessions to capture trees |
| `ui/Activities.kt` | The raw event timeline (debug, reached from the playground) and the role-required stub activities |
| `surface/SurfaceActivity.kt` | The home. First run: the wake-up and the walk-through. After: the chat. Hold the creature to talk |
| `surface/SurfaceStore.kt` | Builds what the screen shows from the ledger and the last plan; sends decisions to the executor |
| `surface/creature/` | buddy: a circle and two strokes, eight moods, five gazes, animated between states |
| `surface/onboarding/` | The wake sequence, eighteen one-phrase steps, and the bootstrap that infers the profile while they run |
| `surface/home/` | Cards for decisions, replies, holds, recalls, handled counts and receipts; the quiet state; the timeline; the playground |
| `surface/theme/` | Three schemes (colour, black and white, green), one colour per domain, the two bundled typefaces (OFL) |
| `voice/VoiceServices.kt` | Voice interaction role holders, no behaviour yet |
