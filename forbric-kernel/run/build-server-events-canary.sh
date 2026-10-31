#!/usr/bin/env bash
# Built run/canary/forbricserverevents.jar for gate-m48: a NeoForge mod whose MinecraftForge server listeners are
# written the way packedup's and Tombstone's are. The probe's listeners are MinecraftForge's (net.minecraftforge event
# buses), so it compiles only against a MinecraftForge carrier, and since Stage 4 of dropping MinecraftForge no stage
# carries one: Forbric runs NeoForge's patched game with the NeoForge carrier alone. gate-m48 exists only for the
# deleted Neo->MinecraftForge forwards and is deleted with this builder and canary/server-events (gates map, C5). Its
# experience-drop cases (through NeoForge's own hook) are the gates map's to move into gate-m43 if kept.
# Until then the builder stops here with a reason instead of reading a stale MinecraftForge carrier off an old stage.
echo "[kernel] FAIL M48 canary retired: canary/server-events listens on MinecraftForge's buses and no stage carries MinecraftForge since Stage 4; gate-m48 goes with it" >&2
exit 1
