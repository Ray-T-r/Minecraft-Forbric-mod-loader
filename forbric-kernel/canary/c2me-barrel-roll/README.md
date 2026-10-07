# Do a Barrel Roll + C2ME canary

A read-only NeoForge probe (`c2merollprobe`) for two repairs, run on a client next to Do a Barrel Roll 3.8.4
(+ cicada-lib, YACL, fabric-api) and C2ME 0.4.x:

- **Camera roll** (`MixinCameraRollAdapter`). While the kernel's elytra smoke flies the player
  (`-Dforbric.clientSmokeElytra=80 -Dforbric.clientSmokeElytraAltitude=200`), the probe sets DABR's roll to 45° and
  posts a 15° roll through NeoForge's `ViewportEvent.ComputeCameraAngles`. At `Camera.extractRenderState` it asserts
  that DABR's camera roll equals the player's roll at that frame's partial tick (negated, as DABR stores it; frames
  where that is still under 1°, the first of a flight, are skipped), that the event roll was kept, and that the render
  orientation and view matrix carry both. Ten good frames print `[C2MERollProbe] CAMERA_PASS`; a failure throws on the render thread.
- **C2ME threading guards** (`MixinFit` reading the whole nest). On server start it checks that C2ME's volatile and
  synchronized fields are in place in `OceanMonumentPieces$RoomDefinition` and `NetherFortressPieces$StartPiece`, then
  builds 64 fortresses and 64 monuments on 8 threads, printing `[C2MERollProbe] STRUCTURES_PASS` or `STRUCTURES_FAIL`.

Build it with `build.py` against the staged carriers and an installed profile's libraries:

```sh
FORBRIC_OLD=<staged root parent> FORBRIC_MC=<installed .minecraft> DBR_JAR=<do_a_barrel_roll jar> \
FORBRIC_JAVA=<jdk25>/bin/java python3 canary/c2me-barrel-roll/build.py
```

The jar lands in `build/c2me-barrel-probe/`. Test fixture only.
