# The boot animation

The first thing the phone shows is buddy opening his eyes.

    ./gradlew :platform:bootanimation:bootAnimation     # writes build/bootanimation.zip

It is drawn, not designed: every number comes from `FaceGeometry`, the same definition the
app's creature and the lock screen's `BuddyFaceView` read, so the face on the boot screen
cannot drift from the face that follows it. Only `FaceGeometry.kt` and `CreatureState.kt`
are compiled in here; everything else under `core/android` needs Compose or the framework,
neither of which exists at this point in a boot.

Two parts, which is the format surfaceflinger reads from `desc.txt`:

| Part | Plays | What |
|---|---|---|
| `part0` | once | Black. The strokes fade up shut, the body grows out behind them, the eyes open. |
| `part1` | loops | The blob breathing, with one blink, until the system is up. |

It ends on the same frame the lock screen begins with — a blob on black, resting — so a
cold boot is one movement rather than an animation that stops and a screen that starts.

`scripts/build.sh` runs the task and copies the zip into `vendor/buddy` in the tree;
`platform/product/buddy_mustang.mk` copies it from there into `/product/media`. A tree
without it builds anyway and warns, falling back to the base animation.

The zip is not committed. It is a render of the geometry, and the geometry is the source.
