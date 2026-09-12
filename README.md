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

## Repository layout

| Path | What |
|---|---|
| `core/ledger` | The life ledger: schema, append-only rules, ids, search. JVM, tested. |
| `core/perception` | Normalisers from platform snapshots to ledger events. JVM, tested. |
| `core/android` | The buddy system app. Built by Soong inside the GrapheneOS tree. |
| `platform/` | Product config, overlays, permissions, SELinux, patch specs, build scripts. |
| `Android.bp` | Soong modules for the app and its config, read when this repo is `vendor/buddy`. |

Run the JVM tests anywhere with a JDK 17 or newer:

```
./gradlew :core:ledger:test :core:perception:test
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
