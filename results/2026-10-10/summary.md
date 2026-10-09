# Forbric nightly 2026-10-10: FAIL

| | |
| --- | --- |
| commit | [`520d7aad11cc`](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/commit/520d7aad11cc7c9b8c8278b5f541e4429e1f7623) Describe the writeByte repair and ThinnedCallOrdinals |
| tested | `origin/main` on the developer's Mac (commit status `nightly/dev-mac`) |
| started | 2026-10-10 02:30 +0800 |
| soak (`gate-m34-soak.sh`) | skipped: it runs on Sundays |
| integration | FAILED (exit 1) in 3 min |
| gates | FAILED (exit 1) in 34 min: 47 GREEN, 10 RED, 1 SKIP |

## Integration: `python3 tools/dev.py integration`

### JUnit totals

| suite | tests | executed | skipped | skip % | failed |
| --- | ---: | ---: | ---: | ---: | ---: |
| test | 3668 | 3668 | 0 | 0.0 | 6 |

Did not execute (no results): `transferTest`

**Failed (6):**
- `test` net.forbric.kernel.boot.KernelBundledMixinExtrasStagedTest.[1] true (org.opentest4j.AssertionFailedError)
- `test` net.forbric.kernel.boot.KernelBundledMixinExtrasStagedTest.[2] false (org.opentest4j.AssertionFailedError)
- `test` net.forbric.kernel.mixin.MixinRetargetRenamedBodyCorpusTest.r3MovesExactlyThePinnedInjectorsOverTheAuditCorpus() (org.opentest4j.AssertionFailedError)
- `test` net.forbric.kernel.mixin.MixinTwinRebindTest.releasedLiquidBounceMovesItsFaceTestAndNothingElse() (org.opentest4j.AssertionFailedError)
- `test` net.forbric.kernel.mixin.ReplacedCallRedirectsTest.releasedViaFabricPlusMovesItsThreeRedirects() (org.opentest4j.AssertionFailedError)
- `test` net.forbric.kernel.mixin.ThinnedCallOrdinalsTest.releasedViaFabricPlusCountsOnTheMergedBody() (org.opentest4j.AssertionFailedError)

## Gates: `bash forbric-kernel/run/compat/gates-all.sh -j auto --skip gate-m34-soak.sh`

- `gate-m0.sh` RED (exit=1)
- `gate-m2b.sh` RED (exit=1)
- `gate-m3.sh` RED (exit=1)
- `gate-m4-canary.sh` RED (exit=1)
- `gate-m12-multiplayer.sh` RED (exit=1)
- `gate-m18-presence.sh` RED (exit=1)
- `gate-m19-nesteddupe.sh` RED (exit=1)
- `gate-m24-brokenmod.sh` RED (exit=1)
- `gate-m24b-badmetadata.sh` RED (exit=1)
- `gate-m34-soak.sh` SKIP (explicit --skip)
- `gate-m54-carry.sh` RED (exit=3)

<details><summary>All 58 RESULT lines</summary>

```
RESULT gate-m0.sh RED (exit=1)
RESULT gate-m1.sh GREEN (exit=0)
RESULT gate-m2.sh GREEN (exit=0)
RESULT gate-m2b.sh RED (exit=1)
RESULT gate-m3.sh RED (exit=1)
RESULT gate-m4.sh GREEN (exit=0)
RESULT gate-m4-canary.sh RED (exit=1)
RESULT gate-m7-neo.sh GREEN (exit=0)
RESULT gate-m8-packmeta.sh GREEN (exit=0)
RESULT gate-m9-client.sh GREEN (exit=0)
RESULT gate-m10-overlay.sh GREEN (exit=0)
RESULT gate-m11-serverconfig.sh GREEN (exit=0)
RESULT gate-m12-multiplayer.sh RED (exit=1)
RESULT gate-m13-anticheat.sh GREEN (exit=0)
RESULT gate-m14-fabric-server.sh GREEN (exit=0)
RESULT gate-m15-forge-network.sh GREEN (exit=0)
RESULT gate-m16-forge-handshake.sh GREEN (exit=0)
RESULT gate-m17-installer.sh GREEN (exit=0)
RESULT gate-m18-presence.sh RED (exit=1)
RESULT gate-m19-nesteddupe.sh RED (exit=1)
RESULT gate-m20-depdialog.sh GREEN (exit=0)
RESULT gate-m21-forgesetup.sh GREEN (exit=0)
RESULT gate-m22-exitswap.sh GREEN (exit=0)
RESULT gate-m23-elytra.sh GREEN (exit=0)
RESULT gate-m24-brokenmod.sh RED (exit=1)
RESULT gate-m24b-badmetadata.sh RED (exit=1)
RESULT gate-m24c-disabled.sh GREEN (exit=0)
RESULT gate-m25-worldgen.sh GREEN (exit=0)
RESULT gate-m26-forgeclient.sh GREEN (exit=0)
RESULT gate-m27-frame.sh GREEN (exit=0)
RESULT gate-m28-forgeconfig.sh GREEN (exit=0)
RESULT gate-m29-forgecaps.sh GREEN (exit=0)
RESULT gate-m30-attribution.sh GREEN (exit=0)
RESULT gate-m31-vanilla-parity.sh GREEN (exit=0)
RESULT gate-m32-savedrop.sh GREEN (exit=0)
RESULT gate-m33-transfer.sh GREEN (exit=0)
RESULT gate-m34-soak.sh SKIP (explicit --skip)
RESULT gate-m35-behavior.sh GREEN (exit=0)
RESULT gate-m36-mixin-outcome.sh GREEN (exit=0)
RESULT gate-m37-entity-callbacks.sh GREEN (exit=0)
RESULT gate-m38-enchantment-contracts.sh GREEN (exit=0)
RESULT gate-m39-transfer-core.sh GREEN (exit=0)
RESULT gate-m40-energy.sh GREEN (exit=0)
RESULT gate-m41-event-chain.sh GREEN (exit=0)
RESULT gate-m42-coremod-parity.sh GREEN (exit=0)
RESULT gate-m43-break-and-loot.sh GREEN (exit=0)
RESULT gate-m44-interaction-events.sh GREEN (exit=0)
RESULT gate-m45-everyday-actions.sh GREEN (exit=0)
RESULT gate-m46-stub-rebind.sh GREEN (exit=0)
RESULT gate-m47-damage-events.sh GREEN (exit=0)
RESULT gate-m48-server-events.sh GREEN (exit=0)
RESULT gate-m49-world-events.sh GREEN (exit=0)
RESULT gate-m50-load-predicates.sh GREEN (exit=0)
RESULT gate-m51-tooltips.sh GREEN (exit=0)
RESULT gate-m52-hopper-fabric-storage.sh GREEN (exit=0)
RESULT gate-m53-widened-new.sh GREEN (exit=0)
RESULT gate-m54-carry.sh RED (exit=3)
RESULT gate-m55-creative-search.sh GREEN (exit=0)
```

</details>

Logs stay on the Mac, in the nightly worktree: `build/nightly/` and `forbric-kernel/build/gates/`.
