# 00. Vision

## The problem

A phone today is a hundred inboxes. Every app is a separate stream that demands a
glance, a swipe, a reply, a decision. The operating system's job has been to make
switching between those streams fast. It has never tried to make the streams go away.

The result is a device you check dozens of times a day, mostly to discover that nothing
needed you. The attention cost is enormous and the actual decisions that require a human
are rare.

## The goal

A phone whose default state is off, in your pocket, doing your admin.

- **Every inbound stream is read by the agent first.** Email, SMS, chat apps, push
  notifications, calendar invites, delivery updates, bank alerts, social mentions.
- **Everything routine is handled without you.** Replies that only need facts you already
  gave the agent. Scheduling. Confirmations. Unsubscribes. Receipts filed. Bills paid
  within limits. Deliveries rescheduled. Two-factor codes entered.
- **You hear from it only for real decisions**, batched into a brief you can consume by
  voice or a glance, at a time you chose. Genuine emergencies break through immediately.
- **It knows you** because it has the full picture across apps: who your people are, what
  you have committed to, where you are going, what you care about, how you talk.

## What "agent-first" means concretely

The interaction model inverts.

| Today | buddy |
|---|---|
| Apps are the UI; the user is the integrator | The agent is the UI; apps are backends |
| Notifications interrupt the user | Notifications feed the agent; the user gets a brief |
| The user opens an app to do a task | The user states an outcome; the agent picks the app |
| Context lives in each app's silo | Context lives in one on-device ledger the agent owns |
| Automation is per-app and rule-based | Automation is cross-app and judgement-based |
| Screen is the primary surface | Voice, earbud, watch, and a single escalation screen |

Apps do not go away. They are still the things that talk to the outside world, and they
still exist for the moments you want to look at something. But they are demoted from
front-and-centre to plumbing.

## Principles

1. **Passive by default.** The agent works from the streams it observes. It does not ask
   you to set up rules. It learns the rules from your history and asks only when it is
   unsure.
2. **Bounded autonomy.** Every action has a reversibility class and a domain. The agent
   acts freely where actions are cheap to undo and asks where they are not. The bounds
   widen as trust is earned.
3. **Context stays home.** The unified record of your life lives on the device,
   encrypted. Cloud reasoning sees only the slice a task needs, and nothing is retained
   there beyond the request.
4. **Explainable and undoable.** Every action the agent takes is logged with its reason,
   is visible in a timeline, and can be undone or corrected. Corrections are learning
   signal.
5. **Adversarial inputs are the norm.** Every message the agent reads was written by
   someone else, possibly to manipulate the agent. The design assumes this from day one.
6. **Fail toward silence, not toward action.** If the agent is unsure, it does nothing
   irreversible and queues the item. Missing an unimportant thing is a far cheaper error
   than sending an embarrassing message.

## What success looks like

Measured on a daily basis, for the person using the phone:

| Metric | Target after 6 months of daily use |
|---|---|
| Screen unlocks per day | Under 10 (typical phone today: 50 to 150) |
| Screen time per day | Under 20 minutes |
| Items handled autonomously per day | Most inbound items, with no human touch |
| Escalations to the user per day | Under 10, delivered as one or two batched briefs |
| Regret rate (actions the user reverses or corrects) | Under 1 percent of autonomous actions |
| Missed critical items | Zero (a critical item is one the user says should have interrupted them) |

The regret rate and missed-critical count are the safety metrics. They gate how much
autonomy the system is allowed to take on.

## Non-goals for the first version

- Not a general-purpose voice assistant that answers trivia. It manages your life, not
  the internet.
- Not multi-user or family accounts. One phone, one person.
- Not a new app ecosystem. It drives existing Android apps.
- Not fully offline. The frontier reasoning runs in the cloud. Offline mode degrades to
  triage and drafts.
