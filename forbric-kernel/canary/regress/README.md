# Regress probe

A Fabric mod (`fabric/`, id `regressprobe`) with a NeoForge half (`neo/`, id `regressprobeneo`) that measures, in a
dedicated server on the staged game (NeoForge's patched base and its carrier), three Fabric behaviours NeoForge's base
had stopped delivering until the kernel adapted the Fabric mixin, and one it delivers natively:

- `damage.*`: fabric-item-api's `CustomDamageHandler` is asked by `ItemStack.hurtAndBreak(int, LivingEntity, EquipmentSlot)`
  for a mob and for a player (`FabricItemDamageMixinAdapter`, off switch `-Dforbric.itemDamageHandler=off`); an iron sword
  wears and breaks as in vanilla.
- `shears.*`: BCLib's tag shears (`c:tools/shear`) shear a sheep and a snow golem (`MixinShearsRelay`'s mob rows, off switch
  `-Dforbric.shearsRelayMobs=off`); vanilla shears shear once (1 to 3 wool, one point of durability); a stick does not.
  Two items in the tag bring an entity entry of their own: the Fabric one is pre-empted, as native Fabric with BCLib
  pre-empts it, and the NeoForge one runs its own entry, as on NeoForge.
- `fuel.*`: fabric-item-api's stack-aware crafting remainder reaches furnace fuel, asked once on the unshrunk stack.
  NeoForge's `consumeFuel` already asks it so; this is a standing check that it stays so.

From `forbric-kernel/`, with the game artifacts staged:

```sh
python3 run/compat/regress-gate.py \
  --fabric-api run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar \
  --bclib build/compat-inputs/sweep90/mods/bclib-26.201.2.jar \
  --mod build/compat-inputs/sweep90/mods/worldweaver-26.201.2.jar --mod /path/to/wunderlib-26.201.0.jar \
  --staged-root /path/to/stage/run [--port N] [--output DIR]
```

The gate compiles both halves, then runs three servers: the fixed run must pass all 15 cases and leave
fabric-item-api's `ItemStackMixin` and BCLib's `SheepMixin` / `SnowGolemMixin` unsuspected in the compatibility report;
`-Dforbric.itemDamageHandler=off` must fail exactly `damage.mob` and `damage.player` and report `ItemStackMixin`
suspected; `-Dforbric.shearsRelayMobs=off` must fail exactly `shears.sheep.tag`, `shears.snowGolem.tag` and
`shears.sheep.ownEntry.fabric` and report the two BCLib mixins suspected. Every server must save and stop normally.
`summary.json` also lists fabric-item-api's `AbstractFurnaceBlockEntityMixin` rows, which the census still calls
suspected although `fuel.*` measure the behaviour as native; they are recorded, not judged.
