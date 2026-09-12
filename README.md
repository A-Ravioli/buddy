# buddy

**The phone you never have to look at.**

buddy is an agent-first version of Android. Instead of a grid of apps that each demand
attention, the phone runs a single always-on agent that reads everything the apps
produce, holds the full context of your life, and acts on your behalf. Email, messages,
calendar, deliveries, bills, bookings, social: handled in the background. You hear from
the phone only when it needs a decision from you, and it tries to make that rare.

The plan lives in `docs/`; Phase 0 code is under way. Read the plan in order:

| Doc | What it covers |
|---|---|
| [docs/00-vision.md](docs/00-vision.md) | What "agent-first" means, the principles, and what success looks like |
| [docs/01-architecture.md](docs/01-architecture.md) | System layers, the Android integration strategy, model strategy, tech stack |
| [docs/02-context-and-memory.md](docs/02-context-and-memory.md) | The life ledger: how the agent gets context from every app and remembers it |
| [docs/03-autonomy-and-trust.md](docs/03-autonomy-and-trust.md) | Autonomy tiers, action gating, undo, prompt injection, security and privacy |
| [docs/04-domain-playbooks.md](docs/04-domain-playbooks.md) | Per-domain behaviour: email, messaging, calendar, money, travel, calls, and more |
| [docs/05-roadmap.md](docs/05-roadmap.md) | Phased build plan with milestones, metrics, and repo layout |
| [docs/06-open-questions.md](docs/06-open-questions.md) | Decisions made, what is still open, and known risks |
| [docs/phase-0.md](docs/phase-0.md) | Phase 0 status: what exists, what is verified, what needs the build host or the phone |
| [docs/phase-1.md](docs/phase-1.md) | Phase 1 status: triage, entities, the brief, the audio gate, the eval harness |
| [docs/phase-2.md](docs/phase-2.md) | Phase 2 status: policy engine, actuation, connectors, the act loop, style, memory |
| [docs/phase-3.md](docs/phase-3.md) | Phase 3 status: app automation, money, logistics, voice, the vision fallback |
| [docs/phase-4.md](docs/phase-4.md) | Phase 4 status: hardening, the watch, bystander controls, onboarding |
| [docs/phase-5.md](docs/phase-5.md) | Phase 5 status: the gate report and the second user |
| [docs/security-review.md](docs/security-review.md) | The review checklist a second person works through before Phase 4 ships |
| [docs/second-user.md](docs/second-user.md) | The procedure for adding a second user |

## Repository layout

| Path | What |
|---|---|
| `core/ledger` | The life ledger: schema, append-only rules, ids, search. JVM, tested. |
| `core/perception` | Normalisers from platform snapshots to ledger events. JVM, tested. |
| `core/triage` | The five-way triage decision and structured field extraction. JVM, tested. |
| `core/entities` | People across apps, threads, recurring charges. JVM, tested. |
| `core/cognition` | Context slices, untrusted envelopes, the Claude client, the brief planner. JVM, tested. |
| `core/audio` | When transcription may run: off-limits rules, daily budget, participant rule. JVM, tested. |
| `core/policy` | The security boundary: action classification, autonomy levels, hard limits in code, the trust ladder, onboarding. JVM, tested. |
| `core/actuation` | Actions, connectors, the executor with holds and undo, the email connector. JVM, tested. |
| `core/style` | How the user writes to each relationship class; a gate on drafts. JVM, tested. |
| `core/automation` | App recipes over captured screens with drift detection. JVM, tested. |
| `core/money`, `core/logistics`, `core/voice`, `core/profile` | Domain logic: money, deliveries and travel, voice commands, profile bootstrap. JVM, tested. |
| `eval/replay` | Replay a ledger through triage and score it against labels. |
| `eval/injection` | The injection corpus and the proof that policy stops every case. |
| `eval/metrics` | The success metrics and the gate report from a ledger. |
| `wear/` | The Pixel Watch app. Built with the Android SDK on the host. |
| `core/android` | The buddy system app, including the surface (the chat, the creature, onboarding). Built by Soong inside the GrapheneOS tree. |
| `core/android-verify` | Compiles the system app on the host against the full framework jar and the Compose API, so CI catches errors in it without the platform tree. |
| `platform/` | Product config, overlays, permissions, SELinux, patch specs, build scripts. |
| `Android.bp` | Soong modules for the app and its config, read when this repo is `vendor/buddy`. |

The surface's design lives on a canvas: https://claude.ai/code/artifact/83f7ec6f-8991-48c1-916f-22a7bd5f91ed

Run the JVM tests, and the compile check for the system app, anywhere with a JDK 17 or newer:

```
./gradlew build
```

## The one-paragraph version

buddy is an Android build, not an app. Two requirements decide that: always-on audio
and content capture across every app, both of which are framework capabilities that no
app can be granted. So Phase 0 is a fork of the GrapheneOS source tree for the Pixel 10 Pro XL, with our own keys,
with buddy's subsystems running as system services in separate SELinux domains. The
perception layer reads every app through content capture, notifications, and APIs. A
tiered audio pipeline listens on the DSP for free, transcribes on the NPU only when the
user is in a conversation that matters, and never stores raw audio. Everything lands in
an encrypted on-device ledger. A small on-device model triages the firehose; a frontier
model in the cloud plans and acts only on the slice of context each task needs, and
every action it proposes passes a policy engine it cannot bypass. The phone's default
state is screen-off in a pocket, with Pixel Buds as the primary surface.
