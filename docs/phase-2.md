# Phase 2 status

Phase 2 from the roadmap: buddy does the boring work. The policy engine, actuation
with holds and undo, the first connectors, the act loop, the style model, memory
consolidation, and the injection corpus.

Built on the Phase 1 branch; separate PR.

## What exists

| Roadmap item | State | Where |
|---|---|---|
| Policy engine: action classification, five autonomy levels, hold windows, hard limits in code, ceilings | **Done, tested** | `core/policy` |
| Trust ladder: promotion on evidence with confirmation, immediate demotion on triggers | **Done, tested** | `core/policy/TrustLadder.kt` |
| Actuation: action registry, connectors, executor with precondition, execute, verify, record; holds that survive restarts; veto; undo as a correction event | **Done, tested** | `core/actuation` |
| Email connector: archive, label, reply, unsubscribe over IMAP and SMTP | Written; message building **tested**, network path needs an account | `core/actuation/mail` |
| Notification actions, calendar responses and events, SMS sending | Written for the phone | `core/android/.../Connectors.kt` |
| Act loop: single `propose_action` tool, strict schema, every proposal through style, second opinion, policy, executor | **Done, tested with a scripted agent**; live loop written against the SDK and compiled | `core/cognition/ActPlanner.kt`, `AnthropicCloudAgent.kt` |
| Style model per relationship class, learned from sent history, gating drafts | **Done, tested** | `core/style` |
| Memory consolidation in idle cycles, corrections weighted highest, notes as events | **Done, tested with a fake model**; idle job written for the phone | `core/cognition/MemoryConsolidator.kt`, `IdleJobService.kt` |
| Injection corpus and the proof that policy stops every case at maximum trust with a fully fooled model | **Done, tested** | `eval/injection` |
| Second-opinion check on outward and irreversible actions | Done; a cheap call on a smaller model | `ActPlanner.kt`, `Prompts.INJECTION_CHECK` |
| Timeline with undo; brief screen with held actions and veto | Written for the phone | `core/android/ui` |
| Playbook defaults: email and device at hold, everything else at draft; money capped at act, accounts at draft | Done | `PolicyProfile` |

## Decisions made while building

- **One tool, not one per action.** The model proposes through a single `propose_action`
  tool whose `action` is an enum of the registry. Adding an action is one entry in the
  registry; the schema, the policy classification, and the connector lookup all key
  off it. Strict mode means the connector never sees a malformed payload.
- **Policy sees the world, the model sees a verdict string.** Every proposal returns to
  the model as `run`, `hold`, `escalate`, or `deny` with reasons. The playbook tells it
  those are outcomes, not errors, so it does not try again with different wording.
- **Style is a gate, not a generator.** A draft to a person must score above a threshold
  against the learned features for that relationship class, or it escalates with the
  score attached. With no samples the score is neutral and the gate is open.
- **Holds are ledger events.** A held action is an ACTION event with a release time;
  release and veto supersede it. The hold alarm re-reads the ledger, so holds survive a
  process restart and the brief screen shows them without extra state.
- **Undo is a correction with a criticality flag.** Ordinary regret and critical regret
  are both recorded; the ladder demotes on critical regret and on suspected injection.
- **The email connector opens a connection per call.** The phone sleeps; long-lived
  IMAP sessions do not.

## Cloud cost shape

Per cycle: the act loop's turns (each reusing the cached prefix), one small second-
opinion call per outward proposal, and the brief. The idle cycle adds one memory call
a day. Token counts land in the ACTION and BRIEF events.

## What needs the build host

1. Everything from Phases 0 and 1.
2. `./gradlew :core:cognition:copyCloudDeps` now also carries jakarta.mail.
3. An email account file at `files/mail/account.properties` for the mail connector
   (address, password or app password, hosts); without it email actions fail closed.
4. `files/profile/policy.properties` to set levels, known payees, never-contacts, quiet
   hours. Defaults are the plan's day-one settings.

## What needs the phone

- The notification action path against real apps: which offer inline reply and
  mark-read actions, which do not.
- Calendar attendee updates against the founder's calendar provider.
- Four weeks of regret rate under target before the ladder proposes anything.

## Exit criteria (from the roadmap)

- [ ] Most inbound email never touches the founder
- [ ] Regret rate under target for four weeks
- [ ] No successful injection in the corpus (passes in CI now; grows with real attempts)
- [ ] Trust ladder promotes email replies to level 2 for organisations
