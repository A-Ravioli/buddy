# core/android

The buddy system app for Phase 0: perception sources that map platform objects into
the snapshot types in `core/perception`, the ledger opened in credential-encrypted
storage, and the debug timeline that doubles as the home screen.

It is **not a Gradle module**. It uses system and platform APIs (the content capture
service, the voice interaction services) that the public Android SDK does not expose,
so it is built by Soong from the `Android.bp` at the repository root when this
repository sits at `vendor/buddy` in the GrapheneOS tree. `platform/scripts/build.sh`
runs the JVM tests first and then the platform build, which compiles this app.

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
| `ui/Activities.kt` | Timeline (home) and the role-required stub activities |
| `voice/VoiceServices.kt` | Voice interaction role holders, no behaviour yet |
