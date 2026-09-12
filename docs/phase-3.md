# Phase 3 status

Phase 3 from the roadmap: buddy reaches every app; the screen is optional. App
automation through recipes and input injection, the vision fallback, the money,
shopping, and travel playbooks, and voice commands.

Built on the Phase 2 branch; separate PR.

## What exists

| Roadmap item | State | Where |
|---|---|---|
| Recipe framework: matchers, steps with placeholders, an interpreter that verifies the screen before every action and aborts on drift, timeouts, no retries | **Done, tested against scripted screens** | `core/automation` |
| Two generic recipes (carrier reschedule, retailer return) and the registry | Done; the founder's apps get theirs written against captured screens | `core/automation/Recipes.kt` |
| Input injection through the framework and a driver over content capture | Written for the phone | `core/android/.../CaptureDriver.kt` |
| Recipes as actions: registry extension, a connector, policy classification (shopping, soft, external) | **Done, tested** via the registry | `core/actuation/RecipeConnector.kt`, `Actions.register` |
| Vision fallback: screenshot in, one move out, structured | Written against the SDK and compiled; the screenshot capture and the loop are build-host work | `core/cognition/VisionFallback.kt` |
| Money: categorisation by rules and overrides, anomaly flags, bill proposals from recurring charges that policy still gates | **Done, tested** | `core/money` |
| Shopping: delivery tracking by tracking number with forward-only status | **Done, tested** | `core/logistics/DeliveryTracker` |
| Travel: itineraries from confirmations (flights, trains, hotels) | **Done, tested** | `core/logistics/ItineraryBuilder` |
| Voice: the command grammar (tell, recall, cancel, reschedule, reply, brief, pause, resume, undo, standing instructions) | **Done, tested** | `core/voice` |
| Voice on the phone: assist gesture opens a one-shot on-device recognition session; commands act through the policy engine; spoken replies | Written for the phone | `core/android/.../VoiceServices.kt` |
| Content capture nodes carry screen bounds so the driver can tap them | Done | `core/perception/capture` |
| Hotword on the DSP | Needs an enrolled keyphrase on the device (build-host work) | |

## Decisions made while building

- **Recipes are data, and drift aborts.** A recipe that does not match the app as it is
  today escalates with its trace; nobody guesses at a changed screen. The founder
  fixes the recipe against the captured screen and the test suite runs it.
- **Domain actions register into the same registry.** Paying a bill or rescheduling a
  delivery is a `propose_action` like any other, with its own classification. The
  money ceiling and the external blast radius apply automatically.
- **Bill payment has no connector yet.** The proposal exists so the policy path is
  exercised end to end; without a payment connector it fails closed as `no_connector`.
  Wiring a bank app recipe is a per-app job.
- **Voice acts through policy.** "Tell Sam I'm late" becomes a send proposal that the
  engine decides on like any other, including quiet hours and first contact.
- **The vision fallback is one move at a time**, structured, with give-up preferred
  over guessing, and it never enters codes or payment details by instruction. The
  harness owns the loop and its step limit.

## What needs the build host

1. Everything from earlier phases.
2. Keyphrase enrolment for the hotword detector on the device, then the
   `AlwaysOnHotwordDetector` wiring in the voice interaction service.
3. Screenshot capture for the vision fallback (the platform-signed app can use the
   screenshot API without a projection prompt) and the step loop around it.
4. Recipes for the founder's top apps, written against snapshots exported from the
   phone.

## What needs the phone

- Input injection against real apps: which honour injected touches, and at what
  settle time content capture reflects the new screen.
- Content capture bounds accuracy per app (relative positions accumulate).
- Recogniser accuracy for the command grammar in real rooms.

## Exit criteria (from the roadmap)

- [ ] The founder has not opened a delivery, banking, or airline app in a month
- [ ] A full day without unlocking
