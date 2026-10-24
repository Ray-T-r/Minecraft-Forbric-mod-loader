# Breath and drown canaries (gate-m57)

Two mods, each using only its own loader's API, built by `run/build-breath-drown-canary.sh` into `run/canary/`:

- `forge/` — `forbricbreathforge`, MinecraftForge: a drowning liquid `murk` (MinecraftForge fluid type, a
  `LiquidBlock` and a `BucketItem` built from suppliers), and listeners for `LivingBreatheEvent`, `LivingDrownEvent`
  (which can also set the bubble count), `LivingAttackEvent` and `LivingHurtEvent` registered with `BUS.addListener`,
  plus `@SubscribeEvent` listeners (`ForgeAnnotated`) for drowning and `LivingDamageEvent` that cancel by returning
  true, and one for breathing.
- `neo/` — `forbricbreathneo`, NeoForge: its own `murk` and bucket, and listeners for breathing, drowning,
  `LivingIncomingDamageEvent` and `LivingDamageEvent.Pre`.
- `common/src/Core.java` — the world probe, copied into each jar under the jar's own package. The jar whose family
  `-Dforbric.breathDrownDriver` names plays the scenarios (`Core.plan`) and writes `-Dforbric.breathDrownProbe`.
  Listeners act on the body's entity tags (`f-*` MinecraftForge, `n-*` NeoForge); a tag of the other family does
  nothing, so on a native loader every scenario is what that family's listeners do alone.

`native-reference.json` is what native MinecraftForge 26.2-65.0.1 and NeoForge 26.2.0.88 do with these jars, every
judged field and the body's random draws agreeing across three runs each. It records the digest of the `.java` and `.toml` sources it was measured
with, and gate-m57 refuses a reference measured with other sources. After changing a canary, regenerate it from the
native servers that `run/compat/native-controls.py prepare` installs:

```sh
python3 forbric-kernel/run/compat/breath-drown.py reference      # M57_NATIVE=<dir> if the images live elsewhere
```

Test fixtures only.
