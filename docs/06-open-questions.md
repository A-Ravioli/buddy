# 06. Open questions and risks

## Decisions to make before building

1. **Base OS.** GrapheneOS is the best privacy and security base and supports Pixels
   well, but it is opinionated about privileged apps and its maintainers will not merge
   buddy upstream, so we carry patches. LineageOS supports more hardware and is easier to
   patch but weaker on security. Recommendation: GrapheneOS on a Pixel for the founder
   device; revisit when we need other hardware.

2. **Google services.** Many target apps (Gmail, Maps, Wallet) assume Play Services.
   Sandboxed Play Services on GrapheneOS work for most apps. Google Wallet tap-to-pay
   does not work on an unlocked bootloader, which means the agent cannot fully own
   payments on that base. Decide whether payments are in scope for stage one.

3. **Cloud model provider lock-in.** The plan is built around the Claude API. The
   adapter layer keeps the cognition module provider-agnostic, but prompt caching,
   effort control, and mid-conversation system messages are provider-specific and the
   design leans on them. Accept the coupling or budget for an abstraction.

4. **Who is the first user.** The plan assumes the founder is the first and only user for
   months. Every metric, threshold, and autonomy default is tuned on one person's data
   before it generalises. Decide when a second user is added and what has to be true
   first.

5. **Voice surface.** Earbuds and a watch are the intended primary surface, but neither
   is built in stage one. Decide whether stage one is "phone in pocket, brief by
   notification" or "earbuds from day one."

## Known risks

| Risk | Severity | Mitigation |
|---|---|---|
| Prompt injection through inbound messages | Critical | Untrusted envelopes, operator channel, capability policy, no secret exfiltration paths. See doc 03. |
| Agent sends a message the user would not have sent | High | Style model from history, hold-and-review window, regret-rate gating of autonomy. |
| Accessibility-based automation breaks on app updates | High | Prefer APIs and intents; keep automation adapters small and tested; fall back to escalate rather than guess. |
| Android background execution limits kill the agent | High | Device owner exemption in stage one; system service in stage two. |
| Battery and thermal cost of continuous perception | Medium | Event-driven, not polling. On-device triage is a small model. Cloud calls are batched. |
| Cloud reasoning cost | Medium | Triage on-device, cached stable prefix, effort tuned per task, cheaper worker model for bulk reads. Budget cap enforced. |
| Loss of the device exposes the ledger | High | Ledger encrypted with a key in the hardware keystore, bound to the lock credential. |
| Regulatory exposure (call recording, automated messaging) | Medium | Jurisdiction-aware defaults. Calls are transcribed on-device only with consent prompts where required. |
| The user stops trusting the brief and starts checking apps again | Product-critical | The brief must be complete, honest about what it did, and never bury an item the user cares about. Missed-critical is a zero-tolerance metric. |

## Things that would change the plan

- **A capable on-device model with tool use at phone scale.** If a 3 to 8 billion
  parameter model on the phone can reliably run the act loop for low-risk domains, the
  cloud call rate drops by an order of magnitude and offline mode becomes real.
- **Platform APIs for agent access.** If Android ships a first-class agent API for
  reading and acting across apps, most of the perception and actuation layers in doc 01
  collapse into it.
- **App-side agent endpoints.** If major services expose agent-facing interfaces, the
  automation adapters become thin API clients.
