#!/usr/bin/env bash
# Built run/canary/forbricdamageprobe.jar for gate-m47: a NeoForge mod whose MinecraftForge damage listeners are written
# the way Tombstone's are. The probe's listeners are MinecraftForge's (net.minecraftforge event buses), so it compiles
# only against a MinecraftForge carrier, and since Stage 4 of dropping MinecraftForge no stage carries one: Forbric runs
# NeoForge's patched game with the NeoForge carrier alone. gate-m47 exists only for the deleted Neo->MinecraftForge
# forwards and is deleted with this builder and canary/damage-events (gates map, C5). Its NeoForge damage pipeline
# controls are the gates map's to re-home before the gate goes.
# Until then the builder stops here with a reason instead of reading a stale MinecraftForge carrier off an old stage.
echo "[kernel] FAIL M47 canary retired: canary/damage-events listens on MinecraftForge's buses and no stage carries MinecraftForge since Stage 4; gate-m47 goes with it" >&2
exit 1
