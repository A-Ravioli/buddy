# Adding a second user

Decision 9 in `docs/06-open-questions.md`: the second user joins only after Phase 4
exits, with a security review by someone else, a clean build from a fresh checkout,
and four consecutive weeks of every gate green on the founder's phone. This is the
procedure.

## Before

1. `./gradlew :eval:metrics:run --args="founder-ledger.db 28"` prints the gate
   report. Every hard gate must pass: enough days, missed critical, critical regrets,
   regret rate, battery, transcription budget.
2. `docs/security-review.md` closed, findings fixed, re-run.
3. A build from a fresh checkout on the build host, flashed to a second Pixel 10 Pro XL
   with `platform/scripts/flash.sh`.

## The first day

1. Unlock the phone once. Let buddy observe: the ledger fills from the notification
   backfill, the SMS, call log, and calendar providers.
2. `platform/scripts/provision-user.sh <dir>` with the API key and, if wanted, the mail
   account. Triage and policy seeds are optional; onboarding infers defaults.
3. The onboarding screen shows what was inferred, in plain words. The user corrects
   anything wrong by talking to buddy ("Sam is my sister, not a colleague"); each
   correction is a standing instruction in the ledger.
4. Voice enrolment when prompted, so only their voice gives commands.
5. Pair the earbuds; the watch if they have one.

## The first week

- Autonomy starts at the day-one defaults: everything at draft, email and device at
  hold. The first-week ladder proposes each step in the brief; nothing moves without
  a yes.
- The brief runs at 07:30 and 19:30. The user should read or hear both for the first
  few days and undo anything they disagree with. Undo is the strongest signal buddy
  gets.
- The stop phrase works from any voice; tell people who share the space.

## The first month

- `battery-baseline.sh` after the first full day, and again after audio tier 3 is on.
- The gate report weekly. A missed critical item or a critical regret freezes the
  ladder until the cause is fixed.
- At day 28 with the gates green, the ladder may propose money at hold. Known payees
  are added by the user, never inferred.

## What is different for a second user

Nothing in the code. The profile, the ledger, and the keys are per phone. The only
shared things are the build and the plan.
