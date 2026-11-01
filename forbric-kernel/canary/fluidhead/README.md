# Fluid HEAD-handler probe

A NeoForge probe mod for `run/compat/fluid-head-gate.py`. It exercises the released HEAD handlers of
`LiquidBlock.shouldSpreadLiquid` — fabric-block-api's `FluidFlowEvents` veto (fabric-api 0.161.0+, block-api 3.1.0) and,
when Create Fly is loaded, its lava + honey reaction — on placement and on a neighbour change, and writes
`fluid-head-probe.json`. Vanilla asks `shouldSpreadLiquid` in both `onPlace` and `neighborChanged`; NeoForge's
`neighborChanged` asks its fluid interaction registry instead, so without `MixinFluidReactionAdapter`'s HEAD wrap the
handlers run on placement alone. Only staged in isolated gate worlds.
