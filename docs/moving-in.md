# Moving in

What happens when someone puts buddy on a phone, what he does himself, and the short list
of things that have to happen before the flash because no app on the device can do them.

## What buddy does himself

He is the setup wizard (patch 0014), so the walk-through is the setup:

| | |
|---|---|
| Getting online | `WifiJoiner` scans and joins in-flow, and skips itself if a SIM already reaches the internet |
| The lock | `LockCredential` sets the first PIN, which the ledger's storage key is bound to |
| Marking the phone set up | `Provisioning` writes `device_provisioned` and `user_setup_complete` at the end |
| Who matters | `ProfileBootstrap` reads the message history already on the phone. No list to fill in |
| Quiet hours | Inferred from when the phone goes quiet, shown on the step, changeable by asking |
| Style | Learned from what has been sent, per relationship |
| The lock screen and the chrome | Stripped back on the way out of the walk-through (patches 0013, 0015) |
| The wallpaper | Black, so nothing of the old phone shows at the edge of a transition |

## What the walk-through hands to the framework

Two things no app on the phone can do for anyone, so buddy opens the platform's own flow
and waits rather than pretending:

- **The number.** eSIM transfer is the carrier's flow. The "Let's bring your life over"
  step opens it.
- **Accounts.** buddy has no business handling a password. The same step, and the accounts
  step, open the framework's add-account screen; the list on the step is the real one, so
  coming back from it shows what landed.

## What to do before flashing

This build has no Google setup wizard, so there is **no restore step**: apps and their data
do not come across, and that is a decision rather than an omission. A phone that restores
the old phone's habits is the old phone.

1. **Note the eSIM.** Some carriers need the old device to start the transfer. Check the
   carrier's transfer flow works before wiping anything.
2. **Export contacts as a vCard** from the old phone and keep it somewhere reachable. buddy
   learns who matters from message history, not from the contact list, but names make his
   first week much better.
3. **Write down the apps that matter.** They reinstall from Play after the account is
   signed in. Most will not be opened again: buddy reaches them through recipes, and the
   ones he cannot reach he hands over (see `device/Apps.kt`).
4. **Keep the old phone for a week.** Two-factor prompts, an app whose login did not come
   across, a photo library that has not synced yet.
5. **Back up anything local**: photos not in a cloud, WhatsApp history, authenticator
   seeds. Authenticators are the one that catches people — move them first.

## After the first boot

- The walk-through runs once. `SurfacePrefs.onboarded` is in device-protected storage, so
  it survives the first unlock and a reflash over the same data.
- The ledger opens at the first unlock, and the first brief needs a day of traffic to be
  worth reading.
- Apps are reached by name: "open Monzo". There is no grid, on purpose.
