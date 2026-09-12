# wear: the Pixel Watch surface

Phase 4 (decision 10, docs/06-open-questions.md): the watch is the tap-to-resolve
escalation surface and carries the haptic cue when transcription starts. It is a Wear
OS app, built with Gradle and the Android SDK on the build host and sideloaded to the
watch; Wear OS is not part of the GrapheneOS build.

This directory holds the watch app and the phone-side bridge contract. The phone side
sends over the Wearable Data Layer:

| Path | Payload | Meaning |
|---|---|---|
| `/buddy/escalations` | JSON list of `{id, text, urgent, options}` | The current escalation queue, replaced whole |
| `/buddy/cue` | `{kind: "transcription_started" \| "transcription_stopped", reason}` | Haptic cue |
| `/buddy/brief` | `{spoken}` | The latest brief, for the watch to show when earbuds are out |

The watch sends back:

| Path | Payload | Meaning |
|---|---|---|
| `/buddy/resolve` | `{id, option}` | The user tapped an option on an escalation |

The bridge on the phone depends on the Play Services wearable library, which is
available through sandboxed Play Services on the GrapheneOS base. It is a separate
prebuilt for the platform build (`platform/prebuilts/wear`), copied by
`./gradlew :wear:copyWearDeps` once the module is built on the host.

Not built in this session: the module needs the Android SDK. Sources are the minimum
that implements the contract above.
