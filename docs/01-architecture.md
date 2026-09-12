# 01. Architecture

## Overview

buddy is five layers stacked on Android, with a policy engine that sits between
reasoning and action.

```
┌──────────────────────────────────────────────────────────────────────┐
│  SURFACE        brief · escalation queue · voice · watch · timeline  │
├──────────────────────────────────────────────────────────────────────┤
│  COGNITION      triage (on-device)  →  plan + act (cloud frontier)   │
│                          ↕ POLICY ENGINE (autonomy, gating, budget)  │
├──────────────────────────────────────────────────────────────────────┤
│  CONTEXT        life ledger · entity graph · commitments · profile   │
├──────────────────────────────────────────────────────────────────────┤
│  PERCEPTION     notifications · app connectors · UI tree · sensors   │
│  ACTUATION      APIs/intents · app automation · screen driving       │
├──────────────────────────────────────────────────────────────────────┤
│  PLATFORM       AOSP (GrapheneOS base) · device owner · system svcs  │
└──────────────────────────────────────────────────────────────────────┘
```

Everything flows event-first. An event arrives (a message, a notification, a calendar
change, a location change, a timer). Perception normalises it into the ledger. Triage
decides whether it needs cognition now, later, or never. Cognition, when invoked, pulls
the relevant slice of context, plans, and proposes actions. The policy engine decides
whether each action runs, waits, or escalates. Actuation runs it. The ledger records the
outcome. The surface shows the user only what the policy engine escalated.

## Platform layer: how deep into Android

Three stages, each unlocked by hitting the walls of the one before.

### Stage 1: privileged system app on a stock AOSP build

Base: GrapheneOS on a Pixel (fallback: LineageOS). buddy ships as a single system app
signed with the platform key, installed to the system partition, and enrolled as
**device owner** at first boot. That single app holds every role that gives reach:

| Android role or permission | What it gives buddy |
|---|---|
| Default launcher (`ROLE_HOME`) | Owns the home screen; can make it a blank brief instead of an app grid |
| Default assistant (`ROLE_ASSISTANT`) | Long-press and voice hotword invoke buddy; gets assist structure from the foreground app |
| Default SMS app (`ROLE_SMS`) | Full read and send over SMS, MMS, RCS where the carrier stack allows |
| Default dialer (`ROLE_DIALER`) | Place and answer calls, in-call UI, call screening (`CallScreeningService`) |
| `NotificationListenerService` | Every notification from every app, including actions like reply and mark-read |
| `AccessibilityService` | Read the UI tree of any foreground app and inject taps, text, scrolls |
| `MediaProjection` (persistent, as device owner) | Screenshots for vision-based understanding when the UI tree is not enough |
| Device owner | Exempt from background execution limits, silent install of connectors, lock task, policy control, persistent foreground service without user-dismissable notification |
| Usage stats, call log, contacts, calendar provider | Structured history and identity data |
| Companion device manager | Pair a watch or earbuds as a first-class surface |

Why stage one is a system app and not a ROM: the wall between a device-owner system app
and a custom ROM is thin, and a system app is orders of magnitude cheaper to iterate on.
Almost every "the agent can see and do X" requirement lands inside this envelope.

### Stage 2: buddy AOSP fork with system services

Move into the OS when these show up as real blockers:

- **Always-on audio.** Continuous transcription of calls and ambient voice commands
  needs a system audio HAL client, not an app.
- **Cross-app data without the Accessibility path.** A system content-capture service
  (Android has `ContentCaptureService`, used today by autofill) can receive structured
  view content from every app without the Accessibility API's latency and brittleness.
- **Interception before delivery.** A notification ranking service and a message routing
  service in the framework let buddy decide what the user sees before the notification
  exists, instead of cancelling it after.
- **Network-level visibility.** A VPN service can run as an app, but a system-level
  packet filter is cheaper and cannot be disabled by another app.
- **Removing the app grid entirely.** Ship without a launcher, settings, or SystemUI
  chrome that assumes the user is looking.

The fork is GrapheneOS with buddy patches, tracked as a set of feature branches rebased
on each upstream release. Everything that can stay in the system app stays there.

### Stage 3: hardware posture

Not a custom phone. A Pixel with the screen off by default, always-on display disabled,
and a watch or earbuds as the primary interface. The phone becomes a modem, a sensor
pack, and a compute node. The only screen surface is the escalation queue.

## Perception layer

Every input is normalised to one **Event** record:

```
Event {
  id, ts, source_app, channel,           // gmail, sms, whatsapp, calendar, bank, ...
  kind,                                  // message, notification, calendar_change, location, ...
  actor,                                 // resolved Person entity or raw sender
  thread_id,                             // conversation grouping across sources
  content { text, attachments, structured }, // structured = parsed fields (amount, date, tracking no.)
  trust: "untrusted",                    // ALL external content is untrusted; see doc 03
  raw_ref                                // pointer to the original for replay
}
```

Perception sources, in order of preference:

1. **Native APIs and connectors.** Gmail API, CalDAV or Google Calendar API, IMAP, bank
   APIs where available, carrier RCS. Structured, reliable, reversible. Use whenever an
   app has one.
2. **Notification stream.** Cheap, universal, covers every app. Enough for triage and
   for most read paths. Notification actions (reply, archive, mark read) cover a large
   share of the act paths too.
3. **UI tree via Accessibility or content capture.** For apps with no API: read the
   conversation view, the order status, the form. Needs per-app adapters that know the
   view hierarchy.
4. **Screenshots plus vision.** Last resort, for apps that render custom views. The
   frontier model reads the screen. Slow and expensive, so cached aggressively and used
   only when the tree path fails.
5. **Sensors.** Location, motion, connectivity, battery, calendar time. These set the
   *situation* (in a meeting, driving, asleep, abroad) that policy uses.

Each app gets a **connector**: a module that knows how to read from and write to that
app through the best available path, and how to translate between the app's concepts
and buddy's Event and Action schemas. Connectors are the long tail of the project.

## Context layer

Detailed in doc 02. In short: a local, encrypted, append-only **life ledger** of events;
an **entity graph** of people, places, organisations, threads, and commitments extracted
from it; a **profile** of the user's preferences, style, and standing instructions; and
a **retrieval index** (full text plus embeddings) so cognition can pull the right slice.

## Cognition layer

Two tiers, chosen for cost, latency, and privacy.

### Tier 1: on-device triage

Runs on every event, on the phone, with no network. A small model (Gemini Nano through
AICore where available, otherwise a 2 to 4 billion parameter open model through
llama.cpp or MLC, quantised) plus classical rules. It answers one question per event:

- **Drop.** Marketing, noise, already-handled duplicates. Logged, never surfaced.
- **File.** Informational, no action. Extract structured fields, update entities, done.
- **Act now.** Something needs doing and a response is time-sensitive.
- **Act batched.** Something needs doing but can wait for the next planning cycle.
- **Escalate.** Clearly needs the human (a decision, an emotional message, an emergency).

Triage also extracts entities and structured fields (amounts, dates, tracking numbers,
addresses) so the ledger is queryable without the cloud.

### Tier 2: cloud planning and action

The frontier model, invoked for "act" and for periodic planning cycles. Default:
Claude Opus 5 (`claude-opus-5`) with adaptive thinking and effort tuned per task class.
Cheaper worker calls (bulk summarisation, style-matched drafting of low-risk replies)
go to Claude Sonnet 5 or Haiku 4.5. The most capable tier (Fable 5.1) is reserved for
the daily planning cycle and for escalated reasoning where correctness dominates cost.

The agent loop runs **on the phone** in a foreground service, calling the Messages API
with tool use. The phone owns the loop, the tools, and the data; the cloud sees a
request and returns a response. Managed Agents would be simpler to run, but it would
move the tool execution and the context slice off the device, which contradicts the
"context stays home" principle. Revisit if the on-phone loop proves too heavy.

Request shape, designed for cache stability and injection resistance:

```
tools:    [stable tool set for this domain, deferred loading for the long tail]
system:   [buddy constitution + user profile + domain playbook]   ← cached prefix
messages: [
  user:   { situation, task, context slice as UNTRUSTED envelopes }
  system: { operator instructions for this turn }                 ← mid-conversation operator channel
  ...agent loop...
]
```

- The system prefix (constitution, profile, playbook) changes rarely and is cached.
- The context slice for the task goes in the user turn, each event wrapped in an
  untrusted-content envelope with its source and trust level.
- Per-turn operator instructions ("you may not send anything in this cycle", "the user
  is asleep, escalations wait") go through the mid-conversation system channel so they
  carry operator authority and do not break the cached prefix.
- Structured outputs for anything that must be machine-parsed (triage results, action
  proposals). Strict tool schemas for every action tool.
- Server-side fallbacks enabled so a refusal on the primary model routes to a fallback
  rather than dropping the task.

Every action the model proposes is a **typed tool call**, never free text and never a
shell command. That is what lets the policy engine gate it.

### Planning cycles

Besides event-driven work, the agent runs scheduled cycles:

| Cycle | When | What |
|---|---|---|
| Morning | User's chosen wake time | Review overnight, plan the day, produce the morning brief |
| Midday | Around lunch | Handle the batched queue, adjust for schedule changes |
| Evening | Before user's typical wind-down | Close out the day, prepare tomorrow, evening brief |
| Weekly | Sunday evening | Review commitments, recurring bills, upcoming travel, relationship upkeep |
| Idle | Whenever the phone is charging and on wifi | Backfill, re-index, consolidate memory, run evaluations |

## Policy engine

Detailed in doc 03. Sits between cognition and actuation. Every proposed action carries
a domain, a reversibility class, a cost, and a confidence. The engine looks up the
user's current autonomy level for that domain and decides: run, run-with-delay (a short
hold so the user can veto from the brief), or escalate. It also enforces hard limits:
spend caps, never-forward lists, quiet hours, and a per-day budget of cloud tokens.

## Actuation layer

Mirrors perception. Preference order:

1. **API or intent.** Send via the Gmail API. Create the calendar event through the
   provider. Fire an `ACTION_VIEW` or app-specific deep link. Reliable and testable.
2. **Notification actions.** Inline reply, archive, mark-read, snooze. Cheap and covers
   most messaging apps.
3. **App automation through the connector.** Accessibility or content-capture driven
   sequences: open the delivery app, find the order, tap reschedule, pick a slot. Each
   step verifies the screen state before proceeding and aborts to escalation on drift.
4. **Screen driving with vision.** The computer-use pattern: screenshot, model picks the
   next action, repeat. Reserved for one-off tasks in apps without an adapter. Always
   runs at the most conservative autonomy level.

Every actuation is wrapped in a transaction: preconditions checked, action taken,
postcondition observed (did the message actually send, did the event actually appear),
result written to the ledger. Failures escalate; they never retry blindly.

## Surface layer

The user-facing part is deliberately tiny.

- **The brief.** A short spoken or text summary at the planned times: what happened,
  what buddy did, what needs you. Delivered to earbuds, watch, or as a single
  notification.
- **The escalation queue.** One screen. Each item is a decision with the options
  buddy recommends, one tap or one sentence to resolve. Empty most of the day.
- **Voice.** Hotword or long-press. "Tell Sam I'll be late." "What did I agree to with
  the landlord?" "Cancel Thursday."
- **The timeline.** Every action taken, with reasons and an undo button. This is the
  trust surface. It is where the user goes when something feels off.
- **Settings as conversation.** No settings screens. "Stop replying to my mum for me."
  becomes a standing instruction in the profile.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin (app and services), C++ for on-device inference glue | Native Android |
| Base OS | GrapheneOS, Pixel | Security, de-Googled, well maintained |
| Persistence | SQLite via Room, `sqlite-vec` for embeddings, SQLCipher for encryption at rest | Local, fast, one file to back up |
| On-device model | AICore / Gemini Nano where present; llama.cpp with a quantised 2 to 4B model otherwise | Triage and extraction without network |
| Embeddings | On-device small embedding model | Retrieval without leaking content |
| Cloud reasoning | Anthropic Java SDK from Kotlin; Messages API with tool use, prompt caching, structured outputs, adaptive thinking | Frontier planning and action |
| Scheduling | Foreground service under device owner; WorkManager for idle-time jobs | Survives Doze |
| Voice | On-device ASR (Whisper-class model) and TTS | Privacy, latency |
| Testing | Replayable ledger fixtures; connector contract tests against app UI snapshots; policy tests as truth tables | Deterministic agent evaluation |
| Observability | Local structured logs; opt-in export of anonymised traces for eval | Debug without leaking |

## Module layout (target)

```
buddy/
  platform/        AOSP patches, device-owner provisioning, build scripts
  core/
    ledger/        event store, entity graph, profile, retrieval index
    perception/    notification listener, accessibility bridge, sensors, connector host
    cognition/     triage model runtime, cloud agent loop, prompt assets, tool registry
    policy/        autonomy levels, gating rules, budgets, hard limits
    actuation/     action executor, transactions, undo, verification
    surface/       brief, escalation queue, voice, timeline
  connectors/      one module per app: gmail, sms, whatsapp, calendar, bank-*, delivery-*
  eval/            replay harness, regret-rate scoring, injection test corpus
  docs/            this plan
```
