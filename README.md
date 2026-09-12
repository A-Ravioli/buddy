# buddy

**The phone you never have to look at.**

buddy is an agent-first version of Android. Instead of a grid of apps that each demand
attention, the phone runs a single always-on agent that reads everything the apps
produce, holds the full context of your life, and acts on your behalf. Email, messages,
calendar, deliveries, bills, bookings, social: handled in the background. You hear from
the phone only when it needs a decision from you, and it tries to make that rare.

This repo currently holds the plan. Read it in order:

| Doc | What it covers |
|---|---|
| [docs/00-vision.md](docs/00-vision.md) | What "agent-first" means, the principles, and what success looks like |
| [docs/01-architecture.md](docs/01-architecture.md) | System layers, the Android integration strategy, model strategy, tech stack |
| [docs/02-context-and-memory.md](docs/02-context-and-memory.md) | The life ledger: how the agent gets context from every app and remembers it |
| [docs/03-autonomy-and-trust.md](docs/03-autonomy-and-trust.md) | Autonomy tiers, action gating, undo, prompt injection, security and privacy |
| [docs/04-domain-playbooks.md](docs/04-domain-playbooks.md) | Per-domain behaviour: email, messaging, calendar, money, travel, calls, and more |
| [docs/05-roadmap.md](docs/05-roadmap.md) | Phased build plan with milestones, metrics, and repo layout |
| [docs/06-open-questions.md](docs/06-open-questions.md) | Decisions still to make and known risks |

## The one-paragraph version

Build it in three stages. Stage one is a privileged system app on a de-Googled AOSP
build (GrapheneOS or LineageOS on a Pixel) that becomes the default launcher, assistant,
SMS app, dialer, and notification listener, and is enrolled as device owner. That gets
roughly ninety percent of the reach with none of the ROM maintenance. Stage two moves
the pieces that hit platform walls (background execution, cross-app data access,
always-on audio) into the OS as system services in a buddy AOSP fork. Stage three is
the hardware: a phone whose default state is screen-off, with voice and a watch or
earbuds as the primary surface. Throughout, context stays on the device in a local
ledger, a small on-device model triages the firehose, and a frontier model in the cloud
plans and acts only on the slice of context each task needs.
