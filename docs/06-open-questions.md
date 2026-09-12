# 06. Open questions and risks

## Decisions made

These were open; they are now decided. Each can be reopened by editing this file, but
the plan elsewhere assumes them.

| # | Decision | Choice | Why |
|---|---|---|---|
| 1 | Base OS | Fork the GrapheneOS source tree | AOSP dropped Pixel device trees with Android 16; GrapheneOS carries Pixel 10 support, verified boot with own keys, an OTA flow, sandboxed Play Services, and the hardening a component this privileged should sit on. Monthly rebase on the security release only. |
| 2 | Device | Pixel 10 Pro XL, one device for the first year | Largest battery in the line, which is the binding constraint for always-on audio. Tensor G5 for DSP hotword, TPU, AICore. |
| 3 | Google services | Sandboxed Play Services from the base | Covers most Google-dependent apps without privileged Google code. Wallet tap-to-pay is out; payments go through bank apps and bill-pay. |
| 4 | Secure-window override | Yes | Money and accounts need buddy to read the bank and the codes. The hard limits in doc 03 are enforced in code whatever buddy can see. Patch scoped to buddy's capture domain only. |
| 5 | Playback-capture override | Yes | Needed for VoIP call transcription. Same consent rules as phone calls. Scoped to buddy's audio domain. |
| 6 | Microphone indicator | No standing indicator; a cue only when transcription (tier 3) starts | The phone is in a pocket, so a screen indicator informs nobody. Cue is a short haptic on the phone and watch plus a soft tone in the earbud, so the user always knows when buddy is transcribing. Bystanders are covered by the stop phrase, the participant-only default, and the user's own disclosure to close contacts. |
| 7 | Transcription scope and budget | Conversations the user is part of, calendar meetings, and calls. Three hours a day. | Three hours at around a watt is roughly a sixth of the battery, which leaves a full day. Meetings and calls count against the budget but take priority over ambient conversation when it runs low. Overheard conversations are off. |
| 8 | Cloud provider coupling | Accept it | Prompt caching, effort control, mid-conversation operator messages, and structured outputs are load-bearing. The cognition module keeps a thin adapter boundary so a swap is a rewrite of one module, not the system, and that is enough. |
| 9 | First and second user | Founder only until Phase 4 exits | The second user joins after a security review by someone else, a clean build from a fresh checkout, and four consecutive weeks of every gate green on the founder's phone. |
| 10 | Voice surface | Pixel Buds Pro 2 from Phase 1; Pixel Watch in Phase 4 | Earbuds are the user's own mic for commands and dictation and the channel for the brief, and they are needed as soon as tier-3 audio lands. The watch is the tap-to-resolve surface and the haptic cue; it waits until the escalation queue is stable. |

## Still open

Nothing blocks Phase 0. Items that will need a decision later, with the phase they
block:

- **Off-limits situation detection** beyond calendar and location (Phase 1): whether to
  train a scene classifier for medical and intimate settings or rely on user-marked
  places and a manual pause.
- **Disclosure defaults per relationship class** for agent-sent messages (Phase 2): the
  plan says no disclosure to close contacts and disclosure to organisations; confirm
  once the style model is real.
- **Watch model** (Phase 4): whichever Pixel Watch is current when Phase 4 starts.

## Known risks

| Risk | Severity | Mitigation |
|---|---|---|
| Prompt injection through inbound messages | Critical | Untrusted envelopes, operator channel, capability policy, no secret exfiltration paths. See doc 03. |
| Agent sends a message the user would not have sent | High | Style model from history, hold-and-review window, regret-rate gating of autonomy. |
| Accessibility-based automation breaks on app updates | High | Prefer APIs and intents; keep automation adapters small and tested; fall back to escalate rather than guess. |
| Battery and thermal cost of always-on audio | High | Tiered pipeline with DSP hotword, VAD gating, a daily transcription budget, and NPU inference; measured per build. |
| Bystander privacy and consent | High | Off-limits situations, user-is-a-participant default, spoken stop phrase, on-device only, no raw audio. Jurisdiction floor enforced. |
| Content capture coverage gaps (Compose, Flutter, WebView) | Medium | Keep Accessibility and vision fallbacks; measure coverage per target app in Phase 1. |
| Framework patch rebase cost against GrapheneOS's cadence | Medium | Small, well-isolated patches; rebase only on the monthly security release; exempt buddy's domains from their restrictions rather than removing the restrictions. |
| buddy's own privilege becomes the attack surface | Critical | Per-subsystem SELinux domains; only cognition has network, only actuation can act, neither can do the other's job. See doc 03. |
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
