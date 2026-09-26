#!/usr/bin/env bash
# The gate-m9 / gate-m4 assertion set, pack-independent half, run against an arbitrary log.
set -u
LOG="${1:?usage: assert.sh <log>}"; FAIL=0
if [ ! -s "$LOG" ]; then
    printf 'FAIL  log is missing or empty: %s\n' "$LOG"
    exit 1
fi
ck()  { if grep -qaE "$2" "$LOG"; then printf 'PASS  %s\n' "$1"; else printf 'FAIL  %s\n' "$1"; FAIL=1; fi; }
abs() { if grep -qaE "$2" "$LOG"; then printf 'FAIL  %s  (found: %s)\n' "$1" "$(grep -aoE "$2" "$LOG" | head -1)"; FAIL=1; else printf 'PASS  %s (absent)\n' "$1"; fi; }
echo "== must be present =="
ck  "smoke controller armed"            "ClientSmoke\] armed on Minecraft.tick"
ck  "joined a world"                    "ClientSmoke\] joined world via quick-play"
ck  "survived real simulation"          "ClientSmoke\] client-ready after"
ck  "window title read"                 "ClientSmoke\] window title: Minecraft"
abs "…and names no single loader"       "ClientSmoke\] window title: .*(NeoForge|Forge|Fabric)"
ck  "left the world cleanly"            "ClientSmoke\] clean disconnect observed"
ck  "server side really ran"            "joined the game|加入了游戏"
ck  "datapacks fully loaded"            "Loaded [1-9][0-9]* advancements"
ck  "FML construct posted (Neo)"        "posted FML construct to [1-9][0-9]* NeoForge mod"
ck  "FML construct posted (Forge)"      "posted FML construct to [1-9][0-9]* traditional-Forge mod"
ck  "client setup posted"               "posted FML client setup to [1-9][0-9]* NeoForge mod"
ck  "common setup inside Minecraft ctor" "\[Render thread/INFO\]: \[Forbric/Lifecycle\] posted FML common setup to [1-9][0-9]* NeoForge mod"
abs "…not from the pre-Minecraft window" "\[main/INFO\]: \[Forbric/Lifecycle\] posted FML common setup"
ck  "registration events ran"           "ran NeoForge.s registration events.* [1-9][0-9]* data map type"
ck  "load complete posted"              "posted FML load complete to [1-9][0-9]* NeoForge mod"
ck  "construction is dependency order"  "Forbric/Order\] construction order is dependency order"
ck  "Fabric init is dependency order"   "Forbric/Order\] [1-9][0-9]* Fabric mod\(s\) initialise in dependency order"
ck  "Fabric mains in Minecraft.<init>" "\[Render thread/INFO\]: \[Forbric/Fabric\] invoked [1-9][0-9]* Fabric main entrypoint\(s\) in the Minecraft.<init> window"
abs "…not the pre-Minecraft window"     "\[main/INFO\]: \[Forbric/Fabric\] invoked [0-9]+ Fabric main entrypoint"
ck  "datapacks served"                  "Forbric/DataPacks\] served [1-9][0-9]* datapack"
ck  "registry alias parity restored"    "Forbric/Aliases\] gave .* alias-resolving lookup"
ck  "NeoForge registration order"       "fired RegisterEvent in NeoForge.s registration order"
ck  "client bridge pass complete"       "all 1 CLIENT_MOD_BUS bridge\(s\) installed"
ck  "game-bus bridge pass complete"     "EventMux\] all [0-9]+ GAME_BUS bridge\(s\) installed"
ck  "client game-bus bridges on"        "EventMux\] all [0-9]+ CLIENT_GAME_BUS bridge\(s\) installed"
echo "== must be absent =="
abs "no mod failed a phase"             "failed during (construct|IMC enqueue|IMC process)"
abs "no bridge reported missing"        "bridge\(s\) MISSING"
abs "no unbound data component"         "Trying to access unbound value"
abs "no RegisterEvent listener failed"  "RegisterEvent listener failed"
abs "no tag lost to a dangling id"      "Couldn.t load tag"
abs "JEI-style empty plugin list"       "plugins must not be empty"
abs "no null pack reached the repo"     "streamSelfAndChildren.* because .pack. is null"
abs "no pack metadata read failed"      "Failed to read pack .* metadata"
abs "no entity missing attributes"      "has no attributes"
abs "render-layer latch is set"         "Render layers can only be set"
abs "no skipped-element leak"           "StubException"
abs "no duplicate registry key"         "Duplicate key ResourceKey"
abs "no NightConfig version split"      "StampedConfig does not support valueMap"
abs "no network protocol error"         "Network Protocol Error"
abs "no mod loading failure"            "Mod Loading has failed"
abs "no crash report"                   "Preparing crash report"
abs "no resourcepack wipe"              "Caught error loading resourcepacks"
if [ -n "${ASSERT_EXTRA:-}" ]; then
    if [ ! -f "$ASSERT_EXTRA" ]; then
        printf 'FAIL  assertion file is missing: %s\n' "$ASSERT_EXTRA"
        FAIL=1
    else
        # Pack-local files call the same ck/abs functions and contribute to FAIL.
        source "$ASSERT_EXTRA" || FAIL=1
    fi
fi
echo "== result: $([ $FAIL = 0 ] && echo ALL-GREEN || echo HAS-FAILURES) =="
exit "$FAIL"
