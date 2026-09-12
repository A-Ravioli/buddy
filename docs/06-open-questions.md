# 06. Open questions and risks

## Decisions to make before building

1. **Base OS.** Now that the fork is day one and the patch set touches audio policy,
   content capture, window management, and SELinux, plain AOSP for Pixel is the
   recommendation: it is the base with the least friction for deep framework patches,
   and Google publishes the vendor binaries. GrapheneOS becomes a later port for its
   hardening. LineageOS is the fallback if we need non-Pixel hardware. Confirm.

2. **Which Pixel.** A recent Tensor device for the DSP hotword path, the NPU, and
   AICore. Pick one model and stay on it for the first year; every extra device is
   another build target and another battery profile.

3. **Google services.** No Play Services in the first build. Choose between microG,
   sandboxed Play Services (needs porting from GrapheneOS), or accepting that some apps
   do not work. Google Wallet tap-to-pay does not work on a build with our own
   verified-boot key regardless, so payments through Wallet are out of scope; decide
   whether card payments happen through bank apps instead.

4. **Secure-window override.** Content capture and screenshots normally skip windows an
   app flags as secure (banking, password managers, some messengers). A framework patch
   can make buddy see them anyway. Taking it means buddy reads bank balances and
   one-time codes from the screen, which the money and account playbooks need. Not
   taking it means those domains stay on notifications and APIs. Recommendation: take
   it, because the hard limits in doc 03 are enforced in code regardless of what buddy
   can see, and because a buddy that cannot see the bank cannot run the money domain.

5. **Playback-capture override.** Same shape: a patch lets buddy capture audio from apps
   that opt out (most VoIP apps). Needed for transcribing VoIP calls. Recommendation:
   take it, with the same consent rules as phone calls.

6. **Microphone indicator and bystanders.** Android shows an indicator when the mic is
   live. With continuous capture it would always be on, which is meaningless, and with
   the phone in a pocket nobody sees it anyway. Options: keep the indicator (honest,
   useless), remove it for buddy (invisible), or replace it with a physical or audible
   cue only when transcription (tier 3) is active. Recommendation: the third, plus the
   spoken stop phrase in doc 03. This is as much an ethical decision as a technical
   one; make it deliberately.

7. **Transcription scope and budget.** Default to transcribing conversations the user
   is part of, with a daily minutes budget, and nothing else. Decide the starting budget
   and whether meetings count against it.

8. **Cloud model provider lock-in.** The plan is built around the Claude API. The
   adapter layer keeps the cognition module provider-agnostic, but prompt caching,
   effort control, and mid-conversation system messages are provider-specific and the
   design leans on them. Accept the coupling or budget for an abstraction.

9. **Who is the first user.** The plan assumes the founder is the first and only user for
   months. Every metric, threshold, and autonomy default is tuned on one person's data
   before it generalises. Decide when a second user is added and what has to be true
   first.

10. **Voice surface.** Earbuds are now the intended primary command channel from early
    on, since the phone stays in the pocket. Decide which earbuds and whether the
    watch is in the first year.

## Known risks

| Risk | Severity | Mitigation |
|---|---|---|
| Prompt injection through inbound messages | Critical | Untrusted envelopes, operator channel, capability policy, no secret exfiltration paths. See doc 03. |
| Agent sends a message the user would not have sent | High | Style model from history, hold-and-review window, regret-rate gating of autonomy. |
| Accessibility-based automation breaks on app updates | High | Prefer APIs and intents; keep automation adapters small and tested; fall back to escalate rather than guess. |
| Battery and thermal cost of always-on audio | High | Tiered pipeline with DSP hotword, VAD gating, a daily transcription budget, and NPU inference; measured per build. |
| Bystander privacy and consent | High | Off-limits situations, user-is-a-participant default, spoken stop phrase, on-device only, no raw audio. Jurisdiction floor enforced. |
| Content capture coverage gaps (Compose, Flutter, WebView) | Medium | Keep Accessibility and vision fallbacks; measure coverage per target app in Phase 1. |
| Framework patch rebase cost | Medium | Small, well-isolated patches; monthly rebase as a scheduled task; port to GrapheneOS only once stable. |
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
