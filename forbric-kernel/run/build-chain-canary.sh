#!/usr/bin/env bash
# Built run/canary/forbricchainprobe.jar for gate-m41: a NeoForge mod whose MinecraftForge listener vetoes one tagged
# entity joining the level. The probe's listeners are MinecraftForge's (net.minecraftforge event buses), so it compiles
# only against a MinecraftForge carrier, and since Stage 4 of dropping MinecraftForge no stage carries one: Forbric runs
# NeoForge's patched game with the NeoForge carrier alone. gate-m41 exists only for the deleted Neo->MinecraftForge
# forwards and is deleted with this builder and canary/event-chain (gates map, C5). Nothing NeoForge-only is left in it
# to keep.
# Until then the builder stops here with a reason instead of reading a stale MinecraftForge carrier off an old stage.
echo "[kernel] FAIL M41 canary retired: canary/event-chain listens on MinecraftForge's buses and no stage carries MinecraftForge since Stage 4; gate-m41 goes with it" >&2
exit 1
