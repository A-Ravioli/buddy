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
│  PERCEPTION     content capture · notifications · connectors · audio │
│  ACTUATION      APIs/intents · app automation · screen driving       │
├──────────────────────────────────────────────────────────────────────┤
│  PLATFORM       buddy AOSP fork · system services · SELinux domains  │
└──────────────────────────────────────────────────────────────────────┘
```

Everything flows event-first. An event arrives (a message, a notification, a calendar
change, a location change, an utterance, a timer). Perception normalises it into the ledger. Triage
decides whether it needs cognition now, later, or never. Cognition, when invoked, pulls
the relevant slice of context, plans, and proposes actions. The policy engine decides
whether each action runs, waits, or escalates. Actuation runs it. The ledger records the
outcome. The surface shows the user only what the policy engine escalated.

## Platform layer: the fork is day one

Two requirements decide this: **always-on audio** and **content capture across apps**.
Neither can be granted to an app at runtime, even a device-owner app. Both are wired at
the framework level (config overlays, signature permissions, audio policy), so buddy is
an Android build from the first commit, not an app on someone else's build.

### What has to live in the framework

| Capability | Why an app cannot have it | What the fork does |
|---|---|---|
| Content capture from every app | The `ContentCaptureService` implementer is fixed by the framework config overlay and must be a platform-signed system component | Set buddy as the content capture service; add a framework patch so windows flagged secure and apps that opt out still report to buddy (decision, see doc 06) |
| Always-on hotword | Needs the voice interaction service role plus the SoundTrigger HAL; the sandboxed hotword process is a system component | buddy is the voice interaction service; hotword runs on the DSP through SoundTrigger; the isolated hotword detection process is buddy's |
| Continuous ambient capture | Android hands the microphone to one client at a time; the privacy indicator and capture policy are framework code | Audio policy gives buddy's capture domain a permanent, concurrent, low-priority mic stream; indicator behaviour is a decision (doc 06) |
| Both sides of a phone call | Voice-call audio source needs a signature-level capture permission | buddy is the dialer with that permission; call audio is transcribed on-device |
| Audio from VoIP calls and media | Apps opt out of playback capture by manifest flag | Framework patch lets buddy's capture domain ignore the opt-out (decision, doc 06) |
| Input injection into any app | Signature-level permission | buddy's actuation domain holds it; no Accessibility service needed for taps and text |
| Notification interception before delivery | Ranking and posting are framework code | A notification ranker hook lets buddy decide what is shown before it is posted |
| Survive Doze and app standby | Device owner exemption is enough for an app; a system service is simpler and cannot be killed | Core buddy services run as persistent system services |
| Isolation between buddy's own parts | Apps get one SELinux domain | Each buddy subsystem gets its own SELinux domain with least privilege (see doc 03) |

Everything else buddy needs (launcher, assistant, SMS, dialer, notification listener
roles; usage stats; contacts; calendar; location) works the same way in the fork as it
would have for a system app, and stays app-level code inside the build.

### Base

Plain **AOSP for Pixel**, built from the monthly tags with Google's published vendor
binaries, bootloader relocked with our own verified-boot key. Reasons over GrapheneOS as
the base:

- The patch set is now deep (audio policy, content capture, window manager, SELinux
  policy). Rebasing it on GrapheneOS's fast release cadence and its hardening patches
  in the same files is a steady tax for one team.
- GrapheneOS deliberately removes or restricts several of the privileged paths above.
  Re-enabling them means fighting the base.
- Pixel's Tensor SoC has the DSP hotword path and an NPU that on-device speech and
  triage models need, and AOSP for Pixel exposes both.

GrapheneOS remains the right base to port to once the patch set is stable, for its
hardening and its sandboxed Play Services. That is a later phase, not the start.

### What this costs

- A build farm (a machine with 64 GB or more of memory and a fast disk; hours per full
  build), a signing setup with platform, release, and verified-boot keys, and an OTA
  pipeline so the founder's phone can update without a wipe.
- A monthly rebase onto the new AOSP security tag.
- Around four to six extra weeks before any buddy feature ships, spent on the build and
  provisioning path. The roadmap in doc 05 absorbs this in Phase 0.
- No Google Play Services in the first build. Apps that hard-depend on them (some
  banking, Google Wallet, RCS through Google's stack) work only after microG or
  sandboxed Play Services are integrated. Tracked in doc 06.

### Hardware posture

Unchanged: a Pixel with the screen off by default, earbuds and a watch as the primary
surface. Tensor is now a hard requirement for the on-device audio path.

## Perception layer

Every input is normalised to one **Event** record:

```
Event {
  id, ts, source_app, channel,           // gmail, sms, whatsapp, calendar, bank, speech, screen, ...
  kind,                                  // message, notification, calendar_change, location, utterance, screen_state, ...
  actor,                                 // resolved Person entity or raw sender / speaker
  thread_id,                             // conversation grouping across sources
  content { text, attachments, structured }, // structured = parsed fields (amount, date, tracking no.)
  trust: "untrusted",                    // ALL external content is untrusted; see doc 03
  raw_ref                                // pointer to the original for replay (never raw audio)
}
```

Perception sources, in order of preference:

1. **Native APIs and connectors.** Gmail API, CalDAV or Google Calendar API, IMAP, bank
   APIs where available, carrier RCS. Structured, reliable, reversible. Use whenever an
   app has one.
2. **Content capture.** The framework streams every view's text and structure changes
   from every app to buddy, continuously, without the app being in the foreground for
   a screenshot and without the Accessibility API's latency. This is the primary read
   path for apps without an API: the message list, the order status, the form, the
   notification detail. Standard View-toolkit apps report reliably; Compose, Flutter,
   and WebView content report partially or not at all on some releases, so the next two
   paths stay.
3. **Notification stream.** Cheap, universal, covers every app. Enough for triage and
   for most read paths, and notification actions cover a large share of the act paths.
4. **UI tree via Accessibility.** Fallback for apps whose views do not report through
   content capture. Needs per-app adapters that know the view hierarchy.
5. **Screenshots plus vision.** Last resort, for apps that render custom views. The
   frontier model reads the screen. Slow and expensive, so cached aggressively.
6. **Audio.** The audio pipeline below. Produces `utterance` events (who said what,
   when, in which situation) and situation signals (in a meeting, in a car, TV on).
7. **Sensors.** Location, motion, connectivity, battery, calendar time. These set the
   *situation* (in a meeting, driving, asleep, abroad) that policy uses.

Each app gets a **connector**: a module that knows how to read from and write to that
app through the best available path, and how to translate between the app's concepts
and buddy's Event and Action schemas. With content capture as the default read path,
most connectors are a parser for that app's view content plus an actuation recipe,
which is far less brittle than Accessibility-driven scraping.

## Audio pipeline

"Always-on" cannot mean "transcribe everything all day". A streaming speech model on
the phone's NPU draws on the order of a watt, and a Pixel battery holds roughly 18 watt
hours, so all-day transcription is the whole battery. The pipeline is tiered so the
expensive stage runs only when there is speech worth hearing.

| Tier | Runs on | Rough power | Always on? | Output |
|---|---|---|---|---|
| 0 Hotword | Audio DSP via SoundTrigger | Negligible | Yes | Wake event |
| 1 Voice activity and scene | Application processor, tiny model | Tens of milliwatts | Yes | Speech present or not; scene (conversation, TV, car, silence) |
| 2 Speaker gate | Application processor, small model | Low, only while speech present | While speech present | Is the user speaking; is a known voice speaking |
| 3 Streaming transcription | NPU, Whisper-class or streaming model | Around a watt | Only while speech present and the situation says it matters | Utterance events with speaker labels |
| 4 Understanding | On-device triage model, then cloud for hard cases | As per cognition | Per utterance batch | Commitments, requests, situation updates, commands |

Rules that keep this workable:

- **Tier 3 is gated by situation, not just by speech.** A meeting on the calendar, a
  phone call, a detected two-way conversation with the user speaking, or the hotword.
  Background TV and other people's conversations the user is not part of are
  transcribed only if the user has turned that on.
- **Raw audio is never persisted.** Transcripts are, as `utterance` events. Speaker
  embeddings for the user (enrolled) and for people the user chooses to enrol are the
  only voice data kept.
- **A daily transcription budget** in minutes, enforced by policy, with the brief
  reporting usage. Start with a couple of hours a day, which costs a modest share of the
  battery, and tune from there.
- **Commands are speaker-verified.** Only the enrolled user's voice can instruct buddy.
  Every other voice is data (see doc 03 on voice injection).

Sources feeding the pipeline:

- **Ambient microphone.** Through buddy's own capture domain with a concurrent,
  low-priority stream, so other apps using the mic (a video call) are not blocked and
  buddy still hears the user's side.
- **Phone calls.** Both sides through the dialer's voice-call capture. Consent handling
  per jurisdiction is in doc 03.
- **VoIP and media.** Playback capture with the opt-out overridden for buddy, plus the
  mic for the user's side. Same consent handling as phone calls.
- **Earbuds and watch.** When paired, they are the preferred mic for the user's own
  voice: better signal, and the phone can stay in a pocket.

What the audio pipeline gives the rest of the system:

- **Verbal commitments** ("I'll send that tonight", "let's do Thursday") become
  Commitment entities like anything typed.
- **In-person context** the apps never see: what was agreed in a meeting, what a friend
  asked for over dinner, what the doctor said.
- **Situation** with far better fidelity than sensors alone: in a meeting, in a
  conversation, alone, driving with a passenger.
- **The voice surface itself**: hotword, dictated replies, spoken corrections.

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
3. **App automation through the connector.** Framework-level input injection driven by
   the connector's recipe, with content capture confirming each screen state before the
   next step. No Accessibility service in the loop, so it is faster and does not
   announce itself to the app. Aborts to escalation on drift.
4. **Screen driving with vision.** The computer-use pattern: screenshot, model picks the
   next action, repeat. Reserved for one-off tasks in apps without a recipe. Always
   runs at the most conservative autonomy level.
5. **Voice.** Speaking on a call (screening, holding, confirming an appointment) through
   on-device text-to-speech routed into the call's uplink.

Every actuation is wrapped in a transaction: preconditions checked, action taken,
postcondition observed (did the message actually send, did the event actually appear,
did the screen change as expected), result written to the ledger. Failures escalate;
they never retry blindly.

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
| Base OS | AOSP for Pixel, monthly tags, own verified-boot keys; GrapheneOS port later | Deep framework patches are cheapest to carry on plain AOSP; Tensor gives the DSP hotword path and NPU |
| Persistence | SQLite via Room, `sqlite-vec` for embeddings, SQLCipher for encryption at rest | Local, fast, one file to back up |
| On-device model | AICore / Gemini Nano where present; llama.cpp with a quantised 2 to 4B model otherwise | Triage and extraction without network |
| Embeddings | On-device small embedding model | Retrieval without leaking content |
| Cloud reasoning | Anthropic Java SDK from Kotlin; Messages API with tool use, prompt caching, structured outputs, adaptive thinking | Frontier planning and action |
| Scheduling | Persistent system services for perception, audio, and policy; WorkManager for idle-time jobs | Cannot be killed; no Doze workarounds |
| Audio | SoundTrigger hotword on the DSP; on-device VAD, speaker verification, streaming ASR on the NPU; on-device TTS | Privacy, latency, battery |
| Isolation | One SELinux domain per buddy subsystem; policy engine and actuation in separate processes | Least privilege inside buddy itself |
| Testing | Replayable ledger fixtures; connector contract tests against app UI snapshots; policy tests as truth tables | Deterministic agent evaluation |
| Observability | Local structured logs; opt-in export of anonymised traces for eval | Debug without leaking |

## Module layout (target)

```
buddy/
  platform/        AOSP manifest and patch set (audio policy, content capture, window manager,
                   notification ranker, input injection), SELinux policy, signing, OTA, build scripts
  core/
    ledger/        event store, entity graph, profile, retrieval index
    perception/    content capture service, notification listener, accessibility fallback, sensors, connector host
    audio/         hotword, VAD, speaker gate, streaming ASR, call and playback capture, TTS
    cognition/     triage model runtime, cloud agent loop, prompt assets, tool registry
    policy/        autonomy levels, gating rules, budgets, hard limits
    actuation/     action executor, transactions, undo, verification
    surface/       brief, escalation queue, voice, timeline
  connectors/      one module per app: gmail, sms, whatsapp, calendar, bank-*, delivery-*
  eval/            replay harness, regret-rate scoring, injection test corpus
  docs/            this plan
```
