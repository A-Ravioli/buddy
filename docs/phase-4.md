# Phase 4 status

Phase 4 from the roadmap: hardening and the second-user build. The security review,
the viewer chrome removed, the watch surface, bystander controls finalised, and the
onboarding flow.

Built on the Phase 3 branch; separate PR.

## What exists

| Roadmap item | State | Where |
|---|---|---|
| Security review by someone who did not write the patches | **Checklist written**; the review itself needs a reviewer and a built tree | `docs/security-review.md` |
| Remove the launcher grid | Done in the build config: Buddy overrides the stock launcher | `Android.bp` |
| Remove SystemUI chrome that assumes a viewer | Patch specified (0013) | `platform/patches/README.md` |
| Pixel Watch 4 as the escalation surface with tap-to-resolve and the haptic cue | Watch app and phone bridge written; needs the Android SDK to build the watch app and the wearable library on the host | `wear/`, `core/android/.../WearBridge.kt` |
| Bystander controls finalised: stop phrase from any voice, earbud gesture, the cue on transcription start, off-limits from marked places, place categories, calendar keywords | **Done, tested** (the phone runtime supplies the utterances and the gesture) | `core/audio/Bystanders.kt` |
| Onboarding: profile bootstrap from history (relationships, quiet hours, style, autonomy defaults) | **Done, tested** | `core/profile` |
| First-week trust ladder: a slow, proposed-only schedule frozen by any critical regret | **Done, tested** | `core/policy/Onboarding.kt` |
| Onboarding screen writing the profile files | Written for the phone | `core/android/.../OnboardingActivity.kt` |
| Voice enrolment | Prompted by onboarding; the sample capture and the speaker model are the audio runtime's, build-host work | |

## Decisions made while building

- **The review is a checklist with pass conditions**, not prose. Every item names the
  file and what a pass looks like, so a reviewer who has never seen the code can work
  through it and the same list is re-run before the second user.
- **The launcher is removed with a Soong override, not a patch.** Buddy's app module
  declares that it replaces the stock launcher, so the build has one HOME candidate.
- **The watch bridge loads the wearable client reflectively.** The phone app builds and
  runs without the Play Services library; with it absent the bridge is a no-op and
  the phone screen and earbuds remain the surface. No hard dependency in the platform
  build.
- **Relationships are inferred from volume, reciprocity, and hour of day**, and every
  inference is written as a default the user can change by talking to buddy. The
  summary is shown on the onboarding screen in plain words.
- **The first-week ladder is proposed, never applied**, and it is slower than the
  evidence ladder. A new user has not yet seen what buddy does.

## What needs the build host

1. Everything from earlier phases.
2. A reviewer for `docs/security-review.md`, and the fixes before anything else ships.
3. The watch app built with Gradle and the Android SDK, sideloaded to the Pixel Watch 4;
   the wearable library copied into `platform/prebuilts/wear` for the phone bridge.
4. Patch 0013 for SystemUI.
5. The audio runtime's speaker enrolment path, driven from the onboarding prompt.

## What needs the phone

- A clean build from a fresh checkout, flashed, and the metrics unchanged for four
  weeks with the founder.
- The onboarding run on a second phone with real history.

## Exit criteria (from the roadmap)

- [ ] A clean build from a fresh checkout
- [ ] Security review closed
- [ ] Battery and safety metrics unchanged
