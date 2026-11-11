# World-upgrade canaries (gate-m58)

Two small mods that gate-m58 (`run/gate-m58-world-upgrade.sh`) boots on both sides of the upgrade: the old kernel
(tag `tri-eco-26.2-final`, merged base, two carriers, `run/old-kernel.sh`) writes a save with them, and this tree's
kernel (NeoForge's patched game, one carrier) reads it back. They are built once, from this tree, by
`run/build-world-upgrade-canary.sh`, so both sides run the same jar bytes and a difference is the loader's.

| jar | what it writes in phase `write` and reads back in phase `read` |
|---|---|
| `forbricupgradefabric` (Fabric, `fabric.mod.json`) | fabric-data-attachment-api persistent attachments on the overworld, chunk (0,0), the pig, the chest and the player (`copyOnDeath`); a data component type `forbricupgradefabric:stamp`; a vanilla `SavedData` (`data/forbricupgradefabric/state.dat`). Carries the shared `forbric.worldupgrade.UpgradeProbe`. |
| `forbricupgradeneo` (NeoForge, `META-INF/neoforge.mods.toml`, depends on the Fabric one) | NeoForge attachments on the same five holders, the player's persistent data, a component type `forbricupgradeneo:stamp`, a global and a per-dimension `SavedData`; and it runs the gate's scripts. |

Scripts (run as the server; every line must print `[M58Upgrade] OK command`, or the gate is red):

- `world.commands` — the main save: game rules that switch off clock, weather, random ticks and spawning; a chest at
  0 -60 0 with one item class per slot (enchanted/damaged/named/lored sword, custom data, both canaries' stamps, a
  potion, a written book, a shulker box with contents, real NeoForge mods' items); vanilla block entities and
  state-bearing blocks; a NoAI pig with a fixed UUID, an armor stand, an item frame; real NeoForge mods' blocks and
  items (mcw-bridges, mcw-furniture, waystones, a sophisticatedbackpacks backpack in the chest) and a corpse entity (D53:
  a mod stays in the set only while its population command succeeds; its libraries balm, shogi and sophisticatedcore are
  installed beside it with no population command of their own, though sophisticatedcore's data components ride on the
  backpack and balm's player key BalmData holds waystones' player data); scoreboard, team, command storage and a bossbar.
- `player.commands` — at each login in phase `write`: inventory (with a backpack and waystones in the hotbar), armour,
  offhand, ender chest, XP, an infinite effect, spawn point, a recipe, an advancement, a score.
- `forge.commands` — the save with MinecraftForge-only content (mcw-bridges' MinecraftForge build beside vanilla and
  canary content in one chest).

Both canaries act only in a world the gate owns (`<root>/.m58-owned` holds `-Dforbric.worldUpgradeToken`, and the
running world is `-Dforbric.worldUpgradeWorld`); anywhere else they print one `DISARMED` line and do nothing. Phase
`read` writes `neo-read.json`, `fabric-read.json` (values + the registry census the gate checks a snapshot's names
against) and `*-read-player.json` to `-Dforbric.worldUpgradeOutput`.
