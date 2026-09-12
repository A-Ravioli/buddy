# 05. Roadmap

Six phases. Each ends with the founder living on the phone at that phase's level, and
each is gated on the safety metrics from doc 00 (regret rate, missed-critical, battery)
staying inside bounds for the phases before it.

The fork is Phase 0. Always-on audio and content capture both need framework changes,
so there is no "system app on someone else's build" shortcut; the build pipeline is the
first deliverable.

## Phase 0: the build (weeks 1 to 8)

Goal: a buddy Android build on the founder's Pixel 10 Pro XL, updating over the air,
with buddy able to see everything and write it to a ledger. No cognition.

- Build farm, GrapheneOS source checkout, platform and release keys, verified-boot
  key, bootloader relocked. OTA server so updates never wipe.
- Framework patch set v0, each patch small and separately revertible:
  content capture service set to buddy; secure-window and playback-capture overrides
  (if the decisions in doc 06 say yes); input injection; notification ranker hook;
  audio policy for a concurrent low-priority capture stream; SELinux domains for the
  buddy subsystems.
- buddy system services: capture, ledger, surface (a debug timeline only). Audio
  domain runs hotword and VAD only.
- Roles claimed: launcher, assistant and voice interaction service, SMS, dialer,
  notification listener.
- Ledger: Room schema, SQLCipher, keystore-bound key, append-only event store, FTS5.
- Perception v0: content capture parsers for the founder's top ten apps, notification
  listener, SMS provider, calendar provider, call log, contacts, location.
- Battery baseline: screen-off drain per hour with capture and hotword running.

Exit: a week of the founder's real traffic in the ledger, replayable. Content capture
coverage measured per app. Screen-off drain within a set budget.

## Phase 1: triage, the brief, and hearing (weeks 9 to 16)

Goal: the phone stops interrupting, and it starts to hear.

- On-device model runtime and the triage classifier. Structured field extraction.
- Entity graph v0: people with cross-app identity resolution, threads, recurring.
- Notification interception at the ranker: drop and file never post; everything else
  is batched.
- Morning and evening brief, text first, delivered as one notification and as a
  screen. Escalation queue screen.
- Audio tiers 2 and 3: speaker enrolment and verification, streaming ASR on the NPU,
  gated by situation, with the daily minutes budget (three hours). Phone calls
  transcribed through the dialer. `utterance` events in the ledger. Off-limits
  situations enforced. Pixel Buds Pro 2 paired as the command mic and hotword source.
- Cloud cognition v0: the planning cycle that produces the brief, with the cached
  system prefix, context slices, and untrusted envelopes. No action tools yet.
- Eval harness: replay a day of ledger through triage and brief, score against the
  founder's labels. Battery per transcribed minute measured.

Exit: unlocks per day halve. Triage precision on "drop" above the founder's bar. Zero
missed-critical over two weeks. Transcription accurate enough that the founder trusts
the meeting summaries. Battery within budget with audio on.

## Phase 2: acting in reversible domains (weeks 17 to 24)

Goal: buddy does the boring work.

- Policy engine with action classification, autonomy levels, hold windows, hard limits,
  timeline with undo. Runs in its own domain; actuation in another.
- Actuation v0: notification actions, calendar provider writes, Gmail API, input
  injection recipes verified by content capture.
- Connectors: Gmail (full), calendar (full), SMS (send), the founder's main messaging
  apps via injection recipes.
- Playbooks: email filing and archiving at level 2; invites at level 2; drafts for
  everything else at level 1. Verbal commitments from calls and conversations become
  tasks and expectations.
- Style model per relationship class, trained on sent history, used to score drafts.
- Memory consolidation in idle cycles, including conversation summaries; corrections
  feed notes.
- Injection corpus v0, including spoken and on-screen injections, in the eval harness;
  the second-opinion check on external actions; speaker-gate tests.

Exit: most inbound email never touches the founder. Regret rate under target for four
weeks. No successful injection in the corpus. Trust ladder promotes email replies to
level 2 for organisations.

## Phase 3: the long tail of apps (weeks 25 to 36)

Goal: buddy reaches every app; the screen is optional.

- Earbud surface completed: spoken brief, dictated replies, whispered call summaries,
  spoken corrections. (Earbuds as the command mic land in Phase 1.)
- Call handling: screening, voice replies through TTS into the uplink, outbound calls
  with hold handling. VoIP transcription through playback capture.
- Injection recipes for the founder's remaining apps: delivery carriers, the bank, the
  main retailer, the airline. Accessibility fallback for apps content capture misses.
- Vision fallback: screenshot plus frontier model for one-off tasks in apps without a
  recipe, always at level 1.
- Money, shopping, and travel playbooks.
- Situation model from audio and sensors driving do-not-disturb, ring behaviour,
  quiet hours, and brief timing.
- Settings-as-conversation: standing instructions edited by talking to buddy.

Exit: the founder has not opened a delivery, banking, or airline app in a month, and
goes a full day without unlocking.

## Phase 4: hardening and the second-user build (weeks 37 to 44)

Goal: the build is something a second person could run.

- Security review of the SELinux policy and the framework patches by someone who did
  not write them. Findings fixed before anything else in this phase.
- Remove the launcher grid and SystemUI chrome that assume a viewer.
- Pixel Watch as the escalation surface: one-tap resolve, haptic cue when
  transcription starts, the brief on the wrist when earbuds are out.
- Bystander controls finalised and tested: the transcription cue, the stop phrase,
  off-limits detection from calendar and location.
- Onboarding flow: profile bootstrap from history, voice enrolment, autonomy defaults,
  first-week trust ladder.

Exit: a clean build from a fresh checkout, security review closed, battery and safety
metrics unchanged.

## Phase 5: second user (weeks 45 onward)

- A second person lives on the phone for a month with the metrics inside bounds. They
  are added only after Phase 4 exits and the founder has had four consecutive weeks
  with every gate green.

## Cross-cutting workstreams

- **Eval** runs from Phase 1 and grows every phase. Every behaviour change is replayed
  against labelled ledger days before it ships to the phone.
- **Battery** is measured from Phase 0 and is a release gate from Phase 1. Screen-off
  drain per hour, watt hours per transcribed hour, and the daily transcription budget
  are tracked per build.
- **Cost** is tracked per day from Phase 1. The budget cap is enforced from Phase 2.
  Levers in order: on-device triage rate, cache hit rate on the system prefix, effort
  per task class, worker model for bulk reads.
- **Rebase** onto the monthly GrapheneOS security release is a scheduled task from Phase 0.
- **Docs** stay in this folder and are updated as decisions land.

## Success gates between phases

| Gate | Requirement |
|---|---|
| Regret rate | Under target for the trailing four weeks, per domain being promoted |
| Missed critical | Zero in the trailing two weeks |
| Injection corpus | All cases, spoken and on-screen included, blocked by policy, not just by the prompt |
| Battery | Screen-off drain and audio cost within the budget set in Phase 0 |
| Founder verdict | "I did not open that app this month" for the domains the phase covers |
