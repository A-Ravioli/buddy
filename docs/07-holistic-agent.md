# 07. The holistic agent

Docs 00 to 06 describe buddy as a pipeline: perception fills the ledger, triage sorts it,
and two planning cycles a day ask a model what to say and what to do. That shape was
right for getting the safety boundary built. It is the wrong shape for the product.

This doc is the design for the second shape: **one continuous agent that owns work until
it is finished**, rather than four stateless loops that each wake up, look at a window of
events, and go back to sleep.

The prompt for it is Instinct, which shipped the continuous shape first and is the
clearest existing evidence about what people actually want from a personal agent, and
about which parts of it hurt. What is known about Instinct is in the first section, with
its sourcing marked, because most of it is secondary reporting rather than anything the
company has published. The rest of the doc is buddy's design.

## 1. What is known about Instinct

Instinct is a personal agent from Spear Street Technology, founded April 2026 by Noah
Shinn, first author of Reflexion (NeurIPS 2023) and a contributor to τ-bench, previously
a research scientist at Sierra. It raised $350M total at a $2.5B valuation in August
2026, co-led by Index and Benchmark, four months after founding. Invite-only, free in
private beta, a waitlist reported at 180,000. Reported plans for $200 to $500 a month,
priced against cloud desktop compute; not confirmed by the company.

### The product claims, from the founder

- **"There are no new interfaces."** You text it or you call it. It arrives on iMessage
  and WhatsApp, and later on the iPhone Action button. No app to open, no dashboard.
- **"Trained to use a phone and a computer in the same way that humans do."**
- **Connects to "email, messaging, screen, audio, location, and more."**
- The core model is described as trained on "the personal texture of everyday life":
  following up on threads you dropped, calling or texting you first, arranging the ride
  to the airport.

### The architecture, as reported and inferred

Nothing below is from a published design doc; it is press coverage, user reports, and
inference from behaviour. Treated here as a hypothesis to design against, not fact.

| Element | What is reported |
|---|---|
| Per-user cloud machine | A dedicated, persistent cloud desktop per user, with a browser, cached credentials, and state that survives between messages |
| Reach | Vision-language models driving ordinary software: clicking, filling forms, working through anti-bot challenges as a human would |
| Continuity | One ongoing conversation; multi-day tasks and follow-ups continue without the user re-explaining |
| Memory | Analysts close to the founder describe it as acting from **consolidated state rather than raw scrollback**, so it degrades gracefully as history grows, and handles reversals ("actually, I'm vegetarian now") because provenance makes pruning real |
| Posture | "Where most assistants stop to confirm, Instinct continues." It reportedly reset a password on its own to finish a purchase when a site blocked it |

### What it does for people

Booked medical appointments and restaurants. Negotiated bills. Monitored ticket
availability. Organised family schedules. Coordinated vendors on WhatsApp. Triaged
inboxes. Sent email. Followed up on dropped threads and texted first.

### What went wrong, in the first weeks

This list matters more to us than the feature list, because each item is a design
constraint with a name attached.

| Incident | What it says |
|---|---|
| Gmail data retained, and still summarised hours after the user disconnected Google; indexed copies held in plain text with no delete path until patched | Revocation that does not delete is not revocation |
| An email sent on a user's behalf without approval | "Continues rather than confirms" has no boundary unless the boundary is drawn somewhere other than the model's judgement |
| A successful email-borne prompt-injection test by a security-minded founder | An agent that reads your mail and holds your credentials is an injection target from day one |
| A user reported losing $300 to a failed booking; cancellation fees and missing confirmations | Actuation without verified postconditions is worse than no actuation |
| Repeated crashes and lost queue positions during a high-demand ticket sale while a human completed the purchase | Long tasks need durable state, not a live session that can die |
| One thread felt limiting with several tasks in flight | One conversation is right; one *task* is not |
| Terms granting a perpetual, sub-licensable licence over user materials including for training | The data posture is the product for this category |
| Argued disadvantage of a cloud agent versus one on the user's own computer, which already has the cookies and a residential IP | Being on the device is a moat, not a constraint |

### What buddy should take, and what it should not

**Take:** the continuous agent that owns a job across days; no new interface; memory as
compiled state with provenance; the willingness to keep going rather than ask after every
step; proactive contact as a first-class behaviour.

**Do not take:** credentials escrowed in someone's cloud; revocation that leaves copies;
a training licence over a person's life; "continue" as a global posture rather than a
scoped one; a single live session as the unit of durability.

buddy already holds the opposite end of most of those trades. The device *is* the
persistent machine; the cookies and the residential IP and the logged-in apps are already
there; the ledger is already the compiled state; the policy engine is already the boundary
the model cannot argue with. What is missing is that nothing in buddy **owns a job over
time**.

## 2. The gap, stated plainly

This section describes the code as it was when the design was written. Steps 1 to 5 and
7 of the build order are now built; section 11 says what runs today and what does not.

Before this doc, the agent side of buddy was four unrelated calls (doc 01, "Cognition"):

| Loop | Trigger | State carried between runs |
|---|---|---|
| Triage | every event | none |
| Act | 07:30 and 19:30 | none |
| Brief | 07:30 and 19:30 | none |
| Memory | idle | notes, but nothing reads them back |

Consequences, each of which was visible in the code:

1. **`ACT_NOW` does not act now.** A time-sensitive question waits for the next cycle,
   up to twelve hours. The class exists; the scheduler ignores it.
2. **No job survives a turn.** A proposal is made, gated, executed or escalated, and
   forgotten. "Book a table for Thursday" cannot exist, because it takes four exchanges
   over two days and there is nothing to hold it.
3. **The agent cannot ask for context.** `SliceBuilder` spoon-feeds one slice; the model
   has no `recall`. The FTS5 index is built on every insert and never queried.
4. **Memory is written and never read.** `MemoryConsolidator` writes `MEMORY_NOTE`
   events; no slice includes them; no note can contradict an older one.
5. **Nothing ever follows up.** `EntityStore.awaitingReply` computes exactly the signal a
   follow-up needs and only one section of one prompt consumes it.
6. **Reach stops at the connector.** `VisionFallback` returns a single move and is wired
   to nothing. There is no voice reach at all.

So the design below is not a rewrite. It is five additions that turn what exists into one
agent: **Task**, **Mandate**, **Wake**, **Working set**, and **Reach**.

## 3. Task: the unit that survives

A Task is a job the agent owns from statement to completion, across wakes, restarts, and
days. It is the missing noun. Doc 02 lists it in the entity graph; nothing implements it.

```kotlin
data class Task(
    val id: String,
    /** The goal in the user's words, or buddy's sentence if buddy opened it. */
    val goal: String,
    val state: TaskState,
    val opener: Opener,              // USER, COMMITMENT, TRIAGE, FOLLOW_UP, BUDDY
    val mandate: Mandate,            // section 4
    /** Events that caused this task, and everything it has touched. */
    val sourceEventIds: List<String>,
    /** The compiled state the next wake resumes from. Rewritten by the agent, never appended to. */
    val workingSet: String,
    /** Ordered, immutable record of what happened. Replayable. */
    val journal: List<JournalEntry>,
    val dueTs: Long?,                // when the world needs this done
    val nextWakeTs: Long?,           // when buddy next wants to look at it
    val waitingOn: String?,          // "reply from sam", "delivery window to open", "user decision"
)

enum class TaskState { PROPOSED, ACTIVE, WAITING, BLOCKED, DONE, ABANDONED }
```

State machine, deliberately small:

```
PROPOSED ──accept──> ACTIVE ──wait for world──> WAITING ──wake──> ACTIVE
    │                  │                                            │
    └──decline──┐      ├──needs a human──> BLOCKED ──answer─────────┘
                ▼      └──finish──> DONE
           ABANDONED <──give up, with a reason──┘
```

Rules that make it durable rather than decorative:

- **A task is ledger events.** `TASK` events with `supersedes` pointing at the previous
  state of the same task, exactly like a correction. No new store, no second source of
  truth, and the whole history of a job replays from the ledger. The current task list is
  a fold over those events.
- **The journal is append-only and machine-readable**: `{ts, kind, actor, detail, eventId?, proposalId?}`.
  It is what the timeline renders, what the eval replays, and what the agent reads when
  it resumes.
- **The working set is rewritten, not grown.** At the end of every turn on a task the
  agent calls `note_state` with the *complete* compiled state: what it knows, what it has
  tried, what is left, what it is waiting for. The next wake reads that, not the journal.
  This is the Instinct memory thesis applied at task scope: resume from consolidated
  state, never from scrollback. Cap it (2,000 characters) so compression is forced.
- **BLOCKED is the only way to reach the user.** A task that needs a decision moves to
  BLOCKED with the question, the options, and the recommendation — which is exactly the
  `Decision` shape the brief already renders. The escalation queue becomes "the blocked
  tasks", and stops being a separate concept.
- **Every task has a deadline or it is not a task.** `dueTs` null means the agent must
  set one when it accepts. Jobs without a clock are how agents accumulate silent debt.

Tasks run in parallel; the conversation stays single. That is the fix for the "one thread
felt limiting" complaint without inventing a second interface: one thread, many jobs, each
addressable by name in that thread ("the table thing — make it Friday instead").

## 4. Mandate: scoped authority, so "continue" is safe

Instinct's defining behaviour and its defining failures are the same property: it keeps
going. The answer is not to make buddy ask after every step — that is the product buddy
exists to replace. The answer is to make the *boundary* explicit and set it before the
work starts, not per action.

A **Mandate** is the envelope of authority a task carries. Inside it the agent proceeds
without asking. At its edge it blocks, every time.

```kotlin
data class Mandate(
    val domains: Set<Domain>,            // what this task may touch at all
    val maxLevel: Level,                 // ceiling for this task, never above the profile's
    val spendCap: Double?, val currency: String?,
    val allowedTargets: Set<String>,     // empty = "anyone the task's own events introduced"
    val deadlineTs: Long,
    val maxCloudTokens: Long,
    val maxActions: Int,
    /** Actions that always block within this task, whatever the level says. */
    val neverWithoutAsking: Set<String>,
)
```

- The mandate is proposed by the agent when it opens a task and **granted by the user in
  one turn**, in their words: "yes, up to sixty quid, any of those three places, by
  Thursday." The agent restates it; the restatement is what is stored.
- The policy engine gains one rule, evaluated before everything else it already does:
  **a proposal outside its task's mandate escalates, regardless of autonomy level.**
  The existing hard limits still apply underneath; a mandate can only narrow.
- A standing mandate is a profile entry ("deliveries: reschedule freely, never pay"), so
  recurring work does not re-ask weekly. Standing mandates are what the trust ladder
  promotes, instead of promoting a whole domain at once.
- Budgets are part of the mandate, so a task that thrashes runs out of tokens and blocks
  with what it has, instead of quietly costing money. This is where doc 03's per-day token
  budget finally lives in code.

The mandate is also the honest answer to the $300 booking and the unrequested email: both
were actions a reasonable mandate would not have contained, taken by a system that had no
concept of one.

## 5. Wake: event-driven, with the reason in the operator channel

Two alarms become one scheduler over a queue of wakes.

| Wake | Fired by | Latency target |
|---|---|---|
| `USER_TURN` | The person says something | immediate |
| `WORK` | Triage returns `ACT_NOW` | under a minute |
| `TASK_DUE` | A `WAITING` task's `nextWakeTs` | at the minute |
| `SIGNAL` | An event that a `WAITING` task declared it was waiting for (a reply on thread X, a delivery update, a price change) | under a minute |
| `HOLD` | A held action's window expired | existing, 5-minute tick |
| `CYCLE` | Morning and evening | existing |
| `IDLE` | Charging and on wifi | existing |

One agent invocation per wake. The wake reason, the current situation, and any per-turn
restrictions go in the **operator channel** — the mid-conversation system message the
cognition layer already implements — so they carry operator authority and do not disturb
the cached system prefix. That is the existing mechanism finally being used for what it
was built for: `"Wake: SIGNAL. Sam replied on thread sms:447… . The user is asleep;
nothing outward until 08:00."`

`SIGNAL` is the interesting one. A `WAITING` task registers what would change its mind —
a thread id, an entity, a structured field crossing a threshold — and the perception path
matches new events against those registrations before triage. It costs one index lookup
per event and it is what turns "check tomorrow" into "wake when it happens".

## 6. Working set and memory: compiled state, with provenance

Three tiers, each with a different lifetime, all of them events in the ledger.

| Tier | Scope | Written by | Read when |
|---|---|---|---|
| Working set | One task | The agent, every turn (`note_state`) | That task's next wake |
| Memory notes | The person's life | Consolidation, idle cycles | Retrieved per task by `recall` |
| Profile | Standing truth | The user, and promoted notes | Cached system prefix, every call |

The changes memory needs to be worth reading:

1. **Provenance.** A note carries `derived_from` (the event ids that justify it) and
   `observed_between` (a time range). Without it, a note cannot be checked, aged, or
   pruned, and the notes rot into folklore.
2. **Reversal.** "I'm vegetarian now" must retire "orders the steak". A new note with
   `supersedes` pointing at the old one — the append-only mechanism that already exists
   for corrections — and the retired note stops being retrievable while staying auditable.
   Provenance is what makes this a real deletion rather than two contradictory notes both
   getting retrieved.
3. **Promotion.** A note that holds for a month and has been acted on without correction
   is proposed for the profile, where it joins the cached prefix. Promotion is proposed in
   the brief and confirmed by the user, like a ladder step.
4. **Retrieval as a tool.** `recall(query, limit)` over FTS5 today, `sqlite-vec` when
   embeddings land. The agent asks; it is not spoon-fed. The slice shrinks to "the trigger,
   the task's working set, the situation", and everything else is pulled on demand. This is
   the single biggest token saving available, and it makes long histories cheap instead of
   expensive.

## 7. The tool surface

The act loop's one tool becomes a small, stable set. Still strict schemas, still every
call through the harness, still the policy engine between the model and the world.

| Tool | Kind | Gated by |
|---|---|---|
| `recall(query, limit)` | read | slice budget only |
| `read_thread(thread_id, limit)` | read | slice budget only |
| `open_task(goal, mandate, due)` | state | user grants the mandate |
| `note_state(task_id, working_set)` | state | none; it is buddy's own memory |
| `wait_for(task_id, signal, until)` | state | none |
| `block(task_id, question, options, recommend)` | state | none; this is how it asks |
| `propose_action(...)` | act | mandate, then style, then second opinion, then policy |
| `drive_app(package, goal, budget)` | act | mandate + per-step policy, most conservative level |
| `speak_to(number, goal, script)` | act | mandate; always escalates on first use per callee |
| `finish(task_id, outcome)` | state | none |

Read tools are cheap and ungated; state tools cost nothing outside the ledger; only the
three act tools reach the world, and they keep the whole existing gauntlet. The split is
what lets the agent work hard while the blast radius stays exactly where it is today.

## 8. Reach: the device is the persistent machine

Instinct's cloud desktop is the part buddy should not copy, and the part buddy already
beats. The phone is logged in, has the cookies, has a residential IP, has the apps, and
has a SIM. Nothing needs escrowing.

What is missing is the loop above the pieces:

- **App driving.** `RecipeRunner` is fast and deterministic where a recipe exists;
  `VisionFallback` is the fallback and is wired to nothing. Design: `drive_app` runs
  recipe-first, falls back to vision step-by-step with a step budget, re-reads the screen
  through content capture before every step, aborts to BLOCKED on drift, and writes the
  whole trace to the task journal. Vision steps never enter credentials or payment
  details — the recipe path or the human does that.
- **Voice reach.** `speak_to` is buddy on the phone to a restaurant or a utility, using
  the dialer it already owns and on-device TTS. First call to any callee escalates for
  approval; the transcript lands in the task journal; buddy states it is an assistant when
  asked, per decision 12.
- **The handover.** `Outcome.needsUserIn` already exists for "buddy got to the bank's face
  check and cannot finish". Under tasks it stops being a dead end and becomes a BLOCKED
  state with one tap: buddy opens the app at the right screen, the person does the one
  thing only they can, and the task resumes on the next `SIGNAL`.

## 9. Answers to the failure list

| Instinct incident | buddy's answer | Exists? |
|---|---|---|
| Data retained after revoke | `forget(source)` **physically deletes** every event from that source and its derived notes, and writes one tombstone event recording what was purged and when. The one deliberate exception to append-only, and it needs to be in the schema from the start rather than retrofitted | New; see decision 14 |
| Email sent without approval | Hold window, veto from the brief, undo with `undoToken`, mandate ceiling per task | Exists, plus mandate |
| Prompt injection via email | Untrusted envelopes, tag neutralisation, second opinion, policy engine, injection corpus in CI | Exists |
| $300 lost to a failed booking | Postcondition verification before an action is recorded `done`; failures escalate, never retry blindly | Exists; `drive_app` must inherit it |
| Crash lost the queue position | The journal and working set are ledger events; a killed process resumes the task at the next wake | New |
| One thread, many jobs | One conversation, parallel tasks, addressable by name | New |
| Perpetual training licence | Nothing leaves the device but the slice; no training licence; provider retention is the only exposure and it is listed in doc 02 | Exists |
| Cloud desktop lacks the user's session | buddy *is* the session | Exists |

## 10. What this costs

- The slice gets smaller and the number of calls gets larger: a wake per signal instead of
  two cycles a day. Token spend moves from "two big calls" to "many small ones", which is
  what prompt caching is for, and what the mandate's `maxCloudTokens` exists to bound.
  Measure it before widening the wake set.
- `SIGNAL` matching runs on the perception path, so it must stay an index lookup. If it
  ever needs the model, it is the wrong design.
- Tasks make buddy's failures *durable* too: a confused task can sit in `WAITING` forever.
  Hence a mandatory deadline, and a weekly sweep that blocks or abandons anything stale.
- The mandate is a new thing to explain to a user. It is also the only part of the design
  the user actually has to understand, which is the right place to spend their attention.

## 11. Build order

Each step is shippable and testable on its own; each has an eval before it ships.
Steps 1 to 5 and 7 are built and tested on the JVM; step 6 is the one that needs the
phone.

| Step | What | State | Eval |
|---|---|---|---|
| 1 | `core/tasks`: Task, state machine, `TASK` events, fold, journal | **Done** | State-machine truth table; a task survives a new connection to the same database and resumes from its working set |
| 2 | Wake scheduler: `WORK`, `TASK_DUE`, `SIGNAL`, the stale sweep | **Done** | `Waker` fires due and signalled tasks; `Ingest` wakes the agent on `ACT_NOW` within the minute |
| 3 | Mandate in the policy engine, before the existing rules | **Done** | `eval/mandate`: ten jobs that exceed their authority, none of which may return `Run` at maximum trust, each caught by the mandate itself and each passing once the mandate is widened |
| 4 | Tool surface: `recall`, `read_thread`, `open_task`, `note_state`, `wait_for`, `block`, `finish` | **Done** | The wake slice carries the working set, not the transcript; `recall` never returns a retired note |
| 5 | Memory provenance, reversal, promotion | **Provenance and reversal done**; promotion to the profile is still by hand | A four-case reversal corpus: the retired note leaves retrieval, stays in the record, and is never shown to the next consolidation |
| 6 | `drive_app` loop, then `speak_to` | **Not started** | Recipe drift aborts to BLOCKED; no vision step ever enters a credential |
| 7 | `forget(source)` and the tombstone | **Done** | After forget, no event, note, or index row from that source is retrievable, and the ledger is append-only again |

### What the phone runs today

`Brain` builds the agent at first unlock; `Ingest` wakes it on act-now work and on any
event a parked task registered a signal for; the five-minute alarm that releases held
actions also fires due tasks and sweeps stale ones; the morning and evening cycles are
now a `CYCLE` wake before the brief is written. A blocked task renders in the surface
as the decision it is, and answering it resumes the job. An utterance the command
parser does not recognise is no longer "I didn't understand that": it goes to the
agent as a `USER_TURN` wake.

### What is not built

- **`drive_app` and `speak_to`.** `RecipeRunner` and `VisionFallback` both exist and
  neither is wired into the tool surface yet, so the agent's reach is still the
  connectors.
- **A note is promoted to the profile by hand.** The evidence is recorded; the
  promotion step in section 6 is not automated.
- **Nothing has run against the real API.** Every test drives a scripted agent. The
  first real wake on a phone is where the prompt earns or loses its keep.

## 12. Decisions this doc makes

Numbering continues doc 06.

| # | Decision | Choice | Why |
|---|---|---|---|
| 14 | Deletion | `forget(source)` physically deletes and writes a tombstone; the sole exception to the append-only rule | Revocation that leaves copies is the first thing Instinct got wrong, and it is unfixable later if the schema assumes nothing is ever removed |
| 15 | The unit of work | Task, persisted as ledger events, not a separate store | One source of truth is why replay, undo, and the timeline work at all |
| 16 | Authority | Mandate per task, granted in one conversational turn, narrowing the profile's levels | "Continue rather than confirm" is correct product behaviour and needs a boundary drawn somewhere other than the model's judgement |
| 17 | Context | The agent pulls with `recall`; the slice carries only the trigger, the working set, and the situation | Spoon-feeding does not survive a long history; consolidated state plus retrieval does |
| 18 | Reach | The phone is the persistent machine; no cloud desktop, no escrowed credentials | The session, the cookies, the IP, and the SIM are already here |
| 19 | Conversation | One thread, many tasks, addressable by name | One interface is right; one job at a time is not |
