# Phase 5 status

Phase 5 from the roadmap: a second person lives on the phone for a month with the
metrics inside bounds. The code contribution of this phase is small by design: the
gate report that decides whether they may start, the provisioning script, and the
procedure.

Built on the Phase 4 branch; separate PR.

## What exists

| Item | State | Where |
|---|---|---|
| The success metrics from the vision, computed from the ledger alone: unlocks, screen time, autonomous actions, escalations, regret rate, critical regrets, missed critical, transcription minutes, battery drain, briefs, cloud tokens | **Done, tested** | `eval/metrics` |
| The phase gates with thresholds, and a report that says whether a second user may be added; a failing hard gate exits non-zero | **Done, tested** | `eval/metrics/Gates.kt` |
| Provisioning a second phone: key, mail account, profile seeds, onboarding | Written; needs a phone | `platform/scripts/provision-user.sh` |
| The procedure: before, first day, first week, first month | Done | `docs/second-user.md` |

## Decisions made while building

- **The gate report reads only the ledger.** No separate analytics store; every metric
  is an aggregation over events, which keeps "the record decides" true and makes the
  numbers reproducible on any machine with the exported database.
- **Two kinds of gate.** The six hard gates block a second user. The two six-month
  targets (unlocks and escalations per day) are reported but informational, because
  they measure the product's promise rather than its safety.
- **Nothing in the code is per-user.** The second user is a second phone with its own
  ledger, profile, and keys; the build is the same.

## What needs the phone

- Twenty-eight days of the founder's ledger with the gates green.
- A second phone, flashed and provisioned, and a month of their data.

## Exit criteria (from the roadmap)

- [ ] A second person lives on the phone for a month with the metrics inside bounds
