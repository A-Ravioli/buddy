# 05. Roadmap

Six phases. Each ends with the founder living on the phone at that phase's level, and
each is gated on the safety metrics from doc 00 (regret rate, missed-critical) staying
inside bounds for the phases before it.

## Phase 0: foundations (weeks 1 to 4)

Goal: a phone that buddy can see everything on and write to a ledger, with no cognition.

- Choose and flash the base OS on a Pixel. Sign a platform key. Provision buddy as
  device owner and system app.
- Claim roles: launcher, assistant, SMS, dialer, notification listener, accessibility.
- Ledger: Room schema, SQLCipher, keystore-bound key, append-only event store, FTS5.
- Perception v0: notification listener, SMS provider, calendar provider, call log,
  contacts, location, all writing Events.
- A debug timeline screen that shows the ledger. This is the only UI.

Exit: a week of the founder's real traffic in the ledger, replayable.

## Phase 1: triage and the brief (weeks 5 to 10)

Goal: the phone stops interrupting. Nothing acts yet.

- On-device model runtime and the triage classifier. Structured field extraction.
- Entity graph v0: people with cross-app identity resolution, threads, recurring.
- Notification suppression: buddy cancels notifications it has classified as drop or
  file; everything else is batched.
- Morning and evening brief, text first, delivered as one notification and as a
  screen. Escalation queue screen.
- Cloud cognition v0: the planning cycle that produces the brief, with the cached
  system prefix, context slices, and untrusted envelopes. No action tools yet.
- Eval harness: replay a day of ledger through triage and brief, score against the
  founder's labels.

Exit: unlocks per day halve. Triage precision on "drop" above the bar the founder sets
after living with it. Zero missed-critical over two weeks.

## Phase 2: acting in reversible domains (weeks 11 to 18)

Goal: buddy does the boring work.

- Policy engine with action classification, autonomy levels, hold windows, hard limits,
  timeline with undo.
- Actuation v0: notification actions, calendar provider writes, Gmail API.
- Connectors: Gmail (full), calendar (full), SMS (send), one messaging app via inline
  reply.
- Playbooks: email filing and archiving at level 2; invites at level 2; drafts for
  everything else at level 1.
- Style model per relationship class, trained on sent history, used to score drafts.
- Memory consolidation in idle cycles; corrections feed notes.
- Injection corpus v0 in the eval harness; the second-opinion check on external
  actions.

Exit: most inbound email never touches the founder. Regret rate under target for four
weeks. Trust ladder promotes email replies to level 2 for organisations.

## Phase 3: the long tail of apps (weeks 19 to 30)

Goal: buddy reaches into apps without APIs.

- Accessibility and content-capture bridge with a per-app adapter framework, screen
  state verification, and abort-to-escalate.
- Adapters for the founder's top apps: the messaging apps in use, delivery carriers,
  the bank, the main retailer, the airline.
- Vision fallback: screenshot plus frontier model for one-off tasks in un-adapted apps,
  always at level 1.
- Money playbook: categorisation, anomalies, bill payment within caps at level 2.
- Shopping and travel playbooks.
- Voice input: on-device ASR, hotword, the assistant role wired to buddy.

Exit: the founder has not opened a delivery, banking, or airline app in a month.

## Phase 4: calls and voice-first (weeks 31 to 40)

Goal: the screen is optional.

- Call screening and on-device transcription. Outbound calling with hold handling.
- Spoken brief through earbuds; watch as the escalation surface with one-tap resolve.
- Situation model from sensors driving do-not-disturb, ring behaviour, and quiet hours.
- Settings-as-conversation: standing instructions edited by talking to buddy.

Exit: screen time under the target. The founder goes a full day without unlocking.

## Phase 5: OS integration (weeks 41 onward, as needed)

Goal: remove the walls the system app hit.

- buddy AOSP fork with system services for notification ranking, content capture, and
  audio.
- Remove the launcher grid and SystemUI chrome that assume a viewer.
- Second user onboarding: profile bootstrap from history, autonomy defaults, first-week
  trust ladder.

Exit: a second person lives on the phone for a month with the metrics inside bounds.

## Cross-cutting workstreams

- **Eval** runs from phase 1 and grows every phase. Every behaviour change is replayed
  against labelled ledger days before it ships to the phone.
- **Cost** is tracked per day from phase 1. The budget cap is enforced from phase 2.
  Levers in order: on-device triage rate, cache hit rate on the system prefix, effort
  per task class, worker model for bulk reads.
- **Battery** measured from phase 0. The perception layer must be event-driven with no
  polling loops; this is verified per release.
- **Docs** stay in this folder and are updated as decisions land.

## Success gates between phases

| Gate | Requirement |
|---|---|
| Regret rate | Under target for the trailing four weeks, per domain being promoted |
| Missed critical | Zero in the trailing two weeks |
| Injection corpus | All cases blocked by policy, not just by the prompt |
| Founder verdict | "I did not open that app this month" for the domains the phase covers |
