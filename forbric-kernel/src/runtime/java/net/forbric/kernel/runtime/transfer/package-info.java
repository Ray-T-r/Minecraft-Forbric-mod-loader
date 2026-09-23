/**
 * Cross-ecosystem block-entity item/fluid transfer, compiled against Fabric transfer 8.0.11 and NeoForge
 * 26.2.0.88. Every built game side carries this package: without the Fabric API compile input the build fails
 * rather than shipping a runtime jar without it. It is never a Fabric mod.
 *
 * <p>Boot integration: install TransferTransactionHooks and TransferCapabilityFallback in the pre-Mixin chain
 * only when both selected APIs exist. After native capability registration, invoke BlockTransferBridge.install
 * through the GAME classloader. Installation checks every hook marker before exposing any fallback. The native
 * provider gets the first answer; a provider from another ecosystem is consulted only when that answer is absent.
 * TransferIssues.setReporter can connect runtime findings to the kernel's attributed compatibility report.
 *
 * <p>The bridge pairs native transaction objects, including every outer ancestor, and closes both at the same
 * nesting boundary. Final notifications run only after both roots close, once per native participant, and may
 * open new roots and transfer again. Transfer during a close callback and joining independently-open roots are
 * unsupported. A callback throwing after commit is reported and both engines are released; external side effects
 * are not magically rolled back.
 *
 * <p>Fabric SlottedStorage is required when exposing a NeoForge ResourceHandler: an arbitrary Storage cannot
 * supply indexed insertion. Fabric has no exact resource-specific validity query; our advertised validity is
 * conservative and the real provider's insert remains authoritative. Fluid units convert exactly at 81 Fabric
 * units per millibucket, with sub-millibucket leftovers retained at their source. Failed quantization trials abort
 * before retry; amounts are never rounded after mutation. Registry identity and DataComponentPatch are preserved.
 *
 * <p>Only a loaded ServerLevel block entity on its server thread is eligible. Directions, including null, are
 * passed unchanged. Wrappers resolve providers afresh for each operation, hold world/entity references weakly,
 * and become empty after removal or unload. Capability invalidation during a transfer aborts its nested operation.
 * Recursive fallback lookup returns absent instead of wrapping itself. No level is force-loaded by this bridge.
 *
 * <p>ForgeSnapshotAdapters grants transactional writes only to exact audited ItemStackHandler and FluidTank
 * implementations, with the default or explicitly approved pure fluid validator. All wrappers for one handler
 * share one real journal. Subclasses and arbitrary proxies are rejected. ForgeLegacyFacades supports the opposite
 * direction using a real transaction per simulate/execute call; it never promises that separate old-style calls
 * form an atomic transfer. Non-empty Forge fluid tags require a per-fluid codec that round-trips exactly to the
 * other APIs' component patch. Unslotted Fabric storage, entity/item capabilities and energy remain outside scope.
 *
 * <p>Register ForgeTransferCapabilityFallback immediately after the existing capability composition. Its root
 * BlockEntity fallback declines to answer from a super-call when a subclass overrides getCapability, preserving
 * that subclass's native authority. A Forge consumer can query Fabric/NeoForge block entities inheriting the
 * composed root; arbitrary override shapes are not rewritten. The existing provider and invalidation remain live.
 */
package net.forbric.kernel.runtime.transfer;
