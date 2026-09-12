# Phase 1 status

Phase 1 from the roadmap: the phone stops interrupting, and it starts to hear. Triage
on every event, the entity graph, notification interception, the morning and evening
brief with the cloud planning cycle, the audio gate, and the eval harness.

Built on the Phase 0 branch; kept as a separate PR so the two can be reviewed and
landed independently.

## What exists

| Roadmap item | State | Where |
|---|---|---|
| Triage classifier: drop, file, act now, act later, escalate, with reasons and confidence | **Rule baseline done, tested.** The on-device model implements the same interface later and is scored by the same harness | `core/triage` |
| Structured field extraction: one-time codes, amounts, tracking numbers, booking references, URLs, phones, date hints | **Done, tested** | `core/triage/Extractors.kt` |
| Entity graph v0: people with cross-app identity resolution, threads with reply expectations, recurring charges | **Done, tested against real SQLite**; merges only on unambiguous names | `core/entities` |
| Notification interception before display | **Written for the phone**: the notification assistant role hides drop and file, lowers act, leaves escalate | `core/android/.../BuddyNotificationAssistant.kt`, overlay |
| Morning and evening brief | **Planner done and tested**; cloud path written against the Claude SDK and compiled; Android scheduler, notification, and screen written | `core/cognition`, `core/android/.../BriefScheduler.kt`, `BriefActivity.kt` |
| Cloud cognition v0: cached system prefix, deterministic context slice, untrusted envelopes, operator channel, structured output, refusal handling | **Done, tested with a fake model**; not yet run against the live API | `core/cognition` |
| Escalation queue screen | Written for the phone | `core/android/.../BriefActivity.kt` |
| Audio tiers 2 and 3 | **Gate logic done and tested**: off-limits rules, daily budget with a reserve for meetings and calls, participant rule, hotword. The speaker gate and streaming ASR runtimes are build-host work | `core/audio` |
| Eval harness: replay a ledger through triage, score against labels | **Done, tested**; missed-critical fails the run | `eval/replay` |
| Battery per transcribed minute | Not started; needs the audio runtime on the phone | |

## Decisions made while building

- **Triage records are events.** Every decision is a TRIAGE event pointing at the
  event it judged, and every brief is a BRIEF event. The timeline shows why a
  notification was hidden, the brief screen reads its queue from them, and the eval
  harness scores from them. Two new kinds in the ledger's closed set.
- **The rule baseline escalates when unsure.** Missing an item is the expensive error,
  so the rules drop only on strong signals (muted, known noise packages, marketing
  language without a receipt) and otherwise file or escalate. The harness reports
  wrong drops separately so this can be tuned against real days.
- **The brief without the cloud is a real brief.** The fallback assembles counts and
  the escalated items verbatim. It runs offline, on refusal, on error, and is the
  baseline the cloud brief is judged against.
- **Hidden fields never reach the model.** One-time codes are stripped from envelopes
  and scrubbed from the model's output if it repeats one anyway. The model also never
  sees the ledger; it sees a slice with a character budget, deterministic for the same
  inputs.
- **The operator channel is a mid-conversation system message.** Per-cycle instructions
  ("quiet hours tonight") go on the operator channel so they carry operator authority
  and leave the cached prefix intact. On a model that rejects the role the client falls
  back to a marked block in the user turn.
- **Names do not merge people on their own.** A name key can belong to several people;
  a name-only sighting joins an existing person only when exactly one person has that
  name. Phone and email are the real identities.

## Cloud cost shape

Per planning cycle, one request: the stable prefix (constitution and playbook, cached
for an hour) plus a slice of at most about fifteen thousand tokens, and a structured
answer. Two cycles a day at that size is small change; the token counts land in the
BRIEF event so the real number is visible from day one.

## What needs the build host

1. Everything from Phase 0's list first.
2. `./gradlew :core:cognition:copyCloudDeps` before the platform build, so Soong can
   import the Claude client and its dependencies (`platform/prebuilts`, git-ignored).
3. An API key at `files/cloud/api_key` in the app's storage on the phone. Without it
   the brief is the fallback, which is the intended first week anyway.
4. Compile errors in the new Android files, as with Phase 0.

## What needs the phone

- Labelled days. The timeline needs its "this should have been X" affordance so the
  founder can label from the phone; until then labels are a CSV written by hand from
  the exported ledger, scored with `./gradlew :eval:replay:run --args="ledger.db labels.csv"`.
- The notification assistant's effect: which apps still interrupt, which are wrongly
  silenced.
- The audio runtime: speaker enrolment, VAD, streaming ASR on the NPU, the earbud
  cue, and the budget accounting. The gate is ready for them.

## Exit criteria (from the roadmap)

- [ ] Unlocks per day halve
- [ ] Triage precision on drop above the founder's bar; zero missed-critical over two weeks
- [ ] Transcription accurate enough to trust the meeting summaries
- [ ] Battery within budget with audio on
