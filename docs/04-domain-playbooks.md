# 04. Domain playbooks

Each domain is a connector set, a slice of the profile, a default autonomy level, and a
playbook the cloud model loads with the task. This is the behaviour spec for each.

## Email

**Reach:** Gmail API (primary), IMAP for others. Full read, send, label, archive.

**Passive handling:**
- Drop marketing and notifications-of-notifications after filing any structured data
  (receipts, tracking numbers, booking references) into the ledger.
- Unsubscribe from senders the user never opens, after a hold.
- File receipts, statements, and confirmations. Extract amounts and dates into
  Recurring and Commitment entities.
- Reply to anything answerable from context: scheduling, confirmations, "did you get
  this", "what's your address", "can you send the document" when the document is in
  the ledger.
- Chase: when someone owes the user a reply past the thread's typical latency, draft a
  nudge.
- Escalate: anything with emotional weight, a decision with cost, a first contact from
  an unknown person that looks legitimate, legal or medical content.

**Level defaults:** archive and file at level 2 from day one; replies at level 1
climbing to 2 by relationship class.

## Messaging (SMS, RCS, WhatsApp, Signal, Telegram, Slack, Discord, iMessage-via-bridge)

**Reach:** Default SMS role for SMS and RCS. Notification listener plus inline reply for
the rest. Accessibility adapter for reading history where notifications are truncated.

**Passive handling:**
- Two-factor codes: enter them in the requesting app when a flow is in progress that
  buddy started; otherwise file and never forward.
- Logistics replies: "on my way", "running 10 late" (using live location and calendar),
  "yes 8 works", address, confirmation of plans already in the calendar.
- Group chats: summarise, extract any plan that involves the user into a Commitment, and
  reply only when addressed directly and the answer is factual.
- Close relationships: level 1 by default, forever, unless the user changes it. The
  brief shows the message and a suggested reply; the user taps to send or speaks a
  different one.
- Escalate immediately: anything from the emergency list, anything with distress
  signals, anything from a close contact that is not pure logistics.

**Style:** the per-relationship style model is mandatory here. A reply that sounds
wrong is worse than no reply.

## Calendar and scheduling

**Reach:** Calendar provider and Google Calendar API. Read and write.

**Passive handling:**
- Accept, decline, or propose alternatives for invites according to profile rules and
  existing commitments. Level 2 for work invites from known senders.
- Negotiate times over email or chat: hold the slot, propose, confirm, write the event.
- Add travel time, buffers, and preparation blocks.
- Turn commitments in messages ("dinner Thursday?") into tentative events and confirm
  with the counterparty.
- Detect conflicts and resolve them by the profile's priority rules, escalating ties.

## Money

**Reach:** Bank and card notifications (universal), open banking APIs where the user's
bank supports them, email statements. Payment execution only through apps with an
adapter and only within caps.

**Passive handling:**
- Categorise every transaction into the ledger. Match to receipts and subscriptions.
- Flag anomalies (unknown merchant, duplicate charge, amount out of pattern, foreign
  charge when the user is home) and escalate.
- Pay recurring bills within caps when they arrive, if the profile allows. Level 3
  ceiling, and only after the recurring pattern has been observed several times.
- Track refunds owed and chase them.
- Weekly brief line: spend by category against the user's usual.

**Hard limit:** never initiates a transfer to a new payee. New payees are always a
human decision.

## Shopping, deliveries, and services

**Reach:** Order confirmation emails, carrier notifications, retailer apps via adapters.

**Passive handling:**
- Track every order to delivery. Reschedule deliveries to the profile's preferred window.
- Handle "we missed you" flows: rebook, redirect to a pickup point.
- Returns and refunds: initiate return flows in the retailer app for items the user said
  to return; chase refunds.
- Reorder consumables on the observed cadence at level 2 with a hold, within a cap.
- Price-drop and warranty claims where the retailer offers them.

## Travel

**Reach:** Booking emails, airline and rail apps via adapters, maps.

**Passive handling:**
- Build the itinerary from confirmations. Check in at the earliest moment. Pull boarding
  passes to the watch or wallet.
- Monitor delays and gate changes; rebook within the profile's rules (same day, same
  class, no extra cost) and escalate anything else.
- Ground transport: book the ride to the airport based on the itinerary and live
  traffic, at level 2 with a hold.
- Abroad: adjust the situation model (time zone, roaming, expected foreign
  transactions, quiet hours).

## Calls

**Reach:** Default dialer with voice-call capture for both sides, call screening
service, on-device transcription, TTS into the uplink. VoIP calls through playback
capture plus the mic.

**Passive handling:**
- Screen unknown callers: answer, ask purpose, transcribe, decide. Spam is dropped.
  Deliveries, appointments, and known organisations get handled by voice where the
  question is answerable from context (confirm an appointment, give a delivery
  instruction). Anything else takes a message and escalates.
- Known callers ring through according to the situation (in a meeting: decline with a
  text and offer a callback slot; asleep: only the emergency list rings).
- Every call the user takes is transcribed on-device (subject to the consent rules in
  doc 03), and commitments made on the call become Commitment entities: "I'll email
  you the form" creates a task; "they said the part arrives Tuesday" creates an
  expectation to track.
- Outbound: place calls the user asked for and stay on the line for hold queues,
  handing off when a human answers, with a whispered summary in the earbud of what the
  call is about.

## Conversations and ambient context

**Reach:** The audio pipeline (doc 01), gated by situation and by the consent rules in
doc 03.

**Passive handling:**
- Meetings: transcribe, extract decisions, actions, and who owes what. Actions assigned
  to the user become Tasks; things others promised become expectations to chase. A
  meeting summary is written to the ledger and offered in the evening brief.
- In-person plans: "dinner Thursday at ours" over coffee becomes a tentative calendar
  entry and a Commitment, confirmed in the brief if the other party is not in a
  messaging thread already.
- Requests in passing: "can you send me that photo" from a friend, if the speaker is
  enrolled, drafts the message at the messaging domain's autonomy level.
- Situation: a two-way conversation in progress suppresses the brief and non-emergency
  escalations; a car scene switches to voice-only; silence after the usual bedtime
  starts quiet hours early.
- Recall on demand: "what did the plumber say about the boiler" answered from the
  transcript index, on the device, without a cloud call where the on-device model can
  handle it.

**Never:**
- Transcribe a situation on the off-limits list.
- Treat any voice but the user's as a command.
- Send third-party speech to the cloud unless a task requires it and the timeline
  records it.

**Budget:** three hours of transcription a day. Calls and calendar meetings take
priority over ambient conversation when the budget runs low; the brief reports usage.

**Level defaults:** listening and extracting is on; anything that sends or books stays
at the owning domain's level.

## Accounts and admin

**Reach:** Email, app adapters, password manager integration.

**Passive handling:**
- Renewals, expirations (passport, licence, insurance, domain names) tracked as
  Recurring with escalation well in advance.
- Password resets and security alerts: escalate always; buddy never changes account
  credentials.
- Forms and applications: pre-fill from the profile, escalate for review before submit.

## Social and relationships

**Reach:** Messaging history, calendar, contacts.

**Passive handling:**
- Birthdays, anniversaries, and life events extracted from messages; draft a message
  at level 1 for the user to approve.
- Relationship upkeep: in the weekly brief, surface close contacts the user has not
  spoken to in an unusual while, with a suggested nudge.
- Social media notifications: filed, summarised weekly, never escalated unless a
  direct message from a known person.

## Device

**Reach:** System settings provider and device policy from inside the build.

**Passive handling:**
- Do-not-disturb, ringer, brightness, and battery saver driven by situation, not by
  schedule. Updates installed during idle cycles.
- Storage and app hygiene: uninstall apps unused for months (level 2, hold), clear
  caches, manage photos backup.
- Buddy's own health: budget, connector failures, and demotions reported in the brief.

## The brief (cross-domain)

Format, spoken or read, in under a minute:

1. Anything urgent, first.
2. Decisions waiting for you, each with buddy's recommendation.
3. What buddy did since the last brief, grouped by domain, counts not lists, with
   anything unusual called out.
4. What is coming up: next commitments, deliveries, travel.
5. One line on system health if anything changed, including minutes of audio
   transcribed and battery cost.

If nothing needs the user, the brief is one sentence and it says so.
