# 02. Context and memory

The whole premise is that the agent understands you because it sees everything. This
doc is how "everything" is stored, structured, retrieved, and kept private.

## The life ledger

An append-only, encrypted, local store of every Event the perception layer produces
(schema in doc 01). Nothing is deleted; corrections are new events that supersede old
ones. It is the source of truth for everything the agent believes, and it is what makes
the agent's behaviour replayable and testable.

Sizing: a heavy user generates a few hundred events a day. With text bodies and
structured fields, that is tens of megabytes a year. Attachments are stored by reference
to the owning app and pulled on demand.

## Derived layers

The ledger is raw. Four derived layers make it useful, all rebuilt from the ledger on
demand and updated incrementally during idle cycles.

### Entity graph

| Entity | Sources | Key attributes |
|---|---|---|
| Person | Contacts, senders, mentions, call log | Identities across apps (same person on email, WhatsApp, SMS), relationship (family, close friend, colleague, service), reply expectations, tone the user uses with them |
| Organisation | Senders, transactions, apps | Bank, landlord, employer, gym, airline; account references |
| Thread | Conversations across sources | Open or closed, who owes whom a reply, topic |
| Commitment | Extracted from messages, calendar, the user's own words | "I'll send it Friday", "dinner at 8", "pay by the 15th"; owner, due, status |
| Place | Location history, addresses in messages | Home, work, frequent, with typical hours |
| Recurring | Bills, subscriptions, deliveries, meetings | Cadence, amount, next expected, tolerance |
| Task | Anything the agent or user has decided to do | State machine: proposed → approved → in progress → done or abandoned |

Identity resolution across apps (the same "Sam" in three apps) is the hard part. Start
with exact matches on phone and email, then let the cloud model resolve ambiguous cases
during idle cycles, writing its decisions back as events the user can correct.

### Profile

A structured document of what the agent knows about the user, maintained by the agent
and visible to the user in plain language:

- **Standing instructions.** "Never reply to my sister on my behalf." "Always accept
  meeting invites from my manager." "Reschedule deliveries to after 6pm."
- **Preferences.** Aisle seat. No calls before 9. Prefers texting to calling. Vegetarian.
- **Style.** How the user writes to each relationship class: length, sign-off,
  punctuation, emoji, formality. Learned from sent history; the agent's drafts are
  scored against it.
- **Situation defaults.** Typical sleep window, work hours, commute, quiet hours.
- **Autonomy levels per domain.** See doc 03. Stored here, edited by conversation.

The profile is the most sensitive document on the device and is part of the cached
system prefix on every cloud call, so it is kept compact (a few thousand tokens) and
stable. Volatile detail lives in the entity graph and is retrieved per task.

### Retrieval index

Full-text (SQLite FTS5) over event content, plus embeddings (on-device model, stored
with `sqlite-vec`) over events, threads, and commitments. Cognition never gets the whole
ledger; it gets a **context slice** built by:

1. The events that triggered this task.
2. The full thread each belongs to (bounded, most recent N).
3. Entities involved and their attributes.
4. Open commitments and tasks involving those entities.
5. Semantic neighbours: past events similar to the trigger (how did the user handle
   this kind of thing before).
6. The current situation (time, location, calendar state, device state).

Each slice has a token budget per task class and is assembled deterministically so the
same trigger produces the same slice, which keeps evaluation meaningful.

### Memory consolidation

During idle cycles the cloud model reviews recent ledger activity and writes
**memory notes**: durable observations that are not a single event. "User has started
running on Tuesday and Thursday mornings." "The landlord is slow to reply; nudging after
three days has worked." "User did not like that buddy accepted the 7am meeting." These
are events too, typed `memory_note`, ranked by confidence, and surfaced in the profile
when they become standing patterns.

Corrections from the user (undo, edits to a draft, "don't do that") are the highest
weight signal and are always consolidated into a note.

## Privacy model

| Data | Where it lives | Who sees it |
|---|---|---|
| Ledger, entity graph, index | Device only, SQLCipher, key in hardware keystore bound to the lock credential | Nobody but the device |
| Profile | Device; sent as cached system prefix on cloud calls | Model provider, transiently, under their retention terms |
| Context slice | Device; sent per task | Model provider, transiently |
| Model outputs | Written back to the ledger | Device |
| Backups | Encrypted export the user controls; never a provider cloud | User |

Rules:

- **Minimise the slice.** Send only what the task needs. Redact obviously irrelevant
  secrets (card numbers, one-time codes) before the slice leaves the device, unless the
  task is specifically about them.
- **Never send the whole ledger.** There is no "upload everything" path, by design.
- **On-device wherever a small model is good enough.** Triage, extraction, embeddings,
  speech.
- **Opt-in trace export** for improving the system, anonymised and reviewed by the user
  before it leaves.

## What "truly understanding you" requires

Context breadth is necessary but not sufficient. The parts that make the agent feel like
it knows you:

- **Relationship-aware tone.** Drafts to your partner sound nothing like drafts to your
  bank. The style model per relationship class handles this.
- **Commitment tracking.** It remembers what you said you would do and what others said
  they would do, and it follows up on both.
- **Situational sense.** It does not read out a brief while you are in a meeting. It
  knows you are abroad and that a bank alert about a foreign transaction is expected.
- **Pattern recall.** "Last time this happened you did X" is the single most useful
  thing the semantic index provides.
- **Honesty about uncertainty.** When it does not know, it says so in the escalation
  rather than guessing. Guessing is how trust is lost.
