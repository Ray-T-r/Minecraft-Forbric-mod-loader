#!/usr/bin/env bash
# Built run/canary/forbricworldevents.jar for gate-m49: a NeoForge mod whose MinecraftForge world and entity listeners
# are written the way packedup's and Tombstone's are. The probe's listeners are MinecraftForge's (net.minecraftforge
# event buses), so it compiles only against a MinecraftForge carrier, and since Stage 4 of dropping MinecraftForge no
# stage carries one: Forbric runs NeoForge's patched game with the NeoForge carrier alone. gate-m49 exists only for the
# deleted Neo->MinecraftForge forwards and is deleted with this builder and canary/world-events (gates map, C5). Its
# drown.neo case (NeoForge's LivingConversionEvent.Post, NeoConversionPost's only symptom check) must move to a NeoForge
# canary before the gate goes; the source stays here until then.
# Until then the builder stops here with a reason instead of reading a stale MinecraftForge carrier off an old stage.
echo "[kernel] FAIL M49 canary retired: canary/world-events listens on MinecraftForge's buses and no stage carries MinecraftForge since Stage 4; gate-m49 goes with it" >&2
exit 1
